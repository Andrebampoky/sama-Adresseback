package sn.samaadresse.address;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.repository.AddressRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddressApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AddressRepository addressRepository;

    @BeforeEach
    void prepareAddress() {
        addressRepository.deleteAll();
        addressRepository.save(new Address(
                "ZIG-KBG-4271",
                "Maison familiale",
                PlaceType.HOUSE,
                "Kandé",
                "Ziguinchor",
                "Près du terrain de sport.",
                new BigDecimal("12.560000"),
                new BigDecimal("-16.270000"),
                null,
                AddressStatus.VALIDATED,
                true
        ));
        addressRepository.save(new Address(
                "ZIG-KBG-4272",
                "Adresse en attente",
                PlaceType.BUSINESS,
                "Kandé",
                "Ziguinchor",
                null,
                new BigDecimal("12.560001"),
                new BigDecimal("-16.270001"),
                null,
                AddressStatus.PENDING_VALIDATION,
                true
        ));
    }

    @Test
    void returnsOnlyValidatedPublicFieldsAndNormalizesCodeCase() throws Exception {
        mockMvc.perform(get("/api/v1/addresses/zig-kbg-4271")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicCode").value("ZIG-KBG-4271"))
                .andExpect(jsonPath("$.district").value("Kandé"))
                .andExpect(jsonPath("$.latitude").value(12.56))
                .andExpect(jsonPath("$.ownerEmail").doesNotExist());
    }

    @Test
    void doesNotExposeAddressesAwaitingValidation() throws Exception {
        mockMvc.perform(get("/api/v1/addresses/ZIG-KBG-4272"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedCodes() throws Exception {
        mockMvc.perform(get("/api/v1/addresses/not-a-code"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthEndpointChecksDatabaseConnection() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }
}
