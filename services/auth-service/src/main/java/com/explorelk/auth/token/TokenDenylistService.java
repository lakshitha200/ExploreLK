package com.explorelk.auth.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Makes logout able to kill an access token before it expires.
 *
 * <p>A JWT is valid because its signature is valid — the server holds no state that
 * could take it back. So logout records the token's {@code jti} in Redis, and the
 * decoder rejects anything listed.
 *
 * <p>Each entry expires exactly when the token would have anyway, so the list stays
 * proportional to logouts-in-the-last-15-minutes rather than growing forever.
 *
 * <p><b>Failure behaviour.</b> If Redis is unreachable, this reports "not denied" and
 * logs a warning. That is the deliberate trade: a Redis outage must not lock every
 * user out of the platform, and the exposure is bounded by the access token TTL —
 * refresh tokens live in Postgres and are still revoked properly. If that trade is
 * ever unacceptable, this is the method to change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenDenylistService {

    private static final String KEY_PREFIX = "jwt:denylist:";

    private final StringRedisTemplate redis;

    /**
     * Denies a token for whatever remains of its lifetime.
     *
     * @param jti      the token's unique id
     * @param expiresAt the token's own expiry — the TTL is derived from it
     */
    public void deny(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            // Already expired; the decoder rejects it on exp alone.
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } catch (Exception e) {
            // Logout still succeeded in the sense that matters most — the refresh
            // token is revoked in Postgres, so no new access tokens can be minted.
            log.warn("Redis unavailable; access token {} stays valid until it expires", shorten(jti), e);
        }
    }

    public boolean isDenied(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("Redis unavailable during denylist check; allowing the request", e);
            return false;
        }
    }

    /** Never log a full jti — it identifies a live session. */
    private static String shorten(String jti) {
        return jti == null ? "(none)" : jti.substring(0, Math.min(8, jti.length())) + "...";
    }
}
