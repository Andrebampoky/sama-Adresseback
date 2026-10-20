package sn.samaadresse.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/**
 * Envoi via l'API REST de Twilio (Messages). Les identifiants de test Twilio (« test credentials »)
 * permettent un essai sandbox sans frais avec les numéros magiques documentés par Twilio.
 */
public class TwilioSmsProvider implements SmsProvider {
    /** Statuts Twilio signifiant que le message ne partira pas. */
    private static final java.util.Set<String> FAILED_STATUSES = java.util.Set.of("failed", "undelivered", "canceled");
    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String accountSid;
    private final String authToken;
    private final String from;

    public TwilioSmsProvider(HttpClient http, ObjectMapper json, String baseUrl, String accountSid,
                             String authToken, String from) {
        this.http = http;
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.from = from;
    }

    @Override
    public String name() {
        return "twilio";
    }

    @Override
    public SmsResult send(String to, String body) {
        String form = "To=" + encode(to) + "&From=" + encode(from) + "&Body=" + encode(body);
        String credentials = Base64.getEncoder()
                .encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/2010-04-01/Accounts/" + encode(accountSid) + "/Messages.json"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new SmsException(HttpStatus.GATEWAY_TIMEOUT, "Le fournisseur SMS ne répond pas. Réessayez plus tard.");
        } catch (IOException e) {
            throw new SmsException(HttpStatus.BAD_GATEWAY, "Fournisseur SMS injoignable. Réessayez plus tard.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException(HttpStatus.SERVICE_UNAVAILABLE, "Envoi interrompu.");
        }
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            // SENT seulement si Twilio a bien pris le message en charge : identifiant présent et statut non en échec.
            String sid = field(response.body(), "sid");
            String providerStatus = field(response.body(), "status");
            if ("?".equals(sid) || FAILED_STATUSES.contains(providerStatus)) {
                throw new SmsException(HttpStatus.BAD_GATEWAY,
                        "Le fournisseur SMS n'a pas confirmé la prise en charge du message.");
            }
            return new SmsResult(SmsResult.Status.SENT, name(), sid);
        }
        if (response.statusCode() == 429) {
            throw new SmsException(HttpStatus.TOO_MANY_REQUESTS, "Quota du fournisseur SMS atteint. Réessayez plus tard.");
        }
        if (response.statusCode() == 400) {
            // Numéro refusé, non vérifié en compte d'essai, etc. : on transmet le code Twilio, jamais les identifiants.
            throw new SmsException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Le fournisseur SMS a refusé l'envoi (code " + field(response.body(), "code") + ").");
        }
        throw new SmsException(HttpStatus.BAD_GATEWAY, "Le fournisseur SMS a renvoyé une erreur (HTTP "
                + response.statusCode() + ").");
    }

    private String field(String body, String name) {
        try {
            JsonNode node = json.readTree(body).get(name);
            return node == null ? "?" : node.asText();
        } catch (IOException e) {
            return "?";
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
