package sn.samaadresse.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;

public final class CurrentUser {
    private CurrentUser() {
    }

    /** Identifiant interne du compte authentifié (sujet du jeton, vérifié par AccountJwtConverter). */
    public static UUID id(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
