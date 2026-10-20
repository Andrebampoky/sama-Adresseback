package sn.samaadresse.address.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import sn.samaadresse.address.service.AddressPhotoService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.service.OwnerAddressService;
import sn.samaadresse.common.PageResponse;
import sn.samaadresse.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/me/addresses")
@Tag(name = "Mes adresses")
@SecurityRequirement(name = "bearer")
public class OwnerAddressController {
    private final OwnerAddressService service;
    private final AddressPhotoService photos;

    public OwnerAddressController(OwnerAddressService service, AddressPhotoService photos) {
        this.service = service;
        this.photos = photos;
    }

    @PostMapping
    @Operation(summary = "Créer une adresse (statut initial PENDING_VALIDATION, code attribué par le serveur)")
    public ResponseEntity<OwnerAddressResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreateAddressRequest request
    ) {
        Address created = service.create(CurrentUser.id(authentication), request.toNewAddress());
        return ResponseEntity.created(URI.create("/api/v1/me/addresses/" + created.getId()))
                .body(OwnerAddressResponse.from(created));
    }

    @GetMapping
    @Operation(summary = "Lister mes adresses, les plus récentes d'abord")
    public PageResponse<OwnerAddressResponse> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return PageResponse.from(service.listOwned(CurrentUser.id(authentication), page, size),
                OwnerAddressResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter une de mes adresses")
    public OwnerAddressResponse get(Authentication authentication, @PathVariable UUID id) {
        return OwnerAddressResponse.from(service.getOwned(CurrentUser.id(authentication), id));
    }

    @PutMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Ajouter ou remplacer la photo de façade (JPEG/PNG, 5 Mo max, pendant la vérification)")
    public OwnerAddressResponse putPhoto(
            Authentication authentication,
            @PathVariable UUID id,
            @RequestPart("photo") MultipartFile photo
    ) throws IOException {
        return OwnerAddressResponse.from(photos.replace(CurrentUser.id(authentication), id, photo.getBytes()));
    }

    @DeleteMapping("/{id}/photo")
    @Operation(summary = "Retirer la photo de façade")
    public OwnerAddressResponse deletePhoto(Authentication authentication, @PathVariable UUID id) {
        return OwnerAddressResponse.from(photos.remove(CurrentUser.id(authentication), id));
    }

    @GetMapping("/{id}/photo")
    @Operation(summary = "Voir la photo de façade d'une de mes adresses")
    @ApiResponse(responseCode = "200", description = "Photo JPEG réencodée par le serveur",
            content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "Adresse introuvable ou sans photo",
            content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<byte[]> getPhoto(Authentication authentication, @PathVariable UUID id) {
        return PhotoResponses.jpeg(photos.ownerPhoto(CurrentUser.id(authentication), id), false);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier le contenu et la visibilité d'une de mes adresses",
            description = "Position, ville et quartier non modifiables (ils déterminent le code). Une adresse "
                    + "déjà validée ou rejetée repasse en vérification si son contenu change. 409 si archivée.")
    public OwnerAddressResponse update(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAddressRequest request
    ) {
        UUID ownerId = CurrentUser.id(authentication);
        service.update(ownerId, id, request.title(), request.placeType(), request.description(),
                request.publicationConsent(), Boolean.TRUE.equals(request.ownerNamePublic()));
        return OwnerAddressResponse.from(service.getOwned(ownerId, id));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archiver définitivement une de mes adresses (elle n'est plus publique)")
    public OwnerAddressResponse archive(Authentication authentication, @PathVariable UUID id) {
        return OwnerAddressResponse.from(service.archive(CurrentUser.id(authentication), id));
    }

    public record RelocateRequest(
            @jakarta.validation.constraints.NotNull(message = "La latitude est obligatoire.")
            @jakarta.validation.constraints.DecimalMin(value = "-90", message = "La latitude doit être comprise entre -90 et 90.")
            @jakarta.validation.constraints.DecimalMax(value = "90", message = "La latitude doit être comprise entre -90 et 90.")
            java.math.BigDecimal latitude,
            @jakarta.validation.constraints.NotNull(message = "La longitude est obligatoire.")
            @jakarta.validation.constraints.DecimalMin(value = "-180", message = "La longitude doit être comprise entre -180 et 180.")
            @jakarta.validation.constraints.DecimalMax(value = "180", message = "La longitude doit être comprise entre -180 et 180.")
            java.math.BigDecimal longitude
    ) {
    }

    @PutMapping("/{id}/location")
    @Operation(summary = "Corriger la position d'une adresse en attente de vérification (le code est recalculé)",
            description = "409 si l'adresse a déjà été validée, rejetée ou archivée ; 400 hors zone de service.")
    public OwnerAddressResponse relocate(Authentication authentication, @PathVariable UUID id,
                                         @Valid @RequestBody RelocateRequest request) {
        return OwnerAddressResponse.from(
                service.relocate(CurrentUser.id(authentication), id, request.latitude(), request.longitude()));
    }
}
