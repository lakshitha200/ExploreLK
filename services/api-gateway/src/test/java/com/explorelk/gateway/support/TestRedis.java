package com.explorelk.gateway.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis, started once and shared by the whole suite.
 *
 * <p>A real one rather than an embedded fake: the rate limiter's correctness is
 * entirely about {@code INCR} and {@code EXPIRE} semantics, and a stub that
 * implements them approximately would pass the tests while the real thing
 * behaved differently.
 *
 * <p><strong>Flushed after every test.</strong> Every request in this suite
 * arrives from 127.0.0.1, so all of them share one rate-limit key — without the
 * flush, a class with thirty routing tests would exhaust the limit partway
 * through and the rest would fail as 429s for a reason unrelated to what they
 * assert.
 */
public abstract class TestRedis {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void flushRateLimitCounters() {
        try {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (RuntimeException e) {
            // A test that deliberately breaks Redis must still be able to finish.
        }
    }
}
