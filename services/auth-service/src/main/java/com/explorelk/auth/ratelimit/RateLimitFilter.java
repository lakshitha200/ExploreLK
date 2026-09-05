package com.explorelk.auth.ratelimit;

import com.explorelk.auth.common.ApiError;
import com.explorelk.auth.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Per-IP limiting on the endpoints that can be attacked without an account.
 *
 * <p>A filter rather than an annotation on the controller, because it has to run
 * <em>before</em> the request costs anything: BCrypt at strength 12 is about a
 * quarter of a second of CPU by design, so an unthrottled login endpoint is a
 * denial-of-service amplifier — a few hundred requests a second saturate the
 * machine whether or not any password is correct.
 *
 * <p>Only the unauthenticated paths are covered. Everything else already needs a
 * valid token, which is its own limit on who can ask.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * The paths an attacker can reach with no credentials at all. {@code
     * /refresh} is included because a stolen refresh token is worth guessing
     * against, and {@code /register} because it is the cheapest way to enumerate
     * or to fill the users table.
     */
    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/reset-password");

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        RateLimitService.Decision decision = rateLimitService.check(
                "rl:login:" + clientIp(request),
                properties.loginPerIp(),
                properties.loginWindow());

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        log.info("Rate limited {} from {}", request.getRequestURI(), clientIp(request));
        writeTooManyRequests(request, response, decision.retryAfterSeconds());
    }

    /**
     * <p><strong>{@code X-Forwarded-For} is trusted here, and that is only safe
     * behind a proxy that overwrites it.</strong> Behind the API gateway the
     * socket address is the gateway's, so limiting on it would throttle every
     * traveler as one client. Exposed directly to the internet the opposite is
     * true: a caller can put whatever they like in the header and get a fresh
     * bucket per request. The deployment has to put this service behind a proxy
     * that sets the header itself — the same requirement every rate limiter in
     * front of a load balancer has.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // The first entry is the original client; the rest are proxies.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {

        response.setStatus(ErrorCode.RATE_LIMITED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Without this a client either gives up or retries immediately, and the
        // immediate retry is what turns a limit into a hot loop.
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        ApiError body = new ApiError(
                ErrorCode.RATE_LIMITED.name(),
                ErrorCode.RATE_LIMITED.defaultMessage(),
                Instant.now(),
                request.getRequestURI(),
                "rl-" + Long.toHexString(System.nanoTime()),
                List.of());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
