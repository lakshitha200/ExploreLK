package com.explorelk.gateway.security;

import com.explorelk.gateway.common.ApiError;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * The platform's front door limit: how much any one caller may ask for.
 *
 * <p>This is not a second copy of the Auth Service's limiter. That one protects
 * <em>accounts</em> — five wrong passwords locks one login. This one protects
 * the <em>platform</em>: it counts every request from an address, whatever it is
 * for, and stops a burst before it costs a downstream service a database
 * connection. A caller that never touches login can still flatten the catalog
 * without it.
 *
 * <p><strong>It fails open.</strong> If Redis is unreachable the request is
 * allowed and a warning is logged. A gateway that refuses everything when its
 * cache is down is a single point of failure for the whole platform, which is
 * exactly what a gateway must not be. The services behind it keep their own
 * limits for the endpoints that genuinely need one.
 *
 * <p>Counting is a fixed window of {@code INCR} plus {@code EXPIRE}, in Redis so
 * that two gateway instances share one count and a deploy does not reset it.
 * The worst case — twice the limit across a window boundary — is irrelevant at
 * the scale this defends against.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Probes must answer while the platform is under load — that is when
        // somebody most needs to know whether it is alive.
        return !properties.enabled()
                || path.startsWith("/actuator/health")
                || path.startsWith("/fallback/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String ip = clientIp(request);
        String key = "gw:rl:" + ip;

        long retryAfter = check(key, properties.requestsPerWindow(), properties.window());
        if (retryAfter < 0) {
            chain.doFilter(request, response);
            return;
        }

        log.info("Rate limited {} {} from {}", request.getMethod(), request.getRequestURI(), ip);
        writeTooManyRequests(request, response, retryAfter);
    }

    /** @return seconds to wait, or -1 when the request is allowed. */
    private long check(String key, int limit, Duration window) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return -1;
            }
            if (count == 1L) {
                // Only on the first hit. Re-setting it every time would slide the
                // window forward, so a steady stream of requests would keep the
                // key alive and the limit would never reset.
                redis.expire(key, window);
            }
            if (count <= limit) {
                return -1;
            }
            Long ttl = redis.getExpire(key);
            return (ttl == null || ttl < 0) ? window.getSeconds() : ttl;

        } catch (RuntimeException e) {
            log.warn("Gateway rate limiter unavailable, allowing request: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * <p>{@link IdentityHeaderFilter} has already stripped any inbound
     * {@code X-Forwarded-For}, so this is the real socket address rather than
     * whatever the caller claimed. That ordering is what stops a client picking
     * its own bucket — and picking somebody else's to poison.
     *
     * <p>If a load balancer is ever put in front of this gateway, that hop
     * becomes the only trusted source of the client address and this method has
     * to read the header the balancer sets. Until then, trusting the socket is
     * both correct and the safer default.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Without this a client either gives up or retries immediately, and the
        // immediate retry is what turns a limit into a hot loop.
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        String traceId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                "RATE_LIMITED", "Too many requests. Slow down", request.getRequestURI(), traceId));
    }
}
