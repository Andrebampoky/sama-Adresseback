package sn.samaadresse.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.security.TokenService;
import sn.samaadresse.security.TokenService.IssuedToken;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

@Service
public class AuthService {
    /** BCrypt ignore tout ce qui dépasse 72 octets : on refuse plutôt que de tronquer silencieusement. */
    static final int MAX_PASSWORD_BYTES = 72;
    private static final String INVALID_CREDENTIALS = "E-mail ou mot de passe incorrect.";

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final LoginAttemptLimiter limiter;
    private final String dummyHash;

    public AuthService(
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            TokenService tokens,
            LoginAttemptLimiter limiter
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.limiter = limiter;
        // Empreinte de comparaison pour les e-mails inconnus : la réponse prend un temps similaire,
        // ce qui évite de deviner quels e-mails possèdent un compte.
        this.dummyHash = passwordEncoder.encode("samaadresse-compte-inexistant");
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public AuthResult register(String rawEmail, String password, String displayName) {
        String email = normalizeEmail(rawEmail);
        checkPasswordStrength(password);
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un compte existe déjà avec cet e-mail.");
        }
        UserAccount user;
        try {
            user = users.saveAndFlush(new UserAccount(email, passwordEncoder.encode(password), displayName.trim()));
        } catch (DataIntegrityViolationException e) {
            // Deux inscriptions simultanées : la contrainte UNIQUE de la base tranche.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un compte existe déjà avec cet e-mail.");
        }
        return new AuthResult(user, tokens.issue(user));
    }

    @Transactional(readOnly = true)
    public AuthResult login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (limiter.isBlocked(email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Trop de tentatives. Réessayez dans quelques minutes.");
        }
        UserAccount user = users.findByEmail(email).orElse(null);
        boolean matches = passwordEncoder.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches || !user.isEnabled()) {
            limiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        limiter.reset(email);
        return new AuthResult(user, tokens.issue(user));
    }

    @Transactional(readOnly = true)
    public UserAccount currentUser(java.util.UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Compte introuvable."));
    }

    private static void checkPasswordStrength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le mot de passe est trop long.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le mot de passe doit contenir au moins une lettre et un chiffre.");
        }
    }

    public record AuthResult(UserAccount user, IssuedToken token) {
    }
}
