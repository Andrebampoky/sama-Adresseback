package sn.samaadresse.address.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AddressCodeGeneratorTest {
    private final AddressCodeGenerator generator = new AddressCodeGenerator();

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void buildsCodeFromPlaceNamesAndGridCellAcceptedByPublicLookup() {
        // 12.561235 → case 12561 → ligne 61 ; -16.273100 → case -16274 → colonne 26 (arrondi vers le bas).
        String code = generator.candidate("Ziguinchor", "Kandé", d("12.561235"), d("-16.273100"), 0);
        assertThat(code).isEqualTo("ZIG-KAN-6126");
        assertThat(AddressCode.normalize(code)).isEqualTo(code);
    }

    @Test
    void sameCellGivesSameNumberAndNeighbourCellsDiffer() {
        String a = AddressCodeGenerator.gridCell(d("14.693700"), d("-17.444100"));
        String sameCell = AddressCodeGenerator.gridCell(d("14.693999"), d("-17.444001"));
        String north = AddressCodeGenerator.gridCell(d("14.694000"), d("-17.444100"));
        String east = AddressCodeGenerator.gridCell(d("14.693700"), d("-17.444000"));
        assertThat(a).isEqualTo(sameCell);
        assertThat(north).isNotEqualTo(a);
        assertThat(east).isNotEqualTo(a);
        assertThat(a).matches("[0-9]{4}");
    }

    @Test
    void tieBreakerSuffixKeepsCodesDistinctAndWithinPublicFormat() {
        BigDecimal lat = d("14.5");
        BigDecimal lng = d("-15.5");
        String base = generator.candidate("Thiès", "Escale", lat, lng, 0);
        assertThat(generator.candidate("Thiès", "Escale", lat, lng, 1)).isEqualTo(base + "1");
        assertThat(generator.candidate("Thiès", "Escale", lat, lng, 9)).isEqualTo(base + "9");
        assertThat(generator.candidate("Thiès", "Escale", lat, lng, 10)).isEqualTo(base + "10");
        String last = generator.candidate("Thiès", "Escale", lat, lng, AddressCodeGenerator.MAX_ATTEMPTS - 1);
        assertThat(AddressCode.normalize(last)).isEqualTo(last);
        assertThrows(IllegalArgumentException.class,
                () -> generator.candidate("A", "B", lat, lng, AddressCodeGenerator.MAX_ATTEMPTS));
        assertThrows(IllegalArgumentException.class, () -> generator.candidate("A", "B", lat, lng, -1));
    }

    @Test
    void usesFallbackSegmentWhenNameHasTooFewUsableCharacters() {
        assertThat(AddressCodeGenerator.segment("É")).isEqualTo("SN");
        assertThat(AddressCodeGenerator.segment(null)).isEqualTo("SN");
        assertThat(AddressCodeGenerator.segment("Médina-Gounass")).isEqualTo("MED");
    }
}
