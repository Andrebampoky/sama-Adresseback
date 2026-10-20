package sn.samaadresse.address.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sn.samaadresse.address.domain.PlaceType;

/**
 * Corps de PUT /api/v1/me/addresses/{id}. La position, la ville et le quartier ne sont pas
 * modifiables : ils déterminent le code public.
 */
public record UpdateAddressRequest(
        @NotBlank(message = "Le nom du lieu est obligatoire.")
        @Size(min = 2, max = 160, message = "Le nom du lieu doit contenir entre 2 et 160 caractères.")
        String title,
        @NotNull(message = "Le type de lieu est obligatoire.")
        PlaceType placeType,
        @Size(max = 1000, message = "La description ne doit pas dépasser 1000 caractères.")
        String description,
        @NotNull(message = "Indiquez explicitement si l'adresse peut être publiée.")
        Boolean publicationConsent,
        Boolean ownerNamePublic
) {
}
