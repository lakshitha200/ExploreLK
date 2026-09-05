package com.explorelk.auth.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The counter logic, without a Redis to run it against.
 *
 * <p>Three behaviours are worth pinning down here rather than in an integration
 * test: that the window is fixed rather than sliding, that exceeding the limit
 * reports how long to wait, and above all that an unreachable Redis lets the
 * request through.
 */
class RateLimitServiceTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new RateLimitService(redis);
    }

    @Test
    @DisplayName("under the limit is allowed")
    void underTheLimitPasses() {
        when(values.increment("rl:login:1.2.3.4")).thenReturn(3L);

        assertThat(service.check("rl:login:1.2.3.4", 10, WINDOW).allowed()).isTrue();
    }

    @Test
    @DisplayName("the TTL is set on the first hit only")
    void windowIsFixedNotSliding() {
        when(values.increment(anyString())).thenReturn(1L);
        service.check("rl:login:1.2.3.4", 10, WINDOW);
        verify(redis, times(1)).expire("rl:login:1.2.3.4", WINDOW);

        // Re-setting the expiry on every call would slide the window forward, so
        // a steady stream of attempts would keep the key alive forever and the
        // limit would never reset — a permanent lockout by accident.
        when(values.increment(anyString())).thenReturn(2L);
        service.check("rl:login:1.2.3.4", 10, WINDOW);
        verify(redis, times(1)).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("over the limit reports how long to wait")
    void overTheLimitReportsRetryAfter() {
        when(values.increment(anyString())).thenReturn(11L);
        when(redis.getExpire(anyString())).thenReturn(42L);

        RateLimitService.Decision decision = service.check("rl:login:1.2.3.4", 10, WINDOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(42L);
    }

    @Test
    @DisplayName("a key with no expiry is treated as a full window, not as forever")
    void missingTtlFallsBackToTheWindow() {
        when(values.increment(anyString())).thenReturn(11L);
        // -1 means "no TTL" in Redis. Passing that through as a Retry-After
        // would tell the client to come back in negative one second.
        when(redis.getExpire(anyString())).thenReturn(-1L);

        assertThat(service.check("rl:login:1.2.3.4", 10, WINDOW).retryAfterSeconds())
                .isEqualTo(WINDOW.getSeconds());
    }

    @Test
    @DisplayName("an unreachable Redis fails OPEN")
    void redisOutageAllowsTheRequest() {
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        // The alternative is that a cache outage stops anyone logging in —
        // turning a degraded dependency into a total one, on the service every
        // other service depends on. Brute force is still covered, because the
        // per-account lockout lives in Postgres.
        assertThat(service.check("rl:login:1.2.3.4", 10, WINDOW).allowed()).isTrue();
    }

    @Test
    @DisplayName("a failed reset is not an error the caller has to handle")
    void resetSwallowsFailures() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        // reset() is called after a successful login. Throwing here would fail
        // the login that just succeeded, for a bookkeeping problem.
        service.reset("rl:login:1.2.3.4");
        verify(redis).delete("rl:login:1.2.3.4");
    }

    @Test
    @DisplayName("a null count from Redis is allowed rather than a NullPointerException")
    void nullCountPasses() {
        when(values.increment(anyString())).thenReturn(null);

        assertThat(service.check("rl:login:1.2.3.4", 10, WINDOW).allowed()).isTrue();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
