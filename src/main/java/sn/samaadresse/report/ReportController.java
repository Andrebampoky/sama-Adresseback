package sn.samaadresse.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.common.PageResponse;
import sn.samaadresse.security.CurrentUser;

@RestController
@Tag(name = "Signalements")
@SecurityRequirement(name = "bearer")
public class ReportController {
    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    public record CreateReportRequest(
            @NotNull(message = "Choisissez un motif de signalement.") ReportReason reason,
            @Size(max = 500, message = "Le commentaire ne doit pas dépasser 500 caractères.") String comment
    ) {
    }

    @Schema(description = "Signalement vu par un administrateur.")
    public record ReportResponse(
            UUID id,
            ReportReason reason,
            String comment,
            ReportStatus status,
            Instant createdAt,
            Instant resolvedAt,
            String reporterName,
            UUID addressId,
            String publicCode,
            String addressTitle,
            String district,
            String city,
            AddressStatus addressStatus
    ) {
        static ReportResponse from(AddressReport r) {
            return new ReportResponse(r.getId(), r.getReason(), r.getComment(), r.getStatus(), r.getCreatedAt(),
                    r.getResolvedAt(), r.getReporter().getDisplayName(), r.getAddress().getId(),
                    r.getAddress().getPublicCode(), r.getAddress().getTitle(), r.getAddress().getDistrict(),
                    r.getAddress().getCity(), r.getAddress().getStatus());
        }
    }

    public record ReportCreated(UUID id, ReportStatus status) {
    }

    @PostMapping("/api/v1/addresses/{code}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Signaler une adresse publique (utilisateur connecté)",
            description = "404 si l'adresse n'est pas publique ; 409 si vous avez déjà un signalement ouvert ; "
                    + "400 pour votre propre adresse.")
    public ReportCreated report(Authentication authentication, @PathVariable String code,
                                @Valid @RequestBody CreateReportRequest request) {
        AddressReport report = service.report(CurrentUser.id(authentication), code, request.reason(), request.comment());
        return new ReportCreated(report.getId(), report.getStatus());
    }

    @GetMapping("/api/v1/admin/reports")
    @Operation(summary = "Lister les signalements par statut (ADMIN), les plus anciens d'abord")
    public PageResponse<ReportResponse> list(
            @RequestParam(defaultValue = "OPEN") ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return PageResponse.from(service.list(status, page, size), ReportResponse::from);
    }

    @PostMapping("/api/v1/admin/reports/{id}/dismiss")
    @Operation(summary = "Écarter un signalement : l'adresse reste en ligne (ADMIN)")
    public ReportResponse dismiss(Authentication authentication, @PathVariable UUID id) {
        return ReportResponse.from(service.dismiss(id, CurrentUser.id(authentication)));
    }

    @PostMapping("/api/v1/admin/reports/{id}/archive-address")
    @Operation(summary = "Signalement fondé : archiver l'adresse et clore ses signalements (ADMIN)")
    public ReportResponse archiveAddress(Authentication authentication, @PathVariable UUID id) {
        return ReportResponse.from(service.archiveAddress(id, CurrentUser.id(authentication)));
    }
}
