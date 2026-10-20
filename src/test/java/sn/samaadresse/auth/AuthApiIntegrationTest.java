package sn.samaadresse.auth;

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
import org.springframework.test.web.servlet.ResultActions;
import sn.samaadresse.support.ApiTestClient;
import sn.samaadresse.user.domain.Role;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest {
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

    private ResultActions postJson(String path, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void registerHashesPasswordAndAlwaysCreatesUserRole() throws Exception {
        String email = ApiTestClient.uniqueEmail();
        postJson("/api/v1/auth/register", Map.of(
                "email", email.toUpperCase(),
                "password", ApiTestClient.PASSWORD,
                "displayName", "Awa Test",
                // Un client malveillant ne doit pas pouvoir choisir son rôle.
                "role", "ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(content().string(not(containsString("password"))));

        UserAccount stored = users.findByEmail(email).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        assertThat(stored.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(ApiTestClient.PASSWORD);
    }

    @Test
    void rejectsDuplicateEmailCaseInsensitively() throws Exception {
        String email = ApiTestClient.uniqueEmail();
        client.register(email);
        postJson("/api/v1/auth/register", Map.of(
                "email", email.toUpperCase(), "password", ApiTestClient.PASSWORD, "displayName", "Autre"))
                .andExpect(status().isConflict());
    }

    @Test
    void validatesRegistrationFields() throws Exception {
        postJson("/api/v1/auth/register", Map.of("email", "pas-un-email", "password", "court", "displayName", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.displayName").exists());

        postJson("/api/v1/auth/register", Map.of(
                "email", ApiTestClient.uniqueEmail(), "password", "uniquementdeslettres", "displayName", "Test"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsTokenUsableOnMeEndpoint() throws Exception {
        String email = ApiTestClient.uniqueEmail();
        client.register(email);

        String body = postJson("/api/v1/auth/login", Map.of("email", email, "password", ApiTestClient.PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetSameGenericAnswer() throws Exception {
        String email = ApiTestClient.uniqueEmail();
        client.register(email);

        postJson("/api/v1/auth/login", Map.of("email", email, "password", "mauvais-mot-de-passe-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou mot de passe incorrect."));
        postJson("/api/v1/auth/login", Map.of("email", ApiTestClient.uniqueEmail(), "password", "x1234567890"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou mot de passe incorrect."));
    }

    @Test
    void blocksLoginAfterRepeatedFailures() throws Exception {
        String email = ApiTestClient.uniqueEmail();
        client.register(email);
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/auth/login", Map.of("email", email, "password", "mauvais-mot-de-passe-1"))
                    .andExpect(status().isUnauthorized());
        }
        // Même le bon mot de passe est refusé pendant le blocage.
        postJson("/api/v1/auth/login", Map.of("email", email, "password", ApiTestClient.PASSWORD))
                .andExpect(status().isTooManyRequests());
    }
}
