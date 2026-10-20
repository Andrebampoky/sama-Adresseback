package sn.samaadresse.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limiteur de débit simple à fenêtre fixe (par clé : utilisateur, adresse IP…).
 * Limite connue : en mémoire, propre à une instance, remis à zéro au redémarrage.
 */
public class RateLimiter {
    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    public RateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /** Consomme une unité ; renvoie faux si la limite de la fenêtre courante est atteinte. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || !current.start.plus(window).isAfter(now)) {
                return new Window(now, 1);
            }
            return new Window(current.start, current.count + 1);
        });
        return updated.count <= limit;
    }

    private record Window(Instant start, int count) {
    }
}
