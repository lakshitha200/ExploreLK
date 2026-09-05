package com.explorelk.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code explorelk.rate-limit.*} — the front-door limit.
 *
 * <p>Deliberately looser than the Auth Service's per-endpoint limits. This one
 * counts every request a caller makes, including browsing the catalog, so a
 * number tight enough for login attempts would break an ordinary user paging
 * through destinations.
 *
 * @param enabled           false skips the check entirely. For a local load test,
 *                          never for anything reachable from elsewhere
 * @param requestsPerWindow requests allowed from one address per window
 * @param window            the fixed window
 */
@ConfigurationProperties(prefix = "explorelk.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int requestsPerWindow,
        Duration window
) {
}
