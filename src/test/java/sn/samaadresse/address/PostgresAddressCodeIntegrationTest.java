package sn.samaadresse.address;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.address.service.NewAddress;
import sn.samaadresse.address.service.OwnerAddressService;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Vérifications sur un vrai PostgreSQL 16 (conteneur jetable). Ignoré si Docker est indisponible.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresAddressCodeIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OwnerAddressService service;
    @Autowired
    private AddressRepository addresses;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID newOwner() {
        return users.save(new UserAccount("pg-" + UUID.randomUUID() + "@exemple.test", "{noop}inutilisé", "Test"))
                .getId();
    }

    private static NewAddress sample() {
        return new NewAddress("Lieu fictif", PlaceType.OTHER, "Quartier", "Ville", null,
                new BigDecimal("14.5"), new BigDecimal("-15.5"), true, false);
    }

    @Test
    void postgresUniqueConstraintRejectsDuplicateCode() {
        UUID owner = newOwner();
        addresses.saveAndFlush(Address.createdByOwner(owner, "DUP-PGS-1111", "A", PlaceType.HOUSE, "Q", "V", null,
                new BigDecimal("14.5"), new BigDecimal("-15.5"), false, false));

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, () ->
                addresses.saveAndFlush(Address.createdByOwner(owner, "DUP-PGS-1111", "B", PlaceType.HOUSE, "Q", "V",
                        null, new BigDecimal("14.5"), new BigDecimal("-15.5"), false, false)));
        // OwnerAddressService s'appuie sur ce nom de contrainte pour distinguer une collision de code.
        assertThat(error.getMostSpecificCause().getMessage()).contains("uk_addresses_public_code");
    }

    /**
     * Douze créations simultanées au même point : même case de grille, donc même code de base.
     * Les collisions sont réglées par l'application puis par la contrainte UNIQUE de PostgreSQL.
     */
    @Test
    void concurrentCreationsAlwaysGetDistinctCodes() throws Exception {
        UUID owner = newOwner();
        int count = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                tasks.add(() -> service.create(owner, sample()).getPublicCode());
            }
            Set<String> codes = new HashSet<>();
            for (Future<String> future : pool.invokeAll(tasks)) {
                codes.add(future.get());
            }
            assertThat(codes).hasSize(count);
        } finally {
            pool.shutdownNow();
        }
        Integer stored = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT public_code) FROM addresses WHERE owner_id = ?", Integer.class, owner);
        assertThat(stored).isEqualTo(count);
    }

    @Test
    void flywayAppliedAllMigrationsOnPostgres() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }

    /**
     * L'annuaire doit fonctionner sur le vrai PostgreSQL avec n'importe quelle combinaison de critères
     * (une première version échouait avec des paramètres nuls que PostgreSQL ne savait pas typer).
     */
    @Test
    void publicDirectorySearchWorksOnPostgres(@Autowired sn.samaadresse.address.service.AddressService directory) {
        UUID owner = newOwner();
        String district = "PgQuartier" + UUID.randomUUID().toString().substring(0, 6);
        Address shown = Address.createdByOwner(owner, "PGS-DIR-" + (1000 + new java.util.Random().nextInt(8999)),
                "Boutique Postgres", PlaceType.BUSINESS, district, "Ziguinchor", null,
                new BigDecimal("12.5"), new BigDecimal("-16.2"), true, true);
        shown.moderate(sn.samaadresse.address.domain.AddressStatus.VALIDATED, owner);
        addresses.saveAndFlush(shown);

        var none = new sn.samaadresse.address.service.DirectoryQuery(null, null, null, null, null, null);
        assertThat(directory.searchDirectory(none, 0, 20).getTotalElements()).isGreaterThanOrEqualTo(1);
        var all = new sn.samaadresse.address.service.DirectoryQuery("boutique", shown.getPublicCode().substring(0, 7),
                "test", district.toLowerCase(), "ziguinchor", PlaceType.BUSINESS);
        var page = directory.searchDirectory(all, 0, 20);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getPublicOwnerName()).isEqualTo("Test");
    }
}
