package sn.samaadresse.unit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.address.service.AddressPhotoService;
import sn.samaadresse.address.service.AddressService;
import sn.samaadresse.address.service.DirectoryQuery;
import sn.samaadresse.address.service.ModerationService;
import sn.samaadresse.common.FieldValidationException;
import sn.samaadresse.photo.PhotoProcessor;
import sn.samaadresse.photo.PhotoStorage;
import sn.samaadresse.report.AddressReport;
import sn.samaadresse.report.AddressReportRepository;
import sn.samaadresse.report.ReportReason;
import sn.samaadresse.report.ReportService;
import sn.samaadresse.report.ReportStatus;
import sn.samaadresse.user.domain.Role;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Règles métier testées avec des doublures (Mockito), sans base de données ni Spring. */
class DomainAndServicesUnitTest {
    private static Address address(UUID owner, boolean consent) {
        return Address.createdByOwner(owner, "ZIG-KAN-6826", "Maison", PlaceType.HOUSE, "Kandé", "Ziguinchor",
                null, new BigDecimal("12.5681"), new BigDecimal("-16.2733"), consent, false);
    }

    private static int status(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    @Test
    void addressLifecycleRules() {
        UUID owner = UUID.randomUUID();
        Address a = address(owner, true);
        assertThat(a.isPubliclyAccessible()).isFalse();
        assertThat(a.editContent("Maison", PlaceType.HOUSE, null)).isFalse();
        assertThat(a.attachPhoto("k1.jpg")).isNull();
        assertThat(a.hasPhoto()).isTrue();
        a.moderate(AddressStatus.VALIDATED, UUID.randomUUID());
        assertThat(a.isPubliclyAccessible()).isTrue();
        assertThrows(IllegalStateException.class, () -> a.moderate(AddressStatus.REJECTED, UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> a.attachPhoto("k2.jpg"));
        assertThat(a.removePhoto()).isEqualTo("k1.jpg");
        a.changeVisibility(false, true);
        assertThat(a.isPubliclyAccessible()).isFalse();
        assertThat(a.getPublicationConsentAt()).isNull();
        a.changeVisibility(true, false);
        assertThat(a.getPublicationConsentAt()).isNotNull();
        assertThat(a.getPublicOwnerName()).isNull();
        a.archive(owner);
        assertThat(a.getStatus()).isEqualTo(AddressStatus.ARCHIVED);
        assertThrows(IllegalStateException.class, () -> a.changeVisibility(true, true));
        assertThrows(IllegalArgumentException.class,
                () -> address(owner, true).moderate(AddressStatus.ARCHIVED, UUID.randomUUID()));
    }

    @Test
    void reportDomainResolvesOnce() {
        AddressReport report = new AddressReport(address(UUID.randomUUID(), true),
                new UserAccount("a@exemple.test", "{noop}x", "A"), ReportReason.OTHER, null);
        assertThrows(IllegalArgumentException.class, () -> report.resolve(ReportStatus.OPEN, UUID.randomUUID()));
        report.resolve(ReportStatus.DISMISSED, UUID.randomUUID());
        assertThat(report.getResolvedAt()).isNotNull();
        assertThrows(IllegalStateException.class, () -> report.resolve(ReportStatus.ACTION_TAKEN, UUID.randomUUID()));
    }

    @Test
    void userAccountsAreUsersUntilPromotedAndCanBeDisabled() {
        UserAccount user = new UserAccount("b@exemple.test", "{noop}x", "B");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.isEnabled()).isTrue();
        user.promoteToAdmin();
        user.setEnabled(false);
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void directorySearchCleansCriteriaAndBoundsPagination() {
        AddressRepository repository = mock(AddressRepository.class);
        Page<Address> empty = new PageImpl<>(List.of());
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class))).thenReturn(empty);
        AddressService service = new AddressService(repository);

