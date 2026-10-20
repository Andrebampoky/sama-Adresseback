package sn.samaadresse.security;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fournit la clé HMAC qui signe les jetons.
 *
 * La clé vient de APP_JWT_SECRET (Base64, au moins 32 octets). Aucune valeur n'est écrite dans les
 * fichiers versionnés : si la variable est absente et que le mode strict n'est pas activé, une clé
 * aléatoire est générée en mémoire. Les jetons deviennent alors invalides à chaque redémarrage,
 * ce qui est acceptable en développement mais pas en production (APP_JWT_REQUIRE_SECRET=true).
 */
@Component
public class JwtKeyProvider {
    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);
    private static final int MIN_KEY_BYTES = 32;

    private final SecretKey key;

    public JwtKeyProvider(
            @Value("${app.security.jwt.secret:}") String base64Secret,
            @Value("${app.security.jwt.require-secret:false}") boolean requireSecret
    ) {
        byte[] bytes;
        if (base64Secret == null || base64Secret.isBlank()) {
            if (requireSecret) {
                throw new IllegalStateException("APP_JWT_SECRET est obligatoire avec app.security.jwt.require-secret=true.");
            }
            bytes = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            log.warn("APP_JWT_SECRET absent : clé de signature éphémère générée. Les sessions seront perdues au redémarrage.");
        } else {
            try {
                bytes = Base64.getDecoder().decode(base64Secret.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("APP_JWT_SECRET doit être encodé en Base64.", e);
            }
            if (bytes.length < MIN_KEY_BYTES) {
                throw new IllegalStateException("APP_JWT_SECRET doit contenir au moins 32 octets une fois décodé.");
            }
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public SecretKey key() {
        return key;
    }
}
