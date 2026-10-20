package sn.samaadresse.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Le document OpenAPI 3.0 décrit bien les routes réellement exposées. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void documentsTheRealEndpointsInOpenApi30() throws Exception {
        JsonNode doc = json.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(doc.get("openapi").asText()).startsWith("3.0");
        JsonNode paths = doc.get("paths");
        assertThat(paths.has("/api/v1/addresses/{code}")).isTrue();
        assertThat(paths.get("/api/v1/addresses").has("get")).isTrue();
        assertThat(paths.get("/api/v1/me/addresses/{id}/photo").has("put")).isTrue();
        assertThat(paths.get("/api/v1/admin/reports").has("get")).isTrue();
        assertThat(paths.get("/api/v1/routes").has("get")).isTrue();
        assertThat(paths.get("/api/v1/me/addresses/{id}/location").has("put")).isTrue();
        assertThat(paths.get("/api/v1/me/addresses/{id}/sms").has("post")).isTrue();
        // Les photos sont décrites comme des images JPEG, les erreurs en problem+json.
        assertThat(doc.at("/paths/~1api~1v1~1addresses~1{code}~1photo/get/responses/200/content").has("image/jpeg")).isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1addresses~1{code}~1photo/get/responses/404/content")
                .has("application/problem+json")).isTrue();
        assertThat(doc.at("/components/securitySchemes/bearer/scheme").asText()).isEqualTo("bearer");
        // La réponse publique ne contient aucune donnée de compte (e-mail, identifiant du propriétaire).
        JsonNode publicSchema = doc.at("/components/schemas/AddressPublicResponse/properties");
        assertThat(publicSchema.has("email")).isFalse();
        assertThat(publicSchema.has("ownerId")).isFalse();
        assertThat(publicSchema.has("latitude")).isTrue();
        assertThat(publicSchema.has("photoUrl")).isTrue();
    }
}
