package sn.samaadresse.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.sms.SimulatedSmsProvider;
import sn.samaadresse.sms.SmsGateway;
import sn.samaadresse.support.ApiTestClient;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Intégrations externes sans réseau : SMS en mode simulation et faux serveur OpenRouteService
 * (serveur HTTP local du JDK) pour vérifier la requête envoyée et chaque cas d'erreur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SmsAndRouteIntegrationTest {
    private static final AtomicInteger ORS_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> ORS_LAST_REQUEST = new AtomicReference<>();
    private static final HttpServer ORS = startFakeOrs();

    private static HttpServer startFakeOrs() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                ORS_LAST_REQUEST.set(exchange.getRequestURI() + " key=" + exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = ("{\"features\":[{\"geometry\":{\"coordinates\":[[-16.27,12.56],[-16.28,12.57]]},"
                        + "\"properties\":{\"summary\":{\"distance\":1234.5,\"duration\":900}}}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(ORS_STATUS.get(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.sms.provider", () -> "simulation");
        registry.add("app.routing.ors.api-key", () -> "cle-de-test");
        registry.add("app.routing.ors.base-url", () -> "http://127.0.0.1:" + ORS.getAddress().getPort());
        registry.add("app.public-base-url", () -> "https://sama.exemple.test/");
    }

    @AfterAll
    static void stop() {
        ORS.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private SmsGateway sms;

    private ApiTestClient client;

    @BeforeEach
    void setUp() {
        client = new ApiTestClient(mockMvc, json, users);
        ORS_STATUS.set(200);
    }

    private org.springframework.test.web.servlet.ResultActions sendSms(String token, String id, String phone) throws Exception {
        return mockMvc.perform(post("/api/v1/me/addresses/" + id + "/sms").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phone", phone))));
    }

    @Test
    void smsIsOnlyForPublicAddressesAndSimulationIsNeverReportedAsSent() throws Exception {
        String owner = client.registerUser();
        String admin = client.registerAdmin();
        JsonNode pending = client.createAddress(owner, ApiTestClient.sampleAddress(true));
        sendSms(owner, pending.get("id").asText(), "+221771234567").andExpect(status().isConflict());

        JsonNode published = client.publicAddress(owner, admin, Map.of("district", "Sms " + UUID.randomUUID()));
        String id = published.get("id").asText();
        sendSms(owner, id, "123").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists());
        sendSms(client.registerUser(), id, "771234567").andExpect(status().isNotFound());
        sendSms(owner, id, "77 123 45 67")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIMULATED"))
                .andExpect(jsonPath("$.to").value("+22177•••••67"))
                .andExpect(jsonPath("$.link").value("https://sama.exemple.test/a/" + published.get("publicCode").asText()));
        String[] last = ((SimulatedSmsProvider) sms.provider().orElseThrow()).outbox().get(
                ((SimulatedSmsProvider) sms.provider().orElseThrow()).outbox().size() - 1);
        assertThat(last[0]).isEqualTo("+221771234567");
        assertThat(last[1]).contains("/a/" + published.get("publicCode").asText());
        // Limite : 5 SMS par heure et par compte.
        for (int i = 0; i < 4; i++) {
            sendSms(owner, id, "+221771234567").andExpect(status().isOk());
        }
        sendSms(owner, id, "+221771234567").andExpect(status().isTooManyRequests());
    }

    @Test
    void routeIsComputedByOpenRouteServiceWithServerSideKey() throws Exception {
        String owner = client.registerUser();
        String admin = client.registerAdmin();
        String code = client.publicAddress(owner, admin, Map.of("district", "Route " + UUID.randomUUID()))
                .get("publicCode").asText();

        mockMvc.perform(get("/api/v1/routes").param("fromLat", "12.56").param("fromLng", "-16.27").param("toCode", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceMeters").value(1234.5))
                .andExpect(jsonPath("$.coordinates[0][0]").value(12.56))
                .andExpect(jsonPath("$.coordinates[0][1]").value(-16.27));
        assertThat(ORS_LAST_REQUEST.get()).contains("/v2/directions/foot-walking?start=-16.270000,12.560000")
                .contains("end=-15.500000,14.500000").endsWith("key=cle-de-test");

        ORS_STATUS.set(429);
        mockMvc.perform(get("/api/v1/routes").param("fromLat", "12.56").param("fromLng", "-16.27").param("toCode", code))
                .andExpect(status().isTooManyRequests());
        ORS_STATUS.set(404);
        mockMvc.perform(get("/api/v1/routes").param("fromLat", "12.56").param("fromLng", "-16.27").param("toCode", code))
                .andExpect(status().isUnprocessableEntity());
        ORS_STATUS.set(500);
        mockMvc.perform(get("/api/v1/routes").param("fromLat", "12.56").param("fromLng", "-16.27").param("toCode", code))
                .andExpect(status().isBadGateway());
        mockMvc.perform(get("/api/v1/routes").param("fromLat", "120").param("fromLng", "-16.27").param("toCode", code))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/routes").param("fromLat", "12.5").param("fromLng", "-16.2").param("toCode", code)
                .param("profile", "fusee")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/routes").param("fromLat", "12.5").param("fromLng", "-16.2").param("toCode", "ZIG-ZZZ-0000"))
                .andExpect(status().isNotFound());
    }
}
