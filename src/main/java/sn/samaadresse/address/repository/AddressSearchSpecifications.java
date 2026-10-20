package sn.samaadresse.address.repository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.user.domain.UserAccount;

/**
 * Requête de l'annuaire construite critère par critère (API Criteria de JPA). Seuls les critères
 * renseignés deviennent des conditions SQL : pas de paramètre nul envoyé à PostgreSQL, qui ne
 * saurait pas en déduire le type. Les motifs reçus sont déjà nettoyés et en minuscules (« %kande% »).
 */
public final class AddressSearchSpecifications {
    private AddressSearchSpecifications() {
    }

    public static Specification<Address> publicDirectory(String codePrefix, String district, String city,
                                                         PlaceType placeType, String owner, String text) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            // Règle de publication : validée ET consentie, comme pour la fiche publique.
            where.add(cb.equal(root.get("status"), AddressStatus.VALIDATED));
            where.add(cb.isTrue(root.get("publiclyVisible")));
            Join<Address, UserAccount> ownerJoin = root.join("owner", JoinType.LEFT);
            // Le nom n'est cherchable que si le propriétaire l'a rendu public.
            Predicate ownerNameSearchable = cb.isTrue(root.get("ownerNamePublic"));
            if (codePrefix != null) {
                where.add(cb.like(root.get("publicCode"), codePrefix));
            }
            if (district != null) {
                where.add(cb.like(cb.lower(root.get("district")), district));
            }
            if (city != null) {
                where.add(cb.like(cb.lower(root.get("city")), city));
            }
            if (placeType != null) {
                where.add(cb.equal(root.get("placeType"), placeType));
            }
            if (owner != null) {
                where.add(cb.and(ownerNameSearchable, cb.like(cb.lower(ownerJoin.get("displayName")), owner)));
            }
            if (text != null) {
                where.add(cb.or(
                        cb.like(cb.lower(root.get("title")), text),
                        cb.like(cb.lower(root.get("district")), text),
                        cb.like(cb.lower(root.get("city")), text),
                        cb.like(cb.lower(root.get("publicCode")), text),
                        cb.and(ownerNameSearchable, cb.like(cb.lower(ownerJoin.get("displayName")), text))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
