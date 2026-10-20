package sn.samaadresse.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.support.ApiTestClient;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OwnerAddressApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private AddressRepository addresses;

    private ApiTestClient client;

    @BeforeEach
    void setUp() {
        client = new ApiTestClient(mockMvc, json, users);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void createdAddressStartsPendingWithServerGeneratedCode() throws Exception {
        String token = client.registerUser();
        Map<String, Object> payload = ApiTestClient.sampleAddress(true);
        payload.put("city", "Ziguinchor");
        payload.put("district", "Kandé");
        payload.put("latitude", 12.5612345);
        // Champs imposés par le serveur : toute valeur fournie par le client est ignorée.
        payload.put("publicCode", "ABC-DEF-1234");
        payload.put("status", "VALIDATED");
        payload.put("ownerId", "00000000-0000-0000-0000-000000000000");

        JsonNode created = client.createAddress(token, payload);

        assertThat(created.get("publicCode").asText()).matches("ZIG-KAN-[0-9]{4}");
        assertThat(created.get("status").asText()).isEqualTo("PENDING_VALIDATION");
        assertThat(created.get("publicationConsent").asBoolean()).isTrue();
        assertThat(created.get("publiclyAccessible").asBoolean()).isFalse();
        assertThat(created.get("latitude").asDouble()).isEqualTo(12.561235);
        assertThat(addresses.findById(java.util.UUID.fromString(created.get("id").asText())).orElseThrow()
                .getOwnerId()).isNotEqualTo(java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"));

        // Pas encore publique : en attente de modération.
        mockMvc.perform(get("/api/v1/addresses/" + created.get("publicCode").asText()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidFieldsAndCoordinates() throws Exception {
        String token = client.registerUser();
        Map<String, Object> payload = ApiTestClient.sampleAddress(true);
        payload.remove("publicationConsent");
        payload.put("title", " ");
        payload.put("latitude", 95);
        mockMvc.perform(post("/api/v1/me/addresses").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.latitude").exists())
                .andExpect(jsonPath("$.errors.publicationConsent").exists());

        // Coordonnées valides sur Terre mais hors de la zone de service (Paris).
        Map<String, Object> outside = ApiTestClient.sampleAddress(true);
        outside.put("latitude", 48.85);
        outside.put("longitude", 2.35);
        mockMvc.perform(post("/api/v1/me/addresses").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(outside)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.latitude").exists())
                .andExpect(jsonPath("$.errors.longitude").exists());

        Map<String, Object> badType = ApiTestClient.sampleAddress(true);
        badType.put("placeType", "CASTLE");
        mockMvc.perform(post("/api/v1/me/addresses").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(badType)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownersOnlySeeTheirOwnAddresses() throws Exception {
        String alice = client.registerUser();
        String bob = client.registerUser();
        String aliceAddress = client.createAddress(alice, ApiTestClient.sampleAddress(false)).get("id").asText();
        client.createAddress(alice, ApiTestClient.sampleAddress(true));

        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/me/addresses/" + aliceAddress).header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/addresses/" + aliceAddress).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addressBecomesPublicOnlyAfterValidationAndWithConsent() throws Exception {
        String owner = client.registerUser();
        String admin = client.registerAdmin();
        JsonNode withConsent = client.createAddress(owner, ApiTestClient.sampleAddress(true));
        JsonNode withoutConsent = client.createAddress(owner, ApiTestClient.sampleAddress(false));

        for (JsonNode address : new JsonNode[] {withConsent, withoutConsent}) {
            mockMvc.perform(post("/api/v1/admin/addresses/" + address.get("id").asText() + "/validate")
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("VALIDATED"));
        }

        mockMvc.perform(get("/api/v1/addresses/" + withConsent.get("publicCode").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Lieu de démonstration"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());
        mockMvc.perform(get("/api/v1/addresses/" + withoutConsent.get("publicCode").asText()))
                .andExpect(status().isNotFound());

        // Une décision déjà prise ne peut pas être rejouée.
        mockMvc.perform(post("/api/v1/admin/addresses/" + withConsent.get("id").asText() + "/reject")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }
}
