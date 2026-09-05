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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What a caller gets when a service behind the gateway is broken — and, just as
 * importantly, what the <em>other</em> service's callers get at the same time.
 *
 * <p>The failure this prevents is the one that makes gateways dangerous: without
 * a breaker, every request to a dead service occupies a thread until it times
 * out, the pool fills with calls waiting on the same host, and endpoints with
 * nothing to do with it stop answering too. One broken service becomes a broken
 * platform.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class ResilienceIT extends TestRedis {

    private static final StubService AUTH = new StubService("auth-service");
    private static final StubService DESTINATION = new StubService("destination-service");

    @DynamicPropertySource
    static void downstreamServices(DynamicPropertyRegistry registry) {
        registry.add("explorelk.gateway.auth-service-uri", AUTH::uri);
        registry.add("explorelk.gateway.destination-service-uri", DESTINATION::uri);
        registry.add("explorelk.auth.jwks-uri", StubAuthServer::jwksUri);
        registry.add("explorelk.auth.issuer", () -> StubAuthServer.ISSUER);
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void healthyStubs() {
        AUTH.clear();
        AUTH.respondWith(200);
        DESTINATION.clear();
        DESTINATION.respondWith(200);
    }

    @Test
    @DisplayName("an application 500 is passed through, not turned into an outage")
    void applicationErrorsAreNotCircuitFailures() {
        DESTINATION.respondWith(500);

        for (int i = 0; i < 6; i++) {
            assertThat(get("/api/v1/destinations").getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // A 500 is a bug in one handler, not evidence the service is down.
        // Tripping on it would let a single broken endpoint return 503 for every
        // other endpoint of a mostly-healthy service — one bug becoming an
        // outage, which is the opposite of what a breaker is for. So the calls
        // keep going through, and the client keeps getting the real error.
        assertThat(DESTINATION.requestCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("a service saying it cannot serve becomes a 503 with a Retry-After")
    void failuresBecomeServiceUnavailable() {
        DESTINATION.respondWith(503);

        // The test profile trips the breaker in a couple of calls; production
        // needs ten. Either way the client eventually gets a 503.
        ResponseEntity<String> response = null;
        for (int i = 0; i < 6; i++) {
            response = get("/api/v1/destinations");
        }

        // 500 says "this request is broken, do not repeat it"; 503 says "this
        // service is away, try again shortly". A client that retries a 500 is
        // wrong, and one that gives up on a 503 loses a request that would have
        // worked a second later.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("SERVICE_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst("Retry-After")).isNotBlank();
        // The service name is not in the body. A client cannot act on which
        // internal component failed, and publishing the topology is free
        // reconnaissance.
        assertThat(response.getBody()).doesNotContain("destination-service");
    }

    @Test
    @DisplayName("an open circuit stops calling the broken service at all")
    void openCircuitShedsLoad() {
        DESTINATION.respondWith(503);

        for (int i = 0; i < 6; i++) {
            get("/api/v1/destinations");
        }
        int callsBefore = DESTINATION.requestCount();

        for (int i = 0; i < 5; i++) {
            get("/api/v1/destinations");
        }

        // Shedding is the point. A service that is failing needs fewer requests
        // to recover, not the same number plus retries — and the caller is told
        // immediately instead of waiting for another timeout.
        assertThat(DESTINATION.requestCount())
                .as("the breaker should be open and calling through rarely, if at all")
                .isLessThan(callsBefore + 5);
    }

    @Test
    @DisplayName("one broken service does not take the other down with it")
    void breakersAreIndependent() {
        DESTINATION.respondWith(503);

        for (int i = 0; i < 6; i++) {
            get("/api/v1/destinations");
        }

        // The whole reason the breakers are named per service. A traveler cannot
        // browse the catalog right now, but they can still sign in — and a
        // platform where a catalog bug stops authentication is a platform where
        // every outage is a total outage.
        assertThat(get("/api/v1/auth/login").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(AUTH.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the circuit closes again once the service recovers")
    void circuitRecovers() {
        DESTINATION.respondWith(503);
        for (int i = 0; i < 6; i++) {
            get("/api/v1/destinations");
        }
        assertThat(get("/api/v1/destinations").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        DESTINATION.respondWith(200);

        // Half-open after the wait, then closed on success. Without this the
        // breaker would need a deploy to reset, and a two-second blip would
        // become an outage lasting until somebody noticed.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(get("/api/v1/destinations").getStatusCode()).isEqualTo(HttpStatus.OK));
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
