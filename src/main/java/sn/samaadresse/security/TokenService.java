package sn.samaadresse.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import sn.samaadresse.user.domain.UserAccount;

@Service
public class TokenService {
    public static final String ISSUER = "samaadresse-api";

    private final JwtEncoder encoder;
    private final Duration validity;

    public TokenService(JwtEncoder encoder, @Value("${app.security.jwt.validity:PT1H}") Duration validity) {
        this.encoder = encoder;
        this.validity = validity;
    }

    public IssuedToken issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(validity);
        // Le jeton ne contient que l'identifiant interne : le rôle et l'état du compte
        // sont relus en base à chaque requête pour qu'une désactivation prenne effet immédiatement.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
