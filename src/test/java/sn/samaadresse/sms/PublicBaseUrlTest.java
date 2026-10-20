package sn.samaadresse.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** L'URL publique utilisée dans les SMS est vérifiée au démarrage (pas de lien cassé silencieux). */
class PublicBaseUrlTest {
    @Test
    void acceptsAbsoluteHttpUrlsAndRemovesTrailingSlashes() {
        assertThat(ShareSmsController.checkedBaseUrl("https://samaadresse.sn//")).isEqualTo("https://samaadresse.sn");
        assertThat(ShareSmsController.checkedBaseUrl(" http://192.168.1.20:5173/ ")).isEqualTo("http://192.168.1.20:5173");
    }

    @Test
    void rejectsRelativeOrNonHttpUrls() {
        assertThrows(IllegalStateException.class, () -> ShareSmsController.checkedBaseUrl("samaadresse.sn"));
        assertThrows(IllegalStateException.class, () -> ShareSmsController.checkedBaseUrl("/a"));
        assertThrows(IllegalStateException.class, () -> ShareSmsController.checkedBaseUrl("ftp://samaadresse.sn"));
        assertThrows(IllegalStateException.class, () -> ShareSmsController.checkedBaseUrl(""));
    }
}
