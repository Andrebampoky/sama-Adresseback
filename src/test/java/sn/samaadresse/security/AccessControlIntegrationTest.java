package sn.samaadresse.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.support.ApiTestClient;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Matrice d'accès : anonyme, utilisateur et administrateur, vérifiée côté serveur. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessControlIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private ApiTestClient client;

    @BeforeEach
    void setUp() {
        client = new ApiTestClient(mockMvc, json, users);
    }

    @Test
    void anonymousCanOnlyUsePublicRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/addresses/ZIG-KAN-0000")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/addresses")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/me/addresses").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ApiTestClient.sampleAddress(true))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/addresses")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/addresses/" + UUID.randomUUID() + "/validate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidOrForgedTokensAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", "Bearer pas-un-jeton"))
                .andExpect(status().isUnauthorized());
        // Jeton signé avec « alg: none » : doit être refusé.
        String unsigned = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAiLCJpc3MiOiJzYW1hYWRyZXNzZS1hcGkifQ.";
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", "Bearer " + unsigned))
                .andExpect(status().isUnauthorized());
        // Un jeton valide altéré (signature cassée) est refusé.
        String token = client.registerUser();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularUserCannotReachAdministration() throws Exception {
        String token = client.registerUser();
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        String id = client.createAddress(token, ApiTestClient.sampleAddress(true)).get("id").asText();
        // Même sur sa propre adresse, un utilisateur ne peut pas s'auto-valider.
        mockMvc.perform(post("/api/v1/admin/addresses/" + id + "/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReachAdministration() throws Exception {
        String admin = client.registerAdmin();
        mockMvc.perform(get("/api/v1/admin/addresses").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void roleAndAccountStateAreReadFromDatabaseOnEachRequest() throws Exception {
        String email = ApiTestClient.uniqueEmail();
        String token = client.register(email);

        // Le même jeton obtient l'accès admin dès la promotion en base…
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        mockMvc.perform(get("/api/v1/admin/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // … et perd tout accès dès la désactivation du compte, sans attendre l'expiration.
        jdbc.update("UPDATE users SET enabled = FALSE WHERE email = ?", email);
        mockMvc.perform(get("/api/v1/me/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsConfiguredOriginWithAuthorizationHeaderOnly() throws Exception {
        mockMvc.perform(options("/api/v1/me/addresses")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mockMvc.perform(options("/api/v1/me/addresses")
                        .header("Origin", "https://site-inconnu.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
