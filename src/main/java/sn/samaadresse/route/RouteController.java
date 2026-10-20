package sn.samaadresse.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.service.AddressService;
import sn.samaadresse.common.FieldValidationException;
import sn.samaadresse.common.RateLimiter;

/**
 * Itinéraire depuis la position de la personne jusqu'à une adresse publique.
 *
 * - Avec une clé OpenRouteService (ORS_API_KEY, service demandé par le cahier des charges) : calcul par ORS ;
 *   la clé reste sur le serveur.
 * - Sans clé : repli sur un serveur OSRM public et gratuit, sans clé (par défaut celui de FOSSGIS,
 *   routing.openstreetmap.de, données OpenStreetMap). Ce serveur communautaire convient au
 *   développement et aux démonstrations, pas à un fort trafic. Désactivable : APP_ROUTING_FALLBACK_ENABLED=false.
 * - Sans clé et sans repli : 503 explicite, l'interface propose le lien d'itinéraire OpenStreetMap.
 *
 * La position de départ n'est ni enregistrée ni journalisée.
 */
@RestController
@Tag(name = "Itinéraire")
public class RouteController {
    private static final List<String> PROFILES = List.of("foot-walking", "driving-car", "cycling-regular");
    /** Profil ORS → profil du serveur OSRM de FOSSGIS. */
    private static final Map<String, String> OSRM_PROFILES = Map.of(
            "foot-walking", "foot", "driving-car", "car", "cycling-regular", "bike");

    private final AddressService addresses;
    private final ObjectMapper json;
    private final HttpClient http;
    private final String orsBaseUrl;
    private final String orsApiKey;
    private final boolean fallbackEnabled;
    private final String osrmBaseUrl;
    private final RateLimiter limiter = new RateLimiter(30, Duration.ofMinutes(1));

    @Autowired
    public RouteController(AddressService addresses, ObjectMapper json,
                           @Value("${app.routing.ors.base-url:https://api.openrouteservice.org}") String orsBaseUrl,
                           @Value("${app.routing.ors.api-key:}") String orsApiKey,
                           @Value("${app.routing.fallback.enabled:true}") boolean fallbackEnabled,
                           @Value("${app.routing.fallback.osrm-base-url:https://routing.openstreetmap.de}") String osrmBaseUrl) {
        this.addresses = addresses;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.orsBaseUrl = orsBaseUrl.replaceAll("/+$", "");
        this.orsApiKey = orsApiKey;
        this.fallbackEnabled = fallbackEnabled;
        this.osrmBaseUrl = osrmBaseUrl.replaceAll("/+$", "");
    }

    /** Raccourci sans repli (tests) : comportement « OpenRouteService seul ». */
    public RouteController(AddressService addresses, ObjectMapper json, String orsBaseUrl, String orsApiKey) {
        this(addresses, json, orsBaseUrl, orsApiKey, false, "http://127.0.0.1:1");
    }

    /**
     * coordinates : liste de points [latitude, longitude] prête pour Leaflet.
     * provider : « openrouteservice » ou « osrm » (service réellement utilisé).
     */
    public record RouteResponse(String profile, double distanceMeters, double durationSeconds,
                                List<double[]> coordinates, String provider) {
    }

