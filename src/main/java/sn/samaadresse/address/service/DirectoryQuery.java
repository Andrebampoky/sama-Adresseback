package sn.samaadresse.address.service;

import sn.samaadresse.address.domain.PlaceType;

/** Critères de l'annuaire public ; chaque champ est facultatif. */
public record DirectoryQuery(
        String q,
        String code,
        String owner,
        String district,
        String city,
        PlaceType placeType
) {
}
