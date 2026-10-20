package sn.samaadresse.address.service;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.repository.AddressRepository;

/** Opérations réservées au rôle ADMIN (vérifié par SecurityConfig sur /api/v1/admin/**). */
@Service
public class ModerationService {
    private final AddressRepository addresses;

    public ModerationService(AddressRepository addresses) {
        this.addresses = addresses;
    }

    @Transactional(readOnly = true)
    public Page<Address> listByStatus(AddressStatus status, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        return addresses.findByStatus(status, request);
    }

    @Transactional
    public Address decide(UUID addressId, AddressStatus decision, UUID moderatorId) {
        Address address = addresses.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adresse introuvable."));
        try {
            address.moderate(decision, moderatorId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return address;
    }
}