    @GetMapping("/api/v1/routes")
    @Operation(summary = "Itinéraire vers une adresse publique (OpenRouteService, ou OSRM sans clé)",
            description = "503 si aucun service n'est disponible ; 429 si le quota est atteint ; "
                    + "422 si aucun itinéraire n'existe ; 502/504 si le service est indisponible.")
    public RouteResponse route(
            HttpServletRequest httpRequest,
            @RequestParam double fromLat,
            @RequestParam double fromLng,
            @Parameter(description = "Code de l'adresse publique de destination") @RequestParam String toCode,
            @RequestParam(defaultValue = "foot-walking") String profile
    ) {
        if (fromLat < -90 || fromLat > 90 || fromLng < -180 || fromLng > 180) {
            throw new FieldValidationException(Map.of("from", "Position de départ invalide."));
        }
        if (!PROFILES.contains(profile)) {
            throw new FieldValidationException(Map.of("profile", "Mode accepté : " + String.join(", ", PROFILES) + "."));
        }
        Address target = addresses.findValidatedPublicAddress(toCode);
        double toLat = target.getLatitude().doubleValue();
        double toLng = target.getLongitude().doubleValue();
        if (orsApiKey.isBlank() && !fallbackEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Calcul d'itinéraire indisponible : OpenRouteService n'est pas configuré sur ce serveur.");
        }
        if (!limiter.tryAcquire(httpRequest.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trop de demandes d'itinéraire. Patientez une minute.");
        }
        return orsApiKey.isBlank()
                ? osrm(profile, fromLat, fromLng, toLat, toLng)
                : ors(profile, fromLat, fromLng, toLat, toLng);
    }

    private RouteResponse ors(String profile, double fromLat, double fromLng, double toLat, double toLng) {
        String url = String.format(Locale.ROOT, "%s/v2/directions/%s?start=%.6f,%.6f&end=%.6f,%.6f",
                orsBaseUrl, profile, fromLng, fromLat, toLng, toLat);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", orsApiKey)
                .header("Accept", "application/geo+json, application/json")
                .GET().build(), "OpenRouteService");
        return switch (response.statusCode()) {
            case 200 -> parseOrs(profile, response.body());
            case 429 -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Quota OpenRouteService atteint. Réessayez plus tard.");
            case 400, 404 -> throw noRoute();
            case 401, 403 -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Calcul d'itinéraire indisponible : clé OpenRouteService refusée.");
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OpenRouteService a renvoyé une erreur (HTTP " + response.statusCode() + ").");
        };
    }

    private RouteResponse osrm(String profile, double fromLat, double fromLng, double toLat, double toLng) {
        String osrmProfile = OSRM_PROFILES.get(profile);
        String url = String.format(Locale.ROOT, "%s/routed-%s/route/v1/%s/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                osrmBaseUrl, osrmProfile, osrmProfile, fromLng, fromLat, toLng, toLat);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                // Politique d'usage des serveurs communautaires : identifier l'application.
                .header("User-Agent", "SamaAdresse/1.0 (projet etudiant UASZ)")
                .GET().build(), "Le service d'itinéraire");
        return switch (response.statusCode()) {
            case 200 -> parseOsrm(profile, response.body());
            case 429 -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Service d'itinéraire saturé. Réessayez dans quelques instants.");
            case 400 -> throw noRoute();
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le service d'itinéraire a renvoyé une erreur (HTTP " + response.statusCode() + ").");
        };
    }

    private HttpResponse<String> send(HttpRequest request, String serviceName) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, serviceName + " ne répond pas. Réessayez plus tard.");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, serviceName + " est injoignable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Calcul interrompu.");
        }
    }

    private RouteResponse parseOrs(String profile, String body) {
        JsonNode feature = read(body).path("features").path(0);
        JsonNode summary = feature.path("properties").path("summary");
        return build(profile, summary.path("distance").asDouble(), summary.path("duration").asDouble(),
                feature.path("geometry").path("coordinates"), "openrouteservice");
    }

    private RouteResponse parseOsrm(String profile, String body) {
        JsonNode root = read(body);
        if (!"Ok".equals(root.path("code").asText())) {
            throw noRoute();
        }
        JsonNode route = root.path("routes").path(0);
        return build(profile, route.path("distance").asDouble(), route.path("duration").asDouble(),
                route.path("geometry").path("coordinates"), "osrm");
    }

    private RouteResponse build(String profile, double distance, double duration, JsonNode coordinates, String provider) {
        List<double[]> points = new ArrayList<>();
        for (JsonNode point : coordinates) {
            // GeoJSON : [longitude, latitude] → Leaflet : [latitude, longitude].
            points.add(new double[] {point.get(1).asDouble(), point.get(0).asDouble()});
        }
        if (points.isEmpty()) {
            throw noRoute();
        }
        return new RouteResponse(profile, distance, duration, points, provider);
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Réponse du service d'itinéraire illisible.");
        }
    }

    private static ResponseStatusException noRoute() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Aucun itinéraire trouvé entre votre position et cette adresse.");
    }
}
