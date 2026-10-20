package sn.samaadresse.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Choix du fournisseur par APP_SMS_PROVIDER : « twilio », « simulation » ou vide (aucun : l'envoi
 * renvoie 503 et l'interface propose le lien sms: de l'appareil).
 */
@Configuration
public class SmsConfig {
    private static final Logger log = LoggerFactory.getLogger(SmsConfig.class);

    @Bean
    SmsGateway smsGateway(
            ObjectMapper json,
            @Value("${app.sms.provider:}") String provider,
            @Value("${app.sms.twilio.base-url:https://api.twilio.com}") String twilioBaseUrl,
            @Value("${app.sms.twilio.account-sid:}") String accountSid,
            @Value("${app.sms.twilio.auth-token:}") String authToken,
            @Value("${app.sms.twilio.from:}") String from
    ) {
        switch (provider.trim().toLowerCase()) {
            case "twilio" -> {
                if (accountSid.isBlank() || authToken.isBlank() || from.isBlank()) {
                    throw new IllegalStateException(
                            "APP_SMS_PROVIDER=twilio exige TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN et TWILIO_FROM.");
                }
                log.info("Envoi de SMS : Twilio.");
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                return new SmsGateway(Optional.of(new TwilioSmsProvider(http, json, twilioBaseUrl, accountSid, authToken, from)));
            }
            case "simulation" -> {
                log.warn("Envoi de SMS en SIMULATION : aucun SMS réel ne sera envoyé.");
                return new SmsGateway(Optional.of(new SimulatedSmsProvider()));
            }
            case "" -> {
                log.info("Aucun fournisseur SMS configuré : l'envoi automatique est désactivé.");
                return new SmsGateway(Optional.empty());
            }
            default -> throw new IllegalStateException("APP_SMS_PROVIDER inconnu : " + provider);
        }
    }
}