        service.searchDirectory(new DirectoryQuery(" Boutique% ", "zig-kan", null, "", "_", PlaceType.BUSINESS), -3, 500);
        // Pagination bornée (page ≥ 0, 50 résultats au plus), du plus récent au plus ancien.
        verify(repository).findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(org.springframework.data.domain.PageRequest.of(0, 50,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))));
        FieldValidationException tooLong = assertThrows(FieldValidationException.class, () ->
                service.searchDirectory(new DirectoryQuery(null, null, "x".repeat(81), null, null, null), 0, 20));
        assertThat(tooLong.getErrors()).containsKey("owner");
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.findValidatedPublicAddress("ZIG-KAN-0000")))).isEqualTo(404);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.findValidatedPublicAddress("pas un code")))).isEqualTo(400);
    }

    @Test
    void reportServiceRules() {
        AddressReportRepository reports = mock(AddressReportRepository.class);
        AddressService addresses = mock(AddressService.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        ReportService service = new ReportService(reports, addresses, users);
        UUID owner = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        Address target = address(owner, true);
        when(addresses.findValidatedPublicAddress("ZIG-KAN-6826")).thenReturn(target);
        when(users.findById(reporter)).thenReturn(Optional.of(new UserAccount("r@exemple.test", "{noop}x", "R")));
        when(reports.save(any(AddressReport.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.report(owner, "ZIG-KAN-6826", ReportReason.OTHER, null)))).isEqualTo(400);
        AddressReport created = service.report(reporter, "ZIG-KAN-6826", ReportReason.WRONG_LOCATION, "  ");
        assertThat(created.getComment()).isNull();
        when(reports.existsByAddressIdAndReporterIdAndStatus(any(), eq(reporter), eq(ReportStatus.OPEN))).thenReturn(true);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.report(reporter, "ZIG-KAN-6826", ReportReason.OTHER, null)))).isEqualTo(409);

        UUID reportId = UUID.randomUUID();
        when(reports.findById(reportId)).thenReturn(Optional.of(created));
        when(reports.findByAddressIdAndStatus(any(), eq(ReportStatus.OPEN))).thenReturn(List.of(created));
        target.moderate(AddressStatus.VALIDATED, UUID.randomUUID());
        service.archiveAddress(reportId, UUID.randomUUID());
        assertThat(created.getStatus()).isEqualTo(ReportStatus.ACTION_TAKEN);
        assertThat(target.getStatus()).isEqualTo(AddressStatus.ARCHIVED);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.archiveAddress(reportId, UUID.randomUUID())))).isEqualTo(409);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.dismiss(reportId, UUID.randomUUID())))).isEqualTo(409);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.dismiss(UUID.randomUUID(), UUID.randomUUID())))).isEqualTo(404);
    }

    @Test
    void photoServiceRules() {
        AddressRepository repository = mock(AddressRepository.class);
        PhotoProcessor processor = mock(PhotoProcessor.class);
        PhotoStorage storage = mock(PhotoStorage.class);
        AddressPhotoService service = new AddressPhotoService(repository, processor, storage);
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Address a = address(owner, true);
        when(repository.findByIdAndOwnerId(id, owner)).thenReturn(Optional.of(a));
        when(processor.sanitize(any())).thenReturn(new byte[] {9});
        when(storage.store(any())).thenReturn("nouvelle.jpg");
        when(storage.load("nouvelle.jpg")).thenReturn(Optional.of(new byte[] {9}));

        service.replace(owner, id, new byte[] {1});
        assertThat(service.ownerPhoto(owner, id)).containsExactly(9);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.ownerPhoto(UUID.randomUUID(), id)))).isEqualTo(404);
        a.moderate(AddressStatus.REJECTED, UUID.randomUUID());
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.replace(owner, id, new byte[] {1})))).isEqualTo(409);
        service.remove(owner, id);
        verify(storage).delete("nouvelle.jpg");
        assertThat(status(assertThrows(ResponseStatusException.class, () -> service.ownerPhoto(owner, id)))).isEqualTo(404);
        assertThat(status(assertThrows(ResponseStatusException.class, () -> service.adminPhoto(UUID.randomUUID()))))
                .isEqualTo(404);
        assertThat(status(assertThrows(ResponseStatusException.class, () -> service.publicPhoto("ZIG-KAN-0000"))))
                .isEqualTo(404);
    }

    @Test
    void moderationOnlyDecidesPendingAddresses() {
        AddressRepository repository = mock(AddressRepository.class);
        ModerationService service = new ModerationService(repository);
        UUID id = UUID.randomUUID();
        Address a = address(UUID.randomUUID(), true);
        when(repository.findById(id)).thenReturn(Optional.of(a));
        assertThat(service.decide(id, AddressStatus.VALIDATED, UUID.randomUUID()).getStatus())
                .isEqualTo(AddressStatus.VALIDATED);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.decide(id, AddressStatus.REJECTED, UUID.randomUUID())))).isEqualTo(409);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> service.decide(UUID.randomUUID(), AddressStatus.VALIDATED, UUID.randomUUID())))).isEqualTo(404);
        verify(repository, never()).save(any());
    }

    @Test
    void loginLimiterBlocksAfterFailuresAndForgetsAfterWindow() throws Exception {
        Instant[] now = {Instant.parse("2026-10-19T10:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        var constructor = sn.samaadresse.auth.service.LoginAttemptLimiter.class
                .getDeclaredConstructor(int.class, Duration.class, Clock.class);
        constructor.setAccessible(true);
        var limiter = constructor.newInstance(2, Duration.ofMinutes(15), clock);
        limiter.recordFailure("x");
        assertThat(limiter.isBlocked("x")).isFalse();
        limiter.recordFailure("x");
        assertThat(limiter.isBlocked("x")).isTrue();
        now[0] = now[0].plus(Duration.ofMinutes(16));
        assertThat(limiter.isBlocked("x")).isFalse();
        limiter.recordFailure("y");
        limiter.reset("y");
        assertThat(limiter.isBlocked("y")).isFalse();
    }
}
