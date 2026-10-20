package sn.samaadresse.security;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

/**
 * Transforme un jeton valide en authentification Spring en relisant le compte en base.
 * Un compte supprimé ou désactivé est refusé même si son jeton n'a pas encore expiré.
 */
@Component
public class AccountJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserAccountRepository users;

    public AccountJwtConverter(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Jeton invalide.");
        }
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new InvalidBearerTokenException("Compte introuvable."));
        if (!user.isEnabled()) {
            throw new DisabledException("Compte désactivé.");
        }
        return new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                user.getId().toString()
        );
    }
}
