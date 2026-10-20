package sn.samaadresse.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limite les essais de mot de passe par e-mail pour freiner la force brute.
 *
 * Limite connue : l'état est en mémoire, propre à une instance et perdu au redémarrage.
 * Une infrastructure à plusieurs instances devra utiliser un stockage partagé ou un proxy limitant le débit.
 */
@Component
public class LoginAttemptLimiter {
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptLimiter(
            @Value("${app.security.login.max-failures:5}") int maxFailures,
            @Value("${app.security.login.lock-duration:PT15M}") Duration window
    ) {
        this(maxFailures, window, Clock.systemUTC());
    }

    LoginAttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public boolean isBlocked(String key) {
        Attempts current = attempts.get(key);
        if (current == null) {
            return false;
        }
        if (current.since.plus(window).isBefore(clock.instant())) {
            attempts.remove(key, current);
            return false;
        }
        return current.count >= maxFailures;
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (k, current) -> {
            if (current == null || current.since.plus(window).isBefore(now)) {
                return new Attempts(1, now);
            }
            return new Attempts(current.count + 1, current.since);
        });
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    private record Attempts(int count, Instant since) {
    }
}
