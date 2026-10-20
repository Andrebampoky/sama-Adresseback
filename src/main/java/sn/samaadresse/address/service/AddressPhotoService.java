package sn.samaadresse.address.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.photo.PhotoProcessor;
import sn.samaadresse.photo.PhotoStorage;

/** Photo de façade : envoi par le propriétaire, lecture par le propriétaire, l'admin ou le public. */
@Service
public class AddressPhotoService {
    private final AddressRepository addresses;
    private final PhotoProcessor processor;
    private final PhotoStorage storage;

    public AddressPhotoService(AddressRepository addresses, PhotoProcessor processor, PhotoStorage storage) {
        this.addresses = addresses;
        this.processor = processor;
        this.storage = storage;
    }

    @Transactional
    public Address replace(UUID ownerId, UUID addressId, byte[] upload) {
        Address address = owned(ownerId, addressId);
        if (address.getStatus() != AddressStatus.PENDING_VALIDATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La photo ne peut être ajoutée ou remplacée que pendant la vérification de l'adresse.");
        }
        // Image validée et nettoyée avant tout enregistrement.
        String key = storage.store(processor.sanitize(upload));
        String previous = address.attachPhoto(key);
        storage.delete(previous);
        return address;
    }

    @Transactional
    public Address remove(UUID ownerId, UUID addressId) {
        Address address = owned(ownerId, addressId);
        storage.delete(address.removePhoto());
        return address;
    }

    @Transactional(readOnly = true)
    public byte[] ownerPhoto(UUID ownerId, UUID addressId) {
        return read(owned(ownerId, addressId));
    }

    @Transactional(readOnly = true)
    public byte[] adminPhoto(UUID addressId) {
        return read(addresses.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adresse introuvable.")));
    }

    /** Même règle que la fiche publique : validée et publication consentie, sinon 404. */
    @Transactional(readOnly = true)
    public byte[] publicPhoto(String rawCode) {
        Address address = addresses.findByPublicCodeAndStatusAndPubliclyVisibleTrue(
                        AddressCode.normalize(rawCode), AddressStatus.VALIDATED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo introuvable."));
        return read(address);
    }

    private Address owned(UUID ownerId, UUID addressId) {
        return addresses.findByIdAndOwnerId(addressId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adresse introuvable."));
    }

    private byte[] read(Address address) {
        return storage.load(address.getPhotoUrl())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo introuvable."));
    }
}
