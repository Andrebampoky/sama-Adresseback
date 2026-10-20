package sn.samaadresse.address.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
import sn.samaadresse.address.repository.AddressSearchSpecifications;
import sn.samaadresse.common.FieldValidationException;

@Service
public class AddressService {
    private final AddressRepository addressRepository;

    public AddressService(AddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }

    static final int MAX_TEXT = 80;
    static final int MAX_PAGE_SIZE = 50;

    @Transactional(readOnly = true)
    public Address findValidatedPublicAddress(String rawCode) {
        String code = AddressCode.normalize(rawCode);
        // Un lieu ne devient consultable qu'après validation et autorisation de visibilité publique.
        return addressRepository.findByPublicCodeAndStatusAndPubliclyVisibleTrue(code, AddressStatus.VALIDATED)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Aucune adresse publique validée ne correspond à ce code."
                ));
    }

    /**
     * Annuaire public paginé (20 résultats par défaut, 50 au plus), du plus récent au plus ancien.
     * Les critères textuels sont réduits à leurs caractères utiles ; les jokers SQL saisis sont ignorés.
     */
    @Transactional(readOnly = true)
    public Page<Address> searchDirectory(DirectoryQuery query, int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        String q = contains(query.q(), "q", errors);
        String owner = contains(query.owner(), "owner", errors);
        String district = contains(query.district(), "district", errors);
        String city = contains(query.city(), "city", errors);
        String code = clean(query.code(), "code", errors);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return addressRepository.findAll(AddressSearchSpecifications.publicDirectory(
                code == null ? null : code.toUpperCase(Locale.ROOT) + "%",
                district, city, query.placeType(), owner, q), request);
    }

    private static String contains(String raw, String field, Map<String, String> errors) {
        String value = clean(raw, field, errors);
        return value == null ? null : "%" + value.toLowerCase(Locale.ROOT) + "%";
    }

    private static String clean(String raw, String field, Map<String, String> errors) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace("%", "").replace("_", "").trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_TEXT) {
            errors.put(field, "Critère trop long (" + MAX_TEXT + " caractères au maximum).");
        }
        return value;
    }
}
