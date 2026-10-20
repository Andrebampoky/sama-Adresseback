package sn.samaadresse.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.support.ApiTestClient;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sans fournisseur SMS ni clé OpenRouteService : erreurs 503 explicites, jamais de faux succès. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnconfiguredIntegrationsTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserAccountRepository users;

    @Test
    void smsAndRoutingAnswer503WhenNotConfigured() throws Exception {
        ApiTestClient client = new ApiTestClient(mockMvc, json, users);
        String owner = client.registerUser();
        String admin = client.registerAdmin();
        var address = client.publicAddress(owner, admin, Map.of("district", "Sans config " + UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/me/addresses/" + address.get("id").asText() + "/sms")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+221771234567\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("pas configuré")));
        mockMvc.perform(get("/api/v1/routes").param("fromLat", "12.5").param("fromLng", "-16.2")
                        .param("toCode", address.get("publicCode").asText()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("OpenRouteService")));
    }
}
