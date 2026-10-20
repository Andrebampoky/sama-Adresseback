package sn.samaadresse.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.support.ApiTestClient;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddressPhotoApiIntegrationTest {
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

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, String id, byte[] bytes, String type)
            throws Exception {
        return mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/me/addresses/" + id + "/photo")
                .file(new MockMultipartFile("photo", "facade", type, bytes))
                .header("Authorization", "Bearer " + token));
    }

    @Test
    void ownerAddsPhotoThatIsReencodedAndResizedThenPublishedOnlyWhenPublic() throws Exception {
        String owner = client.registerUser();
        String admin = client.registerAdmin();
        JsonNode created = client.createAddress(owner, ApiTestClient.sampleAddress(true));
        String id = created.get("id").asText();
        String code = created.get("publicCode").asText();
        assertThat(created.get("hasPhoto").asBoolean()).isFalse();

        upload(owner, id, png(3200, 1600), "image/png")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPhoto").value(true));

        byte[] stored = mockMvc.perform(get("/api/v1/me/addresses/" + id + "/photo").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(image.getWidth()).isEqualTo(1600);
        assertThat(image.getHeight()).isEqualTo(800);

        // L'administrateur voit la photo pour modérer ; le public pas encore (adresse en attente).
        mockMvc.perform(get("/api/v1/admin/addresses/" + id + "/photo").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/addresses/" + code + "/photo")).andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/admin/addresses/" + id + "/validate").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/addresses/" + code))
                .andExpect(jsonPath("$.photoUrl").value("/api/v1/addresses/" + code + "/photo"));
        mockMvc.perform(get("/api/v1/addresses/" + code + "/photo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));

        // Après validation, la photo ne peut plus être remplacée sans nouvelle vérification…
        upload(owner, id, png(10, 10), "image/png").andExpect(status().isConflict());
        // …mais le propriétaire peut toujours la retirer.
        mockMvc.perform(delete("/api/v1/me/addresses/" + id + "/photo").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPhoto").value(false));
        mockMvc.perform(get("/api/v1/addresses/" + code)).andExpect(jsonPath("$.photoUrl").doesNotExist());
        mockMvc.perform(get("/api/v1/addresses/" + code + "/photo")).andExpect(status().isNotFound());
    }

    @Test
    void privateAddressPhotoIsNeverPublic() throws Exception {
        String owner = client.registerUser();
        String admin = client.registerAdmin();
        JsonNode created = client.createAddress(owner, ApiTestClient.sampleAddress(false));
        String id = created.get("id").asText();
        upload(owner, id, png(20, 20), "image/png").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/addresses/" + id + "/validate").header("Authorization", "Bearer " + admin));

        mockMvc.perform(get("/api/v1/addresses/" + created.get("publicCode").asText() + "/photo"))
                .andExpect(status().isNotFound());
        // Régression : un client qui demande du JSON reçoit bien 404 problem+json, et non 406.
        mockMvc.perform(get("/api/v1/addresses/" + created.get("publicCode").asText() + "/photo")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void rejectsFakeImagesAndOtherAccounts() throws Exception {
        String owner = client.registerUser();
        String other = client.registerUser();
        String id = client.createAddress(owner, ApiTestClient.sampleAddress(true)).get("id").asText();

        // Le type déclaré ne suffit pas : la signature binaire est vérifiée.
        upload(owner, id, "<svg onload=alert(1)>".getBytes(), "image/png")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.photo").exists());
        // Signature JPEG mais contenu illisible.
        upload(owner, id, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3}, "image/jpeg")
                .andExpect(status().isBadRequest());
        // L'adresse d'un autre compte reste introuvable (404), et l'anonyme est refusé (401).
        upload(other, id, png(10, 10), "image/png").andExpect(status().isNotFound());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/me/addresses/" + id + "/photo")
                        .file(new MockMultipartFile("photo", "f", "image/png", png(10, 10))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/addresses/" + id + "/photo").header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
    }

    @Test
    void stripsMetadataByReencoding() throws Exception {
        String owner = client.registerUser();
        String id = client.createAddress(owner, ApiTestClient.sampleAddress(true)).get("id").asText();
        // PNG avec un bloc texte (métadonnée) ajouté : il ne doit pas survivre au réencodage.
        byte[] withText = PngText.withTextChunk(png(40, 30), "GPS", "14.69,-17.44");
        upload(owner, id, withText, "image/png").andExpect(status().isOk());
        byte[] stored = mockMvc.perform(get("/api/v1/me/addresses/" + id + "/photo").header("Authorization", "Bearer " + owner))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("14.69,-17.44");
    }
}
