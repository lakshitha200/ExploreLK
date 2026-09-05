package com.explorelk.gateway;

import com.explorelk.gateway.support.StubAuthServer;
import com.explorelk.gateway.support.StubService;
import com.explorelk.gateway.support.TestRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The front-door limit, in a context of its own.
 *
 * <p>It needs its own limit, and that is the point of the separate class: every
 * request in the whole suite arrives from 127.0.0.1 and shares one bucket, so
 * the shared test profile raises the ceiling out of the way and this class
 * lowers it to something it can actually exceed in a handful of calls.
 *
 * <p>What is being defended is the platform rather than any one account. The
 * Auth Service's own limiter stops a password list; this stops a caller that
 * never touches login from flattening the catalog.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class RateLimitIT extends TestRedis {

    private static final int LIMIT = 5;

    private static final StubService AUTH = new StubService("auth-service");
    private static final StubService DESTINATION = new StubService("destination-service");

    @DynamicPropertySource
    static void tightLimit(DynamicPropertyRegistry registry) {
        registry.add("explorelk.rate-limit.requests-per-window", () -> LIMIT);
        registry.add("explorelk.gateway.auth-service-uri", AUTH::uri);
        registry.add("explorelk.gateway.destination-service-uri", DESTINATION::uri);
        registry.add("explorelk.auth.jwks-uri", StubAuthServer::jwksUri);
        registry.add("explorelk.auth.issuer", () -> StubAuthServer.ISSUER);
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void clearStubs() {
        AUTH.clear();
        DESTINATION.clear();
    }

    @Test
    @DisplayName("past the limit is a 429 with a Retry-After, and the service never sees it")
    void burstIsStoppedAtTheEdge() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(get("/api/v1/destinations").getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> limited = get("/api/v1/destinations");

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).contains("RATE_LIMITED");
        // Without this a client either gives up or retries immediately, and the
        // immediate retry is what turns a limit into a hot loop.
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();

        // The whole point of limiting at the edge: the sixth request cost the
        // catalog nothing — no connection, no query, no thread.
        assertThat(DESTINATION.requestCount()).isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("the limit counts every path together, not one bucket per endpoint")
    void oneBucketCoversThePlatform() {
        // A caller that spreads a burst across endpoints is still one caller
        // making too many requests. Per-path buckets would multiply the limit by
        // however many endpoints the platform happens to have.
        get("/api/v1/destinations");
        get("/api/v1/categories");
        get("/api/v1/auth/login");
        get("/api/v1/attractions/abc");
        get("/api/v1/destinations/ella");

        assertThat(get("/api/v1/categories").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a rate-limited response still carries its request id")
    void rejectionIsTraceable() {
        for (int i = 0; i <= LIMIT; i++) {
            get("/api/v1/destinations");
        }

        ResponseEntity<String> limited = get("/api/v1/destinations");

        // A rejected call is exactly the one a user complains about, so it has
        // to be as traceable as a successful one.
        assertThat(limited.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    @DisplayName("health probes are never rate limited")
    void probesAreExempt() {
        for (int i = 0; i <= LIMIT * 2; i++) {
            get("/actuator/health");
        }

        // Being under load is precisely when somebody needs to ask whether the
        // gateway is alive. A 429 to an orchestrator's probe gets the container
        // restarted for succeeding at its job.
        assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(String path) {
        RestTemplate lenient = new RestTemplate();
        lenient.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return lenient.getForEntity(URI.create("http://localhost:" + port + path), String.class);
    }
}
