package sn.samaadresse.address.service;

import java.math.BigDecimal;
import sn.samaadresse.address.domain.PlaceType;

/** Données saisies par le propriétaire, déjà validées syntaxiquement par le contrôleur. */
public record NewAddress(
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
}
