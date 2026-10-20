package sn.samaadresse.address.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;

public interface AddressRepository extends JpaRepository<Address, UUID>, JpaSpecificationExecutor<Address> {
    /** Le propriétaire est chargé avec l'adresse : son nom peut figurer dans la réponse publique. */
    @EntityGraph(attributePaths = "owner")
    Optional<Address> findByPublicCodeAndStatusAndPubliclyVisibleTrue(
            String publicCode,
            AddressStatus status
    );

    boolean existsByPublicCode(String publicCode);

    Page<Address> findByOwnerId(UUID ownerId, Pageable pageable);

    Optional<Address> findByIdAndOwnerId(UUID id, UUID ownerId);

    Page<Address> findByStatus(AddressStatus status, Pageable pageable);

    long countByStatus(AddressStatus status);

    long countByPlaceType(PlaceType placeType);

    long countByOwnerId(UUID ownerId);

    /** Lignes (date de création, type) depuis une date : agrégées par mois côté Java (12 mois au plus). */
    @Query("select a.createdAt, a.placeType from Address a where a.createdAt >= :since")
    java.util.List<Object[]> creationsSince(@Param("since") java.time.Instant since);

    @Query("select count(a) from Address a where a.status = sn.samaadresse.address.domain.AddressStatus.VALIDATED"
            + " and a.publiclyVisible = true")
    long countPublic();

    /**
     * Annuaire public : la requête est construite par AddressSearchSpecifications (seuls les critères
     * fournis sont ajoutés). Le propriétaire est chargé pour pouvoir afficher son nom s'il l'a autorisé.
     */
    @Override
    @EntityGraph(attributePaths = "owner")
    Page<Address> findAll(Specification<Address> specification, Pageable pageable);
}
