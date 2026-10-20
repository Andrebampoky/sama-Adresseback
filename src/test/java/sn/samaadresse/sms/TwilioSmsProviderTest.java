package sn.samaadresse.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Fournisseur Twilio testé contre un faux serveur local : aucun appel réseau réel, aucun frais. */
class TwilioSmsProviderTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(201);
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> okBody = new AtomicReference<>("{\"sid\":\"SM123\",\"status\":\"queued\"}");
    private TwilioSmsProvider provider;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (status.get() < 300 ? okBody.get() : "{\"code\":21211,\"message\":\"invalid\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        provider = new TwilioSmsProvider(HttpClient.newHttpClient(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", "ACtest", "secret", "+15005550006");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsFormWithBasicAuthAndReturnsSid() {
        SmsResult result = provider.send("+221771234567", "Lien : https://x/a/ZIG-KAN-0001");
        assertThat(result.status()).isEqualTo(SmsResult.Status.SENT);
        assertThat(result.providerMessageId()).isEqualTo("SM123");
        assertThat(lastBody.get()).contains("To=%2B221771234567").contains("From=%2B15005550006").contains("Body=Lien");
        assertThat(lastAuth.get()).startsWith("Basic ");
    }

    @Test
    void mapsProviderErrorsWithoutLeakingCredentials() {
        status.set(400);
        SmsException rejected = assertThrows(SmsException.class, () -> provider.send("+221771234567", "x"));
        assertThat(rejected.getStatus().value()).isEqualTo(422);
        assertThat(rejected.getMessage()).contains("21211").doesNotContain("secret");
        status.set(429);
        assertThat(assertThrows(SmsException.class, () -> provider.send("+221771234567", "x")).getStatus().value())
                .isEqualTo(429);
        status.set(503);
        assertThat(assertThrows(SmsException.class, () -> provider.send("+221771234567", "x")).getStatus().value())
                .isEqualTo(502);
        server.stop(0);
        assertThat(assertThrows(SmsException.class, () -> provider.send("+221771234567", "x")).getStatus().value())
                .isEqualTo(502);
    }

    @Test
    void neverReportsSentWithoutProviderConfirmation() {
        okBody.set("{\"status\":\"queued\"}");
        assertThat(assertThrows(SmsException.class, () -> provider.send("+221771234567", "x")).getStatus().value())
                .isEqualTo(502);
        okBody.set("{\"sid\":\"SM9\",\"status\":\"failed\"}");
        assertThat(assertThrows(SmsException.class, () -> provider.send("+221771234567", "x")).getStatus().value())
                .isEqualTo(502);
    }

    @Test
    void normalizesAndMasksPhoneNumbers() {
        assertThat(PhoneNumbers.normalize("77 123 45 67")).contains("+221771234567");
        assertThat(PhoneNumbers.normalize("00221 77-123-45-67")).contains("+221771234567");
        assertThat(PhoneNumbers.normalize("+33 6 12 34 56 78")).contains("+33612345678");
        assertThat(PhoneNumbers.normalize("12345")).isEmpty();
        assertThat(PhoneNumbers.normalize(null)).isEmpty();
        // Régression : un numéro +221 doit compter exactement 9 chiffres et être un mobile (7x).
        assertThat(PhoneNumbers.normalize("+221 77 123 45 6")).isEmpty();
        assertThat(PhoneNumbers.normalize("+221 77 123 45 678")).isEmpty();
        assertThat(PhoneNumbers.normalize("+221 33 823 45 67")).isEmpty();
        assertThat(PhoneNumbers.normalize("221 77 123 45 67")).contains("+221771234567");
        assertThat(PhoneNumbers.normalize("(+221) 70.123.45.67")).contains("+221701234567");
        assertThat(PhoneNumbers.mask("+221771234567")).isEqualTo("+22177•••••67");
    }
}
