package sn.samaadresse.address.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.service.DirectoryQuery;
import sn.samaadresse.common.PageResponse;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import sn.samaadresse.address.service.AddressPhotoService;
import sn.samaadresse.address.service.AddressService;

@RestController
@RequestMapping("/api/v1/addresses")
@Tag(name = "Adresses publiques")
public class AddressController {
    private final AddressService addressService;
    private final AddressPhotoService photos;

    public AddressController(AddressService addressService, AddressPhotoService photos) {
        this.addressService = addressService;
        this.photos = photos;
    }

    @GetMapping
    @Operation(summary = "Annuaire public : recherche par code, nom du propriétaire, quartier, ville et type",
            description = "Seules les adresses validées et publiées sont renvoyées. Le nom du propriétaire n'est "
                    + "cherché et affiché que s'il l'a autorisé. Pagination : size 1 à 50 (20 par défaut).")
    @ApiResponse(responseCode = "200", description = "Page de résultats (éventuellement vide)")
    @ApiResponse(responseCode = "400", description = "Critère invalide (trop long, type inconnu)")
    public PageResponse<AddressPublicResponse> search(
            @Parameter(description = "Texte libre : nom du lieu, quartier, ville, code ou propriétaire")
            @RequestParam(required = false) String q,
            @Parameter(description = "Début du code, ex. ZIG-KAN") @RequestParam(required = false) String code,
            @Parameter(description = "Nom du propriétaire") @RequestParam(required = false) String owner,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) PlaceType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return PageResponse.from(
                addressService.searchDirectory(new DirectoryQuery(q, code, owner, district, city, type), page, size),
                AddressPublicResponse::from);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Retrouver une adresse publique validée par son code")
    @ApiResponse(responseCode = "200", description = "Adresse trouvée")
    @ApiResponse(responseCode = "400", description = "Format de code invalide")
    @ApiResponse(responseCode = "404", description = "Adresse absente ou non publiée")
    public AddressPublicResponse findByCode(
            @Parameter(description = "Code d'adresse, par exemple ZIG-KBG-4271")
            @PathVariable String code
    ) {
        return AddressPublicResponse.from(addressService.findValidatedPublicAddress(code));
    }

    @GetMapping("/{code}/photo")
    @Operation(summary = "Photo de façade d'une adresse publique validée")
    @ApiResponse(responseCode = "200", description = "Photo JPEG réencodée par le serveur",
            content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "Adresse non publique ou sans photo",
            content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<byte[]> photo(@PathVariable String code) {
        return PhotoResponses.jpeg(photos.publicPhoto(code), true);
    }
}
