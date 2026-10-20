package sn.samaadresse.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.user.domain.UserAccount;

/** Signalement d'une adresse publique (mauvaise position, lieu inexistant, contenu inapproprié…). */
@Entity
@Table(name = "address_reports")
public class AddressReport {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "address_id", nullable = false, updatable = false)
    private Address address;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false, updatable = false)
    private UserAccount reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReportReason reason;

    @Column(length = 500)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    protected AddressReport() {
    }

    public AddressReport(Address address, UserAccount reporter, ReportReason reason, String comment) {
        this.address = address;
        this.reporter = reporter;
        this.reason = reason;
        this.comment = comment;
        this.status = ReportStatus.OPEN;
        this.createdAt = Instant.now();
    }

    /** Un signalement n'est traité qu'une fois. */
    public void resolve(ReportStatus outcome, UUID adminId) {
        if (status != ReportStatus.OPEN) {
            throw new IllegalStateException("Ce signalement a déjà été traité.");
        }
        if (outcome == ReportStatus.OPEN) {
            throw new IllegalArgumentException("Décision invalide.");
        }
        this.status = outcome;
        this.resolvedAt = Instant.now();
        this.resolvedBy = adminId;
    }

    public UUID getId() {
        return id;
    }

    public Address getAddress() {
        return address;
    }

    public UserAccount getReporter() {
        return reporter;
    }

    public ReportReason getReason() {
        return reason;
    }

    public String getComment() {
        return comment;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
