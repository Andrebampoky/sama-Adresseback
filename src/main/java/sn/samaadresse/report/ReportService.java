package sn.samaadresse.report;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.service.AddressService;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

/**
 * Signalements : un utilisateur connecté signale une adresse publique ; un administrateur
 * l'écarte ou archive l'adresse (ce qui clôt tous les signalements ouverts de cette adresse).
 */
@Service
public class ReportService {
    private final AddressReportRepository reports;
    private final AddressService addresses;
    private final UserAccountRepository users;

    public ReportService(AddressReportRepository reports, AddressService addresses, UserAccountRepository users) {
        this.reports = reports;
        this.addresses = addresses;
        this.users = users;
    }

    @Transactional
    public AddressReport report(UUID reporterId, String publicCode, ReportReason reason, String comment) {
        Address address = addresses.findValidatedPublicAddress(publicCode);
        if (reporterId.equals(address.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vous ne pouvez pas signaler votre propre adresse : modifiez-la depuis votre espace.");
        }
        if (reports.existsByAddressIdAndReporterIdAndStatus(address.getId(), reporterId, ReportStatus.OPEN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vous avez déjà un signalement en cours pour cette adresse.");
        }
        UserAccount reporter = users.findById(reporterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Compte introuvable."));
        String cleanComment = comment == null || comment.isBlank() ? null : comment.trim();
        return reports.save(new AddressReport(address, reporter, reason, cleanComment));
    }

    @Transactional(readOnly = true)
    public Page<AddressReport> list(ReportStatus status, int page, int size) {
        return reports.findByStatus(status, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    @Transactional
    public AddressReport dismiss(UUID reportId, UUID adminId) {
        AddressReport report = find(reportId);
        resolve(report, ReportStatus.DISMISSED, adminId);
        return report;
    }

    /** Signalement fondé : l'adresse est archivée et tous ses signalements ouverts sont clos. */
    @Transactional
    public AddressReport archiveAddress(UUID reportId, UUID adminId) {
        AddressReport report = find(reportId);
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce signalement a déjà été traité.");
        }
        try {
            report.getAddress().archive(adminId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        for (AddressReport open : reports.findByAddressIdAndStatus(report.getAddress().getId(), ReportStatus.OPEN)) {
            open.resolve(ReportStatus.ACTION_TAKEN, adminId);
        }
        return report;
    }

    private AddressReport find(UUID reportId) {
        return reports.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Signalement introuvable."));
    }

    private static void resolve(AddressReport report, ReportStatus outcome, UUID adminId) {
        try {
            report.resolve(outcome, adminId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
