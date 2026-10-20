package sn.samaadresse.address.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.samaadresse.user.domain.UserAccount;

@Entity
@Table(name = "addresses")
public class Address {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "public_code", nullable = false, unique = true, length = 32)
    private String publicCode;

    @Column(nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_type", nullable = false, length = 24)
    private PlaceType placeType;

    @Column(nullable = false, length = 100)
    private String district;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal longitude;

    /** Clé de la photo de façade dans le stockage (colonne historique photo_url), null sans photo. */
    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AddressStatus status;

    @Column(name = "publicly_visible", nullable = false)
    private boolean publiclyVisible;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    /** Lecture seule : sert à l'annuaire (recherche par nom du propriétaire), jamais à l'écriture. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", insertable = false, updatable = false)
    private UserAccount owner;

    /** Le propriétaire accepte que son nom affiché apparaisse dans l'annuaire public (faux par défaut). */
    @Column(name = "owner_name_public", nullable = false)
    private boolean ownerNamePublic;

    @Column(name = "publication_consent_at")
    private Instant publicationConsentAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "moderated_by")
    private UUID moderatedBy;

    protected Address() {
    }

    /**
     * Adresse créée par un propriétaire via l'API. Elle démarre toujours en attente de validation ;
     * la visibilité publique ne vaut que si la personne y a explicitement consenti.
     */
    public static Address createdByOwner(
            UUID ownerId,
            String publicCode,
            String title,
            PlaceType placeType,
            String district,
            String city,
            String description,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean publicationConsent,
            boolean ownerNamePublic
    ) {
        Address address = new Address(publicCode, title, placeType, district, city, description,
                latitude, longitude, null, AddressStatus.PENDING_VALIDATION, publicationConsent);
        address.ownerId = ownerId;
        address.ownerNamePublic = ownerNamePublic;
        address.publicationConsentAt = publicationConsent ? address.createdAt : null;
        address.updatedAt = address.createdAt;
        return address;
    }

    /** Décision de modération : seule une adresse en attente peut être validée ou rejetée. */
    public void moderate(AddressStatus decision, UUID moderatorId) {
        if (status != AddressStatus.PENDING_VALIDATION) {
            throw new IllegalStateException("Seule une adresse en attente de validation peut être modérée.");
        }
        if (decision != AddressStatus.VALIDATED && decision != AddressStatus.REJECTED) {
            throw new IllegalArgumentException("Décision de modération non prise en charge : " + decision);
        }
        Instant now = Instant.now();
        this.status = decision;
        this.moderatedAt = now;
        this.moderatedBy = moderatorId;
        this.updatedAt = now;
    }

    /**
     * Modification du contenu par le propriétaire. La position n'est pas modifiable (le code en dépend :
     * il faut créer une nouvelle adresse). Une adresse validée ou rejetée repasse en vérification, car
     * l'équipe n'a pas contrôlé le nouveau contenu ; une adresse archivée n'est plus modifiable.
     * Renvoie vrai si l'adresse doit être revérifiée.
     */
    public boolean editContent(String newTitle, PlaceType newPlaceType, String newDescription) {
        requireNotArchived();
        boolean changed = !title.equals(newTitle) || placeType != newPlaceType
                || !java.util.Objects.equals(description, newDescription);
        if (!changed) {
            return false;
        }
        this.title = newTitle;
        this.placeType = newPlaceType;
        this.description = newDescription;
        boolean backToReview = status != AddressStatus.PENDING_VALIDATION;
        if (backToReview) {
            this.status = AddressStatus.PENDING_VALIDATION;
            this.moderatedAt = null;
            this.moderatedBy = null;
        }
        this.updatedAt = Instant.now();
        return backToReview;
    }

    /**
     * Correction de la position tant que l'adresse est en attente de vérification (rien n'a encore été
     * publié). Le code dépend de la case de la grille : il est remplacé par le nouveau code fourni.
     */
    public void relocate(String newPublicCode, BigDecimal newLatitude, BigDecimal newLongitude) {
        if (status != AddressStatus.PENDING_VALIDATION) {
            throw new IllegalStateException(
                    "La position ne peut être corrigée que pendant la vérification ; créez une nouvelle adresse.");
        }
        this.publicCode = newPublicCode;
        this.latitude = newLatitude;
        this.longitude = newLongitude;
        this.updatedAt = Instant.now();
    }

    /** Le consentement peut être donné ou retiré à tout moment ; le retrait masque aussitôt la fiche publique. */
    public void changeVisibility(boolean publicationConsent, boolean ownerNamePublic) {
        requireNotArchived();
        if (publicationConsent && !this.publiclyVisible) {
            this.publicationConsentAt = Instant.now();
        }
        if (!publicationConsent) {
            this.publicationConsentAt = null;
        }
        this.publiclyVisible = publicationConsent;
        this.ownerNamePublic = ownerNamePublic;
        this.updatedAt = Instant.now();
    }

    /** Archivage définitif (par le propriétaire ou un administrateur après signalement). */
    public void archive(UUID byUserId) {
        requireNotArchived();
        Instant now = Instant.now();
        this.status = AddressStatus.ARCHIVED;
        this.publiclyVisible = false;
        this.moderatedAt = now;
        this.moderatedBy = byUserId;
        this.updatedAt = now;
    }

    private void requireNotArchived() {
        if (status == AddressStatus.ARCHIVED) {
            throw new IllegalStateException("Cette adresse est archivée et ne peut plus être modifiée.");
        }
    }

    public boolean isPubliclyAccessible() {
        return publiclyVisible && status == AddressStatus.VALIDATED;
    }

    /**
     * La photo fait partie de ce que l'équipe vérifie : elle ne peut être ajoutée ou remplacée
     * que tant que l'adresse est en attente de validation. La retirer reste toujours possible.
     * Renvoie la clé de l'ancienne photo, à supprimer du stockage.
     */
    public String attachPhoto(String photoKey) {
        if (status != AddressStatus.PENDING_VALIDATION) {
            throw new IllegalStateException(
                    "La photo ne peut être ajoutée ou remplacée que pendant la vérification de l'adresse.");
        }
        String previous = this.photoUrl;
        this.photoUrl = photoKey;
        this.updatedAt = Instant.now();
        return previous;
    }

    public String removePhoto() {
        String previous = this.photoUrl;
        this.photoUrl = null;
        this.updatedAt = Instant.now();
        return previous;
    }

    public boolean hasPhoto() {
        return photoUrl != null;
    }

    public Address(
            String publicCode,
            String title,
            PlaceType placeType,
            String district,
            String city,
            String description,
            BigDecimal latitude,
            BigDecimal longitude,
            String photoUrl,
            AddressStatus status,
            boolean publiclyVisible
    ) {
        this.publicCode = publicCode;
        this.title = title;
        this.placeType = placeType;
        this.district = district;
        this.city = city;
        this.description = description;
        this.latitude = latitude;
        this.longitude = longitude;
        this.photoUrl = photoUrl;
        this.status = status;
        this.publiclyVisible = publiclyVisible;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getPublicCode() {
        return publicCode;
    }

    public String getTitle() {
        return title;
    }

    public PlaceType getPlaceType() {
        return placeType;
    }

    public String getDistrict() {
        return district;
    }

    public String getCity() {
        return city;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public AddressStatus getStatus() {
        return status;
    }

    public boolean isPubliclyVisible() {
        return publiclyVisible;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UserAccount getOwner() {
        return owner;
    }

    public boolean isOwnerNamePublic() {
        return ownerNamePublic;
    }

    /** Nom du propriétaire publiable : seulement s'il l'a explicitement autorisé. */
    public String getPublicOwnerName() {
        return ownerNamePublic && owner != null ? owner.getDisplayName() : null;
    }

    public Instant getPublicationConsentAt() {
        return publicationConsentAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getModeratedAt() {
        return moderatedAt;
    }
}
