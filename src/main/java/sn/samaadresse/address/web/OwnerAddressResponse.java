package sn.samaadresse.address.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.domain.PlaceType;

@Schema(description = "Adresse vue par son propriétaire (ou un administrateur).")
public record OwnerAddressResponse(
        UUID id,
        String publicCode,
        String title,
        PlaceType placeType,
        String district,
        String city,
        String description,
        double latitude,
        double longitude,
        AddressStatus status,
        boolean publicationConsent,
        boolean publiclyAccessible,
        Instant createdAt,
        Instant updatedAt,
        Instant moderatedAt,
        boolean hasPhoto,
        boolean ownerNamePublic
) {
    public static OwnerAddressResponse from(Address address) {
        return new OwnerAddressResponse(
                address.getId(),
                address.getPublicCode(),
                address.getTitle(),
                address.getPlaceType(),
                address.getDistrict(),
                address.getCity(),
                address.getDescription(),
                address.getLatitude().doubleValue(),
                address.getLongitude().doubleValue(),
                address.getStatus(),
                address.isPubliclyVisible(),
                // Même règle que GET /api/v1/addresses/{code}.
                address.isPubliclyAccessible(),
                address.getCreatedAt(),
                address.getUpdatedAt(),
                address.getModeratedAt(),
                address.hasPhoto(),
                address.isOwnerNamePublic()
        );
    }
}
