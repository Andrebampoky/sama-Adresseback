package sn.samaadresse.address.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.common.FieldValidationException;

@Service
public class OwnerAddressService {
    private static final Logger log = LoggerFactory.getLogger(OwnerAddressService.class);
    private final AddressRepository addresses;
    private final AddressCodeGenerator codeGenerator;
    private final TransactionTemplate newTransaction;
    private final BigDecimal minLatitude;
    private final BigDecimal maxLatitude;
    private final BigDecimal minLongitude;
    private final BigDecimal maxLongitude;

    public OwnerAddressService(
            AddressRepository addresses,
            AddressCodeGenerator codeGenerator,
            PlatformTransactionManager transactionManager,
            @Value("${app.address.bounds.min-latitude:12.0}") BigDecimal minLatitude,
            @Value("${app.address.bounds.max-latitude:16.8}") BigDecimal maxLatitude,
            @Value("${app.address.bounds.min-longitude:-17.6}") BigDecimal minLongitude,
            @Value("${app.address.bounds.max-longitude:-11.3}") BigDecimal maxLongitude
    ) {
        this.addresses = addresses;
        this.codeGenerator = codeGenerator;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.minLatitude = minLatitude;
        this.maxLatitude = maxLatitude;
        this.minLongitude = minLongitude;
        this.maxLongitude = maxLongitude;
    }

    /**
     * Crée l'adresse avec un code public unique (case de la grille, puis suffixe de départage).
     *
     * Volontairement sans transaction englobante : chaque tentative d'insertion a sa propre
     * transaction. Si deux créations simultanées tirent le même code, la contrainte UNIQUE de
     * PostgreSQL rejette la seconde ; sa transaction est annulée et on passe au code suivant.
     */
    public Address create(UUID ownerId, NewAddress input) {
        checkWithinServiceArea(input.latitude(), input.longitude());
        BigDecimal latitude = input.latitude().setScale(6, RoundingMode.HALF_UP);
        BigDecimal longitude = input.longitude().setScale(6, RoundingMode.HALF_UP);
        String description = input.description() == null || input.description().isBlank()
                ? null : input.description().trim();

        for (int attempt = 0; attempt < AddressCodeGenerator.MAX_ATTEMPTS; attempt++) {
            String code = codeGenerator.candidate(input.city(), input.district(), latitude, longitude, attempt);
            // Vérification applicative : évite la plupart des collisions sans passer par une erreur SQL.
            if (addresses.existsByPublicCode(code)) {
                continue;
            }
            Address candidate = Address.createdByOwner(ownerId, code, input.title().trim(), input.placeType(),
                    input.district().trim(), input.city().trim(), description, latitude, longitude,
                    input.publicationConsent(), input.ownerNamePublic());
            try {
                return newTransaction.execute(status -> addresses.saveAndFlush(candidate));
            } catch (DataIntegrityViolationException e) {
                if (!isPublicCodeConflict(e)) {
                    throw e;
                }
                // Collision détectée par la base (création concurrente dans la même case) : essai suivant.
                log.info("Collision de code public détectée par la base, essai suivant.");
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Impossible d'attribuer un code unique pour le moment. Réessayez.");
    }

    public Page<Address> listOwned(UUID ownerId, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return addresses.findByOwnerId(ownerId, request);
    }

    public Address getOwned(UUID ownerId, UUID addressId) {
        // 404 plutôt que 403 : on ne confirme pas l'existence d'une adresse appartenant à quelqu'un d'autre.
        return addresses.findByIdAndOwnerId(addressId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adresse introuvable."));
    }

    /**
     * Modifie le contenu et la visibilité. Renvoie vrai si l'adresse repasse en vérification
     * (contenu modifié après une décision de l'équipe).
     */
    @Transactional
    public boolean update(UUID ownerId, UUID addressId, String title, PlaceType placeType, String description,
                          boolean publicationConsent, boolean ownerNamePublic) {
        Address address = getOwned(ownerId, addressId);
        String cleanDescription = description == null || description.isBlank() ? null : description.trim();
        try {
            boolean backToReview = address.editContent(title.trim(), placeType, cleanDescription);
            // Le nom n'est publiable qu'avec la publication de l'adresse elle-même.
            address.changeVisibility(publicationConsent, ownerNamePublic && publicationConsent);
            return backToReview;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @Transactional
    public Address archive(UUID ownerId, UUID addressId) {
        Address address = getOwned(ownerId, addressId);
        try {
            address.archive(ownerId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return address;
    }

    /**
     * Corrige la position d'une adresse encore en attente de vérification et lui attribue le code de
     * la nouvelle case (même règle de grille et de départage qu'à la création). Si la nouvelle position
     * tombe dans la même case, le code est conservé. Chaque essai a sa propre transaction : une collision
     * détectée par la contrainte UNIQUE fait passer au code suivant, comme à la création.
     */
    public Address relocate(UUID ownerId, UUID addressId, BigDecimal rawLatitude, BigDecimal rawLongitude) {
        checkWithinServiceArea(rawLatitude, rawLongitude);
        BigDecimal latitude = rawLatitude.setScale(6, RoundingMode.HALF_UP);
        BigDecimal longitude = rawLongitude.setScale(6, RoundingMode.HALF_UP);
        Address current = getOwned(ownerId, addressId);
        if (current.getStatus() != sn.samaadresse.address.domain.AddressStatus.PENDING_VALIDATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La position ne peut être corrigée que pendant la vérification ; créez une nouvelle adresse.");
        }
        for (int attempt = 0; attempt < AddressCodeGenerator.MAX_ATTEMPTS; attempt++) {
            String code = codeGenerator.candidate(current.getCity(), current.getDistrict(), latitude, longitude, attempt);
            if (!code.equals(current.getPublicCode()) && addresses.existsByPublicCode(code)) {
                continue;
            }
            try {
                return newTransaction.execute(status -> {
                    Address address = getOwned(ownerId, addressId);
                    address.relocate(code, latitude, longitude);
                    return addresses.saveAndFlush(address);
                });
            } catch (DataIntegrityViolationException e) {
                if (!isPublicCodeConflict(e)) {
                    throw e;
                }
                log.info("Collision de code public lors d'une correction de position, essai suivant.");
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Impossible d'attribuer un code unique pour le moment. Réessayez.");
    }

    private void checkWithinServiceArea(BigDecimal latitude, BigDecimal longitude) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (latitude.compareTo(minLatitude) < 0 || latitude.compareTo(maxLatitude) > 0) {
            errors.put("latitude", "La latitude doit se situer dans la zone couverte (Sénégal).");
        }
        if (longitude.compareTo(minLongitude) < 0 || longitude.compareTo(maxLongitude) > 0) {
            errors.put("longitude", "La longitude doit se situer dans la zone couverte (Sénégal).");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    private static boolean isPublicCodeConflict(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("uk_addresses_public_code")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
