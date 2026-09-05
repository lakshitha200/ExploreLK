package com.explorelk.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code explorelk.rate-limit.*} — the numbers from §8 of the design, in one
 * place so they can be tuned per environment and turned off in tests that are
 * not about rate limiting.
 *
 * @param enabled          false skips every check. Integration tests that hammer
 *                         login with the same fixture would otherwise trip the
 *                         limiter and fail for the wrong reason
 * @param loginPerIp       attempts allowed from one IP inside {@code loginWindow}
 * @param loginWindow      the window for the per-IP login limit
 * @param maxFailedLogins  consecutive failures before the account itself locks
 * @param lockDuration     how long that lock lasts
 * @param emailRequests    forgot-password and resend-verification requests
 *                         allowed per address inside {@code emailWindow}
 * @param emailWindow      the window for both email-triggered limits
 */
@ConfigurationProperties(prefix = "explorelk.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int loginPerIp,
        Duration loginWindow,
        int maxFailedLogins,
        Duration lockDuration,
        int emailRequests,
        Duration emailWindow
) {
}
