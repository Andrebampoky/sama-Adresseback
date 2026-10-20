package sn.samaadresse.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.common.PageResponse;
import sn.samaadresse.report.AddressReportRepository;
import sn.samaadresse.report.ReportStatus;
import sn.samaadresse.security.CurrentUser;
import sn.samaadresse.user.domain.Role;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

/** Indicateurs et gestion des comptes. Accès ADMIN imposé par SecurityConfig (/api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration — indicateurs et comptes")
@SecurityRequirement(name = "bearer")
public class AdminController {
    private final AddressRepository addresses;
    private final AddressReportRepository reports;
    private final UserAccountRepository users;

    public AdminController(AddressRepository addresses, AddressReportRepository reports, UserAccountRepository users) {
        this.addresses = addresses;
        this.reports = reports;
        this.users = users;
    }

    public record MonthlyCount(String month, long total, Map<PlaceType, Long> byType) {
    }

    public record Stats(
            long totalAddresses,
            Map<AddressStatus, Long> byStatus,
            Map<PlaceType, Long> byType,
            long publicAddresses,
            long openReports,
            long users,
            long activeUsers,
            List<MonthlyCount> monthly
    ) {
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    @Operation(summary = "Indicateurs exacts calculés en base (compteurs, répartition, 12 derniers mois)")
    public Stats stats() {
        Map<AddressStatus, Long> byStatus = new EnumMap<>(AddressStatus.class);
        for (AddressStatus status : AddressStatus.values()) {
            byStatus.put(status, addresses.countByStatus(status));
        }
        Map<PlaceType, Long> byType = new EnumMap<>(PlaceType.class);
        for (PlaceType type : PlaceType.values()) {
            byType.put(type, addresses.countByPlaceType(type));
        }
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        Map<YearMonth, MonthlyCount> months = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) {
            YearMonth month = current.minusMonths(i);
            Map<PlaceType, Long> empty = new EnumMap<>(PlaceType.class);
            for (PlaceType type : PlaceType.values()) {
                empty.put(type, 0L);
            }
            months.put(month, new MonthlyCount(month.toString(), 0, empty));
        }
        Instant since = current.minusMonths(11).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        for (Object[] row : addresses.creationsSince(since)) {
            YearMonth month = YearMonth.from(((Instant) row[0]).atZone(ZoneOffset.UTC));
            MonthlyCount count = months.get(month);
            if (count != null) {
                count.byType().merge((PlaceType) row[1], 1L, Long::sum);
                months.put(month, new MonthlyCount(count.month(), count.total() + 1, count.byType()));
            }
        }
        return new Stats(addresses.count(), byStatus, byType, addresses.countPublic(),
                reports.countByStatus(ReportStatus.OPEN), users.count(), users.countByEnabledTrue(),
                List.copyOf(months.values()));
    }

    public record UserSummary(UUID id, String email, String displayName, Role role, boolean enabled,
                              Instant createdAt, long addressCount) {
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    @Operation(summary = "Comptes, les plus récents d'abord (ADMIN)")
    public PageResponse<UserSummary> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return PageResponse.from(
                users.findAllBy(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                        Sort.by(Sort.Direction.DESC, "createdAt"))),
                this::summary);
    }

    @PostMapping("/users/{id}/disable")
    @Transactional
    @Operation(summary = "Désactiver un compte (ADMIN). Impossible sur son propre compte ou un autre administrateur.")
    public UserSummary disable(Authentication authentication, @PathVariable UUID id) {
        UserAccount user = find(id);
        if (user.getId().equals(CurrentUser.id(authentication))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vous ne pouvez pas désactiver votre propre compte.");
        }
        if (user.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un compte administrateur ne peut pas être désactivé ici.");
        }
        user.setEnabled(false);
        return summary(user);
    }

    @PostMapping("/users/{id}/enable")
    @Transactional
    @Operation(summary = "Réactiver un compte (ADMIN)")
    public UserSummary enable(@PathVariable UUID id) {
        UserAccount user = find(id);
        user.setEnabled(true);
        return summary(user);
    }

    private UserAccount find(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable."));
    }

    private UserSummary summary(UserAccount u) {
        return new UserSummary(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.isEnabled(),
                u.getCreatedAt(), addresses.countByOwnerId(u.getId()));
    }
}
