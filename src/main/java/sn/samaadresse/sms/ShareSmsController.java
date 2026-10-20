package sn.samaadresse.sms;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.service.OwnerAddressService;
import sn.samaadresse.common.FieldValidationException;
import sn.samaadresse.common.RateLimiter;
import sn.samaadresse.security.CurrentUser;

/**
 * Le propriétaire envoie par SMS le lien de la fiche publique de son adresse.
 * Réservé aux adresses publiques (sinon le lien ne fonctionnerait pas) et limité à 5 SMS par heure.
 */
@RestController
@Tag(name = "Partage")
@SecurityRequirement(name = "bearer")
public class ShareSmsController {
    private static final Logger log = LoggerFactory.getLogger(ShareSmsController.class);
    private final SmsGateway gateway;
    private final OwnerAddressService addresses;
    private final String publicBaseUrl;
    private final RateLimiter limiter = new RateLimiter(5, Duration.ofHours(1));

    public ShareSmsController(SmsGateway gateway, OwnerAddressService addresses,
                              @Value("${app.public-base-url:http://localhost:5173}") String publicBaseUrl) {
        this.gateway = gateway;
        this.addresses = addresses;
        this.publicBaseUrl = checkedBaseUrl(publicBaseUrl);
    }

    /**
     * L'URL publique du frontend doit être absolue (http ou https) : sinon les liens envoyés seraient
     * inutilisables et l'erreur ne se verrait qu'à la réception du SMS. Le démarrage échoue donc.
     */
    static String checkedBaseUrl(String raw) {
        String trimmed = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_PUBLIC_BASE_URL invalide : " + trimmed, e);
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalStateException("APP_PUBLIC_BASE_URL doit être une URL absolue http(s), ex. https://samaadresse.sn");
        }
        if ("localhost".equals(uri.getHost()) || uri.getHost().startsWith("127.")) {
            log.warn("APP_PUBLIC_BASE_URL={} : les liens envoyés par SMS ne s'ouvriront que sur cet ordinateur, "
                    + "pas sur un téléphone.", trimmed);
        }
        return trimmed;
    }

    public record SmsRequest(@NotBlank(message = "Le numéro de téléphone est obligatoire.") String phone) {
    }

    public record SmsResponse(SmsResult.Status status, String provider, String to, String link) {
    }

    @PostMapping("/api/v1/me/addresses/{id}/sms")
    @Operation(summary = "Envoyer par SMS le lien de la fiche publique d'une de mes adresses",
            description = "503 si aucun fournisseur n'est configuré ; 409 si l'adresse n'est pas publique ; "
                    + "429 au-delà de 5 envois par heure. status=SIMULATED signifie qu'aucun SMS n'a été envoyé.")
    public SmsResponse send(Authentication authentication, @PathVariable UUID id,
                            @Valid @RequestBody SmsRequest request) {
        UUID ownerId = CurrentUser.id(authentication);
        Address address = addresses.getOwned(ownerId, id);
        String to = PhoneNumbers.normalize(request.phone()).orElseThrow(() -> new FieldValidationException(
                Map.of("phone", "Numéro invalide : mobile sénégalais à 9 chiffres commençant par 7 (77 123 45 67 ou +221 77 123 45 67), ou numéro international complet.")));
        if (!address.isPubliclyAccessible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le lien ne fonctionnera qu'une fois l'adresse validée et publiée.");
        }
        SmsProvider sms = gateway.provider().orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "L'envoi automatique de SMS n'est pas configuré sur ce serveur."));
        if (!limiter.tryAcquire(ownerId.toString())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Limite atteinte : 5 SMS par heure. Réessayez plus tard.");
        }
        String link = publicBaseUrl + "/a/" + address.getPublicCode();
        String body = "SamaAdresse " + address.getPublicCode() + " - " + address.getTitle() + ", "
                + address.getDistrict() + " : " + link;
        try {
            SmsResult result = sms.send(to, body);
            return new SmsResponse(result.status(), result.provider(), PhoneNumbers.mask(to), link);
        } catch (SmsException e) {
            throw new ResponseStatusException(e.getStatus(), e.getMessage());
        }
    }
}
