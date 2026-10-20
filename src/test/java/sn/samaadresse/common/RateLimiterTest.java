package sn.samaadresse.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {
    /** Horloge réglable pour tester l'expiration de la fenêtre sans attendre. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void blocksAfterLimitThenResetsAfterWindow() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), clock);
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
        clock.now = clock.now.plusSeconds(61);
        assertThat(limiter.tryAcquire("a")).isTrue();
    }
}
