package sn.samaadresse.address.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.service.NewAddress;

/**
 * Corps de POST /api/v1/me/addresses. Le code public, le statut et le propriétaire ne sont
 * jamais acceptés depuis le client : ils sont attribués par le serveur.
 * Les coordonnées peuvent comporter plus de 6 décimales (GPS du navigateur) : le serveur les
 * arrondit à 6 décimales (environ 10 cm), précision du stockage.
 */
public record CreateAddressRequest(
        @NotBlank(message = "Le nom du lieu est obligatoire.")
        @Size(min = 2, max = 160, message = "Le nom du lieu doit contenir entre 2 et 160 caractères.")
        String title,
        @NotNull(message = "Le type de lieu est obligatoire.")
        PlaceType placeType,
        @NotBlank(message = "Le quartier est obligatoire.")
        @Size(min = 2, max = 100, message = "Le quartier doit contenir entre 2 et 100 caractères.")
        String district,
        @NotBlank(message = "La ville ou commune est obligatoire.")
        @Size(min = 2, max = 100, message = "La ville doit contenir entre 2 et 100 caractères.")
        String city,
        @Size(max = 1000, message = "La description ne doit pas dépasser 1000 caractères.")
        String description,
        @NotNull(message = "La latitude est obligatoire.")
        @DecimalMin(value = "-90", message = "La latitude doit être comprise entre -90 et 90.")
        @DecimalMax(value = "90", message = "La latitude doit être comprise entre -90 et 90.")
        BigDecimal latitude,
        @NotNull(message = "La longitude est obligatoire.")
        @DecimalMin(value = "-180", message = "La longitude doit être comprise entre -180 et 180.")
        @DecimalMax(value = "180", message = "La longitude doit être comprise entre -180 et 180.")
        BigDecimal longitude,
        @NotNull(message = "Indiquez explicitement si l'adresse peut être publiée.")
        Boolean publicationConsent,
        /** Facultatif (faux par défaut) : afficher le nom du propriétaire dans l'annuaire public. */
        Boolean ownerNamePublic
) {
    public NewAddress toNewAddress() {
        return new NewAddress(title, placeType, district, city, description, latitude, longitude,
                publicationConsent, Boolean.TRUE.equals(ownerNamePublic) && publicationConsent);
    }
}
