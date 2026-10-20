package sn.samaadresse.address.web;

import io.swagger.v3.oas.annotations.media.Schema;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.PlaceType;

@Schema(description = "Informations explicitement publiques d'une adresse validée.")
public record AddressPublicResponse(
        String publicCode,
        String title,
        PlaceType placeType,
        String district,
        String city,
        String description,
        double latitude,
        double longitude,
        String photoUrl,
        @Schema(description = "Nom affiché du propriétaire, seulement s'il a accepté de figurer dans l'annuaire")
        String ownerName
) {
    public static AddressPublicResponse from(Address address) {
        return new AddressPublicResponse(
                address.getPublicCode(),
                address.getTitle(),
                address.getPlaceType(),
                address.getDistrict(),
                address.getCity(),
                address.getDescription(),
                address.getLatitude().doubleValue(),
                address.getLongitude().doubleValue(),
                // Chemin relatif à l'API : la photo n'est servie que pour une adresse publique.
                address.hasPhoto() ? "/api/v1/addresses/" + address.getPublicCode() + "/photo" : null,
                address.getPublicOwnerName()
        );
    }
}
