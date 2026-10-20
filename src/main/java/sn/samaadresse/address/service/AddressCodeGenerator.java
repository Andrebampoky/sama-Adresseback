package sn.samaadresse.address.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Génère les codes publics VIL-QUA-NNNN[S], par exemple ZIG-KAN-6873.
 *
 * - VIL / QUA : trois premières lettres/chiffres de la ville puis du quartier saisis, sans accents
 *   (« SN » si moins de deux caractères utilisables) ;
 * - NNNN : numéro de case d'une grille géographique propre à SamaAdresse. Le territoire est découpé
 *   en cases de 0,001° × 0,001° (environ 110 m × 108 m au Sénégal), regroupées en blocs de
 *   100 × 100 cases (environ 11 km de côté) : NNNN = ligne × 100 + colonne de la case dans son bloc.
 *   Dans un même quartier, le numéro indique donc l'emplacement à une centaine de mètres près ;
 * - S : suffixe de départage (1 à 2 chiffres) seulement si la case est déjà occupée par une autre
 *   adresse de même préfixe : ZIG-KAN-6873, puis ZIG-KAN-68731 … ZIG-KAN-68739, puis ZIG-KAN-687310…
 *
 * Le calcul est déterministe : le navigateur peut afficher hors connexion le code provisionnel
 * (essai 0) ; le serveur reste seul juge du code définitif, garanti unique par la contrainte UNIQUE
 * de la base. Le code ne dépend jamais de l'identité du propriétaire.
 */
@Component
public class AddressCodeGenerator {
    static final String FALLBACK_SEGMENT = "SN";
    /** Taille d'une case de la grille, en degrés. */
    static final BigDecimal CELL_DEGREES = new BigDecimal("0.001");
    static final int CELLS_PER_BLOCK_SIDE = 100;
    /** Essai 0 : case seule ; 1-9 : un chiffre de départage ; 10-99 : deux chiffres. */
    public static final int MAX_ATTEMPTS = 100;

    public String candidate(String city, String district, BigDecimal latitude, BigDecimal longitude, int attempt) {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Essai hors limites : " + attempt);
        }
        String base = segment(city) + "-" + segment(district) + "-" + gridCell(latitude, longitude);
        return attempt == 0 ? base : base + attempt;
    }

    /** Numéro de case sur 4 chiffres (0000 à 9999) dans le bloc de 100 × 100 cases. */
    static String gridCell(BigDecimal latitude, BigDecimal longitude) {
        int row = Math.floorMod(cellIndex(latitude), CELLS_PER_BLOCK_SIDE);
        int column = Math.floorMod(cellIndex(longitude), CELLS_PER_BLOCK_SIDE);
        return String.format(Locale.ROOT, "%02d%02d", row, column);
    }

    private static long cellIndex(BigDecimal degrees) {
        // Arrondi vers le bas (et non vers zéro) : les longitudes négatives du Sénégal restent cohérentes.
        return degrees.divide(CELL_DEGREES, 0, RoundingMode.FLOOR).longValueExact();
    }

    static String segment(String value) {
        String ascii = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
        if (ascii.length() < 2) {
            return FALLBACK_SEGMENT;
        }
        return ascii.substring(0, Math.min(3, ascii.length()));
    }
}
