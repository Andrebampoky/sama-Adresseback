package sn.samaadresse.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Aide de test : crée de vrais comptes par l'API publique et renvoie leurs jetons. */
public final class ApiTestClient {
    public static final String PASSWORD = "motdepasse-test-42";

    private final MockMvc mockMvc;
    private final ObjectMapper json;
    private final UserAccountRepository users;

    public ApiTestClient(MockMvc mockMvc, ObjectMapper json, UserAccountRepository users) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
    }

    public static String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@exemple.test";
    }

    public String registerUser() throws Exception {
        return register(uniqueEmail());
    }

    public String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "Compte de test"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    /** Administrateur de test : inscription normale, puis promotion directe en base (aucun mot de passe codé). */
    public String registerAdmin() throws Exception {
        String email = uniqueEmail();
        String token = register(email);
        users.findByEmail(email).ifPresent(user -> {
            user.promoteToAdmin();
            users.save(user);
        });
        return token;
    }

    public JsonNode createAddress(String token, Map<String, Object> payload) throws Exception {
        String body = mockMvc.perform(post("/api/v1/me/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Lieu fictif de démonstration, situé dans la zone de service. */
    public static Map<String, Object> sampleAddress(boolean consent) {
        return new java.util.HashMap<>(Map.of(
                "title", "Lieu de démonstration",
                "placeType", "HOUSE",
                "district", "Quartier Exemple",
                "city", "Ville Exemple",
                "description", "Donnée fictive de test.",
                "latitude", 14.5,
                "longitude", -15.5,
                "publicationConsent", consent));
    }

    /** Valide une adresse avec un jeton administrateur (le contrôle d'accès réel est testé ailleurs). */
    public void validate(String adminToken, String addressId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/addresses/" + addressId + "/validate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    /** Adresse publique (créée, consentie, validée) dans un quartier au nom unique ; renvoie la réponse JSON. */
    public JsonNode publicAddress(String ownerToken, String adminToken, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = sampleAddress(true);
        payload.putAll(overrides);
        JsonNode created = createAddress(ownerToken, payload);
        validate(adminToken, created.get("id").asText());
        return created;
    }
}
