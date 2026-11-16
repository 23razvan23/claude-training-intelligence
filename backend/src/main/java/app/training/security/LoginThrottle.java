package app.training.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * R-09: in-memory slowdown for guessing passwords. After {@code max} wrong passwords for one
 * username and client address inside the window, further tries are refused until the window
 * (counted from the first failure) ends. A right password clears the count. Lost on restart.
 */
@Component
public class LoginThrottle {
    private record Entry(int failures, long windowStartMs) {}

    private static final int PURGE_ABOVE = 10_000;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final int max;
    private final long windowMs;
    private final Clock clock;

    /**
     * @param max failures allowed before refusing [configurable]. 5: slows guessing, while a coach who mistypes
     *     a few times is not affected.
     * @param window how long a refusal lasts [configurable]. 15 minutes: at most ~5 guesses per 15 minutes, and
     *     the only coach account is never locked out for long.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LoginThrottle(
            @Value("${app.security.login-max-failures:5}") int max,
            @Value("${app.security.login-window:PT15M}") Duration window) {
        this(max, window, Clock.systemUTC());
    }

    LoginThrottle(int max, Duration window, Clock clock) {
        this.max = max;
        this.windowMs = window.toMillis();
        this.clock = clock;
    }

    static String key(String username, String address) {
        return username.toLowerCase(java.util.Locale.ROOT) + "|" + address;
    }

    /** Seconds the caller must wait, or 0 when a try is allowed. */
    public long waitSeconds(String key) {
        Entry e = entries.get(key);
        if (e == null) return 0;
        long left = e.windowStartMs() + windowMs - clock.millis();
        if (left <= 0) {
            entries.remove(key, e);
            return 0;
        }
        return e.failures() >= max ? Math.max(1, (left + 999) / 1000) : 0;
    }

    public void failed(String key) {
        long now = clock.millis();
        if (entries.size() > PURGE_ABOVE) entries.values().removeIf(e -> e.windowStartMs() + windowMs <= now);
        entries.merge(key, new Entry(1, now), (old, ignored) ->
                old.windowStartMs() + windowMs <= now ? new Entry(1, now) : new Entry(old.failures() + 1, old.windowStartMs()));
    }

    public void succeeded(String key) {
        entries.remove(key);
    }
}
