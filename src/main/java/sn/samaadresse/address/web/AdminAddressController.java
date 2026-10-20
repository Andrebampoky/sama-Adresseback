package sn.samaadresse.address.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.samaadresse.address.domain.AddressStatus;
import org.springframework.http.ResponseEntity;
import sn.samaadresse.address.service.AddressPhotoService;
import sn.samaadresse.address.service.ModerationService;
import sn.samaadresse.common.PageResponse;
import sn.samaadresse.security.CurrentUser;

/** Modération. L'accès est refusé (403) à tout compte non ADMIN par SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/addresses")
@Tag(name = "Administration — adresses")
@SecurityRequirement(name = "bearer")
public class AdminAddressController {
    private final ModerationService moderation;
    private final AddressPhotoService photos;

    public AdminAddressController(ModerationService moderation, AddressPhotoService photos) {
        this.moderation = moderation;
        this.photos = photos;
    }

    @GetMapping
    @Operation(summary = "Lister les adresses par statut (par défaut : en attente)")
    public PageResponse<OwnerAddressResponse> list(
            @RequestParam(defaultValue = "PENDING_VALIDATION") AddressStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return PageResponse.from(moderation.listByStatus(status, page, size), OwnerAddressResponse::from);
    }

    @PostMapping("/{id}/validate")
    @Operation(summary = "Valider une adresse en attente")
    public OwnerAddressResponse validate(Authentication authentication, @PathVariable UUID id) {
        return OwnerAddressResponse.from(
                moderation.decide(id, AddressStatus.VALIDATED, CurrentUser.id(authentication)));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Rejeter une adresse en attente")
    public OwnerAddressResponse reject(Authentication authentication, @PathVariable UUID id) {
        return OwnerAddressResponse.from(
                moderation.decide(id, AddressStatus.REJECTED, CurrentUser.id(authentication)));
    }

    @GetMapping("/{id}/photo")
    @Operation(summary = "Voir la photo de façade d'une adresse à modérer")
    @ApiResponse(responseCode = "200", description = "Photo JPEG réencodée par le serveur",
            content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "Adresse introuvable ou sans photo",
            content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<byte[]> photo(@PathVariable UUID id) {
        return PhotoResponses.jpeg(photos.adminPhoto(id), false);
    }
}
