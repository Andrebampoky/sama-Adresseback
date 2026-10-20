package sn.samaadresse.address.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.common.FieldValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Collisions de code (application et contrainte UNIQUE), zone de service et règles propriétaire. */
class OwnerAddressServiceTest {
    private final AddressRepository repository = mock(AddressRepository.class);
    private final AddressCodeGenerator generator = new AddressCodeGenerator();
    private OwnerAddressService service;

    private static final NewAddress INPUT = new NewAddress("Lieu", PlaceType.HOUSE, "Quartier", "Ville", null,
            new BigDecimal("14.5"), new BigDecimal("-15.5"), true, false);
    private final String base = generator.candidate("Ville", "Quartier", new BigDecimal("14.500000"),
            new BigDecimal("-15.500000"), 0);

    @BeforeEach
    void setUp() {
        service = new OwnerAddressService(repository, generator, mock(PlatformTransactionManager.class),
                new BigDecimal("12.0"), new BigDecimal("16.8"), new BigDecimal("-17.6"), new BigDecimal("-11.3"));
        when(repository.saveAndFlush(any(Address.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key value violates unique constraint \"" + constraint + "\"",
                        new SQLException("duplicate"), constraint));
    }

    @Test
    void firstAddressOfCellGetsTheBareGridCode() {
        Address created = service.create(UUID.randomUUID(), INPUT);
        assertThat(created.getPublicCode()).isEqualTo(base);
        assertThat(created.getStatus()).isEqualTo(AddressStatus.PENDING_VALIDATION);
    }

    @Test
    void addsTieBreakerWhenCellIsAlreadyTaken() {
        when(repository.existsByPublicCode(base)).thenReturn(true);
        assertThat(service.create(UUID.randomUUID(), INPUT).getPublicCode()).isEqualTo(base + "1");
    }

    @Test
    void retriesWhenDatabaseRejectsConcurrentDuplicate() {
        when(repository.saveAndFlush(any(Address.class)))
                .thenThrow(uniqueViolation("uk_addresses_public_code"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(UUID.randomUUID(), INPUT).getPublicCode()).isEqualTo(base + "1");
        verify(repository, times(2)).saveAndFlush(any(Address.class));
    }

    @Test
    void doesNotHideOtherIntegrityErrors() {
        when(repository.saveAndFlush(any(Address.class))).thenThrow(uniqueViolation("fk_addresses_owner"));
        assertThrows(DataIntegrityViolationException.class, () -> service.create(UUID.randomUUID(), INPUT));
    }

    @Test
    void givesUpWith503WhenTheWholeCellIsFull() {
        when(repository.existsByPublicCode(anyString())).thenReturn(true);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(UUID.randomUUID(), INPUT));
        assertThat(error.getStatusCode().value()).isEqualTo(503);
        verify(repository, times(AddressCodeGenerator.MAX_ATTEMPTS)).existsByPublicCode(anyString());
    }

    @Test
    void refusesPointsOutsideSenegal() {
        NewAddress paris = new NewAddress("Lieu", PlaceType.HOUSE, "Q", "V", null,
                new BigDecimal("48.85"), new BigDecimal("2.35"), true, false);
        FieldValidationException error = assertThrows(FieldValidationException.class,
                () -> service.create(UUID.randomUUID(), paris));
        assertThat(error.getErrors()).containsKeys("latitude", "longitude");
    }

    @Test
    void editingValidatedAddressSendsItBackToReviewAndArchivedIsFrozen() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Address address = Address.createdByOwner(owner, "VIL-QUA-0001", "Lieu", PlaceType.HOUSE, "Q", "V", null,
                new BigDecimal("14.5"), new BigDecimal("-15.5"), true, true);
        address.moderate(AddressStatus.VALIDATED, UUID.randomUUID());
        when(repository.findByIdAndOwnerId(id, owner)).thenReturn(Optional.of(address));

        // Changement de visibilité seule : pas de nouvelle vérification.
        assertThat(service.update(owner, id, "Lieu", PlaceType.HOUSE, null, true, false)).isFalse();
        assertThat(address.getStatus()).isEqualTo(AddressStatus.VALIDATED);
        assertThat(address.isOwnerNamePublic()).isFalse();
        // Changement de contenu : retour en vérification.
        assertThat(service.update(owner, id, "Nouveau nom", PlaceType.BUSINESS, " ", true, true)).isTrue();
        assertThat(address.getStatus()).isEqualTo(AddressStatus.PENDING_VALIDATION);
        assertThat(address.getModeratedAt()).isNull();
        // Le nom ne peut pas être public si l'adresse ne l'est pas.
        service.update(owner, id, "Nouveau nom", PlaceType.BUSINESS, null, false, true);
        assertThat(address.isOwnerNamePublic()).isFalse();
        assertThat(address.isPubliclyVisible()).isFalse();

        service.archive(owner, id);
        assertThat(address.getStatus()).isEqualTo(AddressStatus.ARCHIVED);
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> service.update(owner, id, "X", PlaceType.HOUSE, null, true, false));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(assertThrows(ResponseStatusException.class, () -> service.archive(owner, id))
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void anotherOwnersAddressIsNotFound() {
        assertThat(assertThrows(ResponseStatusException.class,
                () -> service.getOwned(UUID.randomUUID(), UUID.randomUUID())).getStatusCode().value()).isEqualTo(404);
    }
}
