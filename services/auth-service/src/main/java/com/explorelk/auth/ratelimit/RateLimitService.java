package com.explorelk.auth.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Counters in Redis: {@code INCR} plus {@code EXPIRE}, and nothing more.
 *
 * <p>A token bucket would give smoother behaviour, but a fixed window of
 * {@code INCR}/{@code EXPIRE} is two commands anyone can read in {@code
 * redis-cli}, and its worst case — twice the limit across a window boundary —
 * does not matter for the thing this protects against, which is somebody
 * hammering login with a password list.
 *
 * <p><strong>It fails open, on purpose.</strong> If Redis is unreachable, every
 * check returns "allowed" and logs a warning. The alternative is that a cache
 * outage stops anyone logging in — turning a degraded dependency into a total
 * one, on the service every other service depends on. Brute-force protection
 * does not disappear when that happens: the per-account lockout in
 * {@link LoginAttemptService} lives in Postgres precisely so the important half
 * survives a Redis outage.
 *
 * <p>Keys follow §8 of the design: {@code rl:login:{ip}},
 * {@code rl:pwreset:{email}}, {@code rl:verify:{email}}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final StringRedisTemplate redis;

    /**
     * Counts one attempt and says whether it is allowed.
     *
     * <p>The TTL is set only on the first hit of a window. Re-setting it on
     * every call would slide the window forward with each request, so a steady
     * stream of attempts would keep the key alive and the limit would never
     * reset — a subtle way to lock somebody out permanently.
     */
    public Decision check(String key, int limit, Duration window) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return Decision.pass();
            }
            if (count == 1L) {
                redis.expire(key, window);
            }
            if (count > limit) {
                Long ttl = redis.getExpire(key);
                // A key with no TTL would never let the caller back in. Treat a
                // missing expiry as the full window rather than as "forever".
                long retryAfter = (ttl == null || ttl < 0) ? window.getSeconds() : ttl;
                return Decision.limited(retryAfter);
            }
            return Decision.pass();

        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request for key {}: {}", key, e.getMessage());
            return Decision.pass();
        }
    }

    /** Clears a counter — used when a login finally succeeds. */
    public void reset(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not reset rate limit key {}: {}", key, e.getMessage());
        }
    }

    /**
     * @param allowed         whether the caller may proceed
     * @param retryAfterSeconds what to put in the {@code Retry-After} header; a
     *                          429 without it tells a client to guess, and
     *                          clients guess badly
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        // Named `pass`, not `allowed`: a static factory cannot share a name with
        // the record's own accessor.
        static Decision pass() {
            return new Decision(true, 0);
        }

        static Decision limited(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}
