package sn.samaadresse.directory;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Annuaire public (code, propriétaire, quartier, type) et gestion des adresses par le propriétaire. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DirectoryAndOwnerApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserAccountRepository users;

    private ApiTestClient client;
    private String owner;
    private String admin;
    /** Quartier au nom unique : les autres tests partagent la même base H2. */
    private String district;

    @BeforeEach
    void setUp() throws Exception {
        client = new ApiTestClient(mockMvc, json, users);
        owner = client.registerUser();
        admin = client.registerAdmin();
        district = "Annuaire " + UUID.randomUUID().toString().substring(0, 8);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void directoryReturnsOnlyPublicAddressesAndSearchesEachCriterion() throws Exception {
        JsonNode shop = client.publicAddress(owner, admin, Map.of("district", district, "placeType", "BUSINESS",
                "title", "Boutique Sama", "ownerNamePublic", true));
        client.publicAddress(owner, admin, Map.of("district", district, "placeType", "HOUSE", "title", "Maison discrète"));
        // Non validée et privée : jamais dans l'annuaire.
        client.createAddress(owner, Map.of("title", "En attente", "placeType", "HOUSE", "district", district,
                "city", "Ville Exemple", "latitude", 14.5, "longitude", -15.5, "publicationConsent", true));
        JsonNode privateOne = client.createAddress(owner, Map.of("title", "Privée", "placeType", "HOUSE",
                "district", district, "city", "Ville Exemple", "latitude", 14.5, "longitude", -15.5,
                "publicationConsent", false));
        client.validate(admin, privateOne.get("id").asText());

        mockMvc.perform(get("/api/v1/addresses").param("district", district.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].district", everyItem(is(district))));
        mockMvc.perform(get("/api/v1/addresses").param("district", district).param("type", "BUSINESS"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Boutique Sama"))
                .andExpect(jsonPath("$.items[0].ownerName").value("Compte de test"));
        // Recherche par nom du propriétaire : seulement là où il a accepté de figurer.
        mockMvc.perform(get("/api/v1/addresses").param("owner", "compte de TEST").param("district", district))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].publicCode").value(shop.get("publicCode").asText()));
        mockMvc.perform(get("/api/v1/addresses").param("district", district).param("type", "HOUSE"))
                .andExpect(jsonPath("$.items[0].ownerName").doesNotExist());
        // Recherche par début de code (insensible à la casse) et texte libre.
        String prefix = shop.get("publicCode").asText().substring(0, 7);
        mockMvc.perform(get("/api/v1/addresses").param("code", prefix.toLowerCase()).param("district", district))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/addresses").param("q", "boutique sama"))
                .andExpect(jsonPath("$.items[0].title", containsString("Boutique")));
        // Pagination bornée, résultat vide et critères invalides.
        mockMvc.perform(get("/api/v1/addresses").param("district", district).param("size", "1000"))
                .andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get("/api/v1/addresses").param("district", "aucun-quartier-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/addresses").param("type", "CHATEAU")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/addresses").param("q", "x".repeat(81)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.q").exists());
        // Les jokers SQL saisis ne servent pas à tout lister.
        mockMvc.perform(get("/api/v1/addresses").param("district", "%").param("type", "INSTITUTION")
                        .param("q", district))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void ownerEditsSendValidatedAddressBackToReviewAndArchiveRemovesIt() throws Exception {
        JsonNode created = client.publicAddress(owner, admin, Map.of("district", district));
        String id = created.get("id").asText();
        String code = created.get("publicCode").asText();
        mockMvc.perform(get("/api/v1/addresses/" + code)).andExpect(status().isOk());

        String other = client.registerUser();
        Map<String, Object> edit = Map.of("title", "Nouveau nom", "placeType", "BUSINESS",
                "description", "Portail vert", "publicationConsent", true);
        mockMvc.perform(put("/api/v1/me/addresses/" + id).header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/me/addresses/" + id).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", ""))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/me/addresses/" + id).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VALIDATION"))
                .andExpect(jsonPath("$.publicCode").value(code))
                .andExpect(jsonPath("$.title").value("Nouveau nom"));
        // Contenu non vérifié : la fiche publique disparaît jusqu'à la nouvelle validation.
        mockMvc.perform(get("/api/v1/addresses/" + code)).andExpect(status().isNotFound());
        client.validate(admin, id);
        mockMvc.perform(get("/api/v1/addresses/" + code)).andExpect(jsonPath("$.description").value("Portail vert"));

        mockMvc.perform(post("/api/v1/me/addresses/" + id + "/archive").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/me/addresses/" + id + "/archive").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(get("/api/v1/addresses/" + code)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/me/addresses/" + id + "/archive").header("Authorization", bearer(owner)))
                .andExpect(status().isConflict());
    }

    @Test
    void sameGridCellGetsDistinctCodesWithTieBreaker() throws Exception {
        Map<String, Object> payload = ApiTestClient.sampleAddress(true);
        payload.put("city", "Ziguinchor");
        payload.put("district", "Grille " + UUID.randomUUID().toString().substring(0, 4));
        payload.put("latitude", 12.5681);
        payload.put("longitude", -16.2733);
        String first = client.createAddress(owner, payload).get("publicCode").asText();
        String second = client.createAddress(owner, payload).get("publicCode").asText();
        org.assertj.core.api.Assertions.assertThat(first).matches("ZIG-[A-Z0-9]{2,3}-6826");
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first + "1");
    }

    /**
     * Régression (audit) : un point placé par erreur au centre du pays doit pouvoir être corrigé
     * tant que l'adresse n'est pas validée ; le code suit la nouvelle case de la grille.
     */
    @Test
    void ownerCorrectsPositionWhilePendingAndCodeFollowsTheGrid() throws Exception {
        Map<String, Object> payload = ApiTestClient.sampleAddress(true);
        payload.put("city", "Ziguinchor");
        payload.put("district", "Kenia " + UUID.randomUUID().toString().substring(0, 4));
        payload.put("latitude", 14.466596);   // centre de la carte du Sénégal : mauvais point
        payload.put("longitude", -14.458008);
        JsonNode created = client.createAddress(owner, payload);
        String id = created.get("id").asText();
        org.assertj.core.api.Assertions.assertThat(created.get("publicCode").asText()).endsWith("-6641");

        String body = json.writeValueAsString(Map.of("latitude", 12.5770, "longitude", -16.2510));
        mockMvc.perform(put("/api/v1/me/addresses/" + id + "/location").header("Authorization", bearer(client.registerUser()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/me/addresses/" + id + "/location").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("latitude", -16.25, "longitude", 12.57))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/me/addresses/" + id + "/location").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(12.577))
                .andExpect(jsonPath("$.longitude").value(-16.251))
                // 12.577 → ligne 77 ; -16.251 → case -16251 → colonne 49.
                .andExpect(jsonPath("$.publicCode", org.hamcrest.Matchers.endsWith("-7749")));

        client.validate(admin, id);
        mockMvc.perform(put("/api/v1/me/addresses/" + id + "/location").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }
}
