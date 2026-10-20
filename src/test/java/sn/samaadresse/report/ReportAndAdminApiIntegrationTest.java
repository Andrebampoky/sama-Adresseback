package sn.samaadresse.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.support.ApiTestClient;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signalements (utilisateur → administrateur), indicateurs et gestion des comptes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportAndAdminApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserAccountRepository users;

    private ApiTestClient client;

    @BeforeEach
    void setUp() {
        client = new ApiTestClient(mockMvc, json, users);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private org.springframework.test.web.servlet.ResultActions report(String token, String code, Object body)
            throws Exception {
        var request = post("/api/v1/addresses/" + code + "/reports")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) {
            request.header("Authorization", bearer(token));
        }
        return mockMvc.perform(request);
    }

    @Test
    void reportLifecycleFromUserToAdminArchive() throws Exception {
        String owner = client.registerUser();
        String admin = client.registerAdmin();
        String reporter = client.registerUser();
        String second = client.registerUser();
        JsonNode address = client.publicAddress(owner, admin, Map.of("district", "Signal " + UUID.randomUUID()));
        String code = address.get("publicCode").asText();
        Map<String, Object> body = Map.of("reason", "WRONG_LOCATION", "comment", "Le point est dans la rue voisine.");

        report(null, code, body).andExpect(status().isUnauthorized());
        report(owner, code, body).andExpect(status().isBadRequest());
        report(reporter, code, Map.of("comment", "sans motif")).andExpect(status().isBadRequest());
        report(reporter, "ZIG-ZZZ-0000", body).andExpect(status().isNotFound());
        report(reporter, code, body).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
        report(reporter, code, body).andExpect(status().isConflict());
        report(second, code, Map.of("reason", "NOT_EXISTING")).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/reports").header("Authorization", bearer(reporter)))
                .andExpect(status().isForbidden());
        String body2 = mockMvc.perform(get("/api/v1/admin/reports").param("size", "50").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(body2).get("items");
        String first = null;
        String other = null;
        for (JsonNode item : items) {
            if (item.get("publicCode").asText().equals(code)) {
                if (first == null) {
                    first = item.get("id").asText();
                } else {
                    other = item.get("id").asText();
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(first).isNotNull();
        org.assertj.core.api.Assertions.assertThat(other).isNotNull();

        mockMvc.perform(post("/api/v1/admin/reports/" + first + "/dismiss").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
        mockMvc.perform(post("/api/v1/admin/reports/" + first + "/dismiss").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/addresses/" + code)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/reports/" + other + "/archive-address").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_TAKEN"))
                .andExpect(jsonPath("$.addressStatus").value("ARCHIVED"));
        mockMvc.perform(get("/api/v1/addresses/" + code)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/reports/" + UUID.randomUUID() + "/dismiss").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void statsAndUserManagementAreAdminOnlyAndExact() throws Exception {
        String admin = client.registerAdmin();
        String user = client.registerUser();
        client.createAddress(user, ApiTestClient.sampleAddress(true));

        mockMvc.perform(get("/api/v1/admin/stats").header("Authorization", bearer(user))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/stats").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAddresses").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byStatus.PENDING_VALIDATION").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.monthly", hasSize(12)))
                .andExpect(jsonPath("$.monthly[11].total").value(greaterThanOrEqualTo(1)));

        String page = mockMvc.perform(get("/api/v1/admin/users").param("size", "50").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String userId = null;
        String adminId = null;
        for (JsonNode u : json.readTree(page).get("items")) {
            if (u.get("role").asText().equals("USER") && u.get("addressCount").asLong() >= 1 && userId == null) {
                userId = u.get("id").asText();
            }
            if (u.get("role").asText().equals("ADMIN") && adminId == null) {
                adminId = u.get("id").asText();
            }
        }
        // Le compte désactivé ne peut plus appeler l'API, même avec un jeton encore valide.
        String meId = json.readTree(mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(user)))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/admin/users/" + meId + "/disable").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", bearer(user))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/users/" + meId + "/enable").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", bearer(user))).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(userId).isNotNull();
        // Un administrateur ne peut pas en désactiver un autre ni se désactiver lui-même.
        mockMvc.perform(post("/api/v1/admin/users/" + adminId + "/disable").header("Authorization", bearer(admin)))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isIn(400, 409));
    }
}
