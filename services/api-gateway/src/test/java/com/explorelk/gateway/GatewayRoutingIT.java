package com.explorelk.gateway;

import com.explorelk.gateway.support.StubAuthServer;
import com.explorelk.gateway.support.StubService;
import com.explorelk.gateway.support.TestRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing, and the rules about who may cross the gateway.
 *
 * <p><strong>A real port, not MockMvc.</strong> MockMvc never leaves the servlet
 * container, and proxying is exactly the part that does. What has to be proven
 * here is what the <em>other end</em> received — the path, the headers, and the
 * absence of the forged ones — which needs a real socket and a real server on
 * the far side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class GatewayRoutingIT extends TestRedis {

    private static final StubService AUTH = new StubService("auth-service");
    private static final StubService DESTINATION = new StubService("destination-service");

    @DynamicPropertySource
    static void downstreamServices(DynamicPropertyRegistry registry) {
        registry.add("explorelk.gateway.auth-service-uri", AUTH::uri);
        registry.add("explorelk.gateway.destination-service-uri", DESTINATION::uri);

        // The throwaway auth server, not a real one. Nothing in this suite reads
        // auth-service's keys or requires it to be running.
        registry.add("explorelk.auth.jwks-uri", StubAuthServer::jwksUri);
        registry.add("explorelk.auth.issuer", () -> StubAuthServer.ISSUER);
    }

    @LocalServerPort
    private int port;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void clearStubs() {
        AUTH.clear();
        DESTINATION.clear();
    }

    // ── Which service gets what ──────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "/api/v1/auth/login,                      auth",
            "/api/v1/users/me,                        auth",
            "/api/v1/super-admin/admins,              auth",
            "/api/v1/admin/users,                     auth",
            "/api/v1/admin/providers/abc/approval,    auth",
            "/.well-known/jwks.json,                  auth",
            "/api/v1/destinations,                    destination",
            "/api/v1/destinations/ella,               destination",
            "/api/v1/attractions/abc,                 destination",
            "/api/v1/categories,                      destination",
            "/api/v1/admin/destinations,              destination",
            "/api/v1/admin/attractions/abc,           destination",
            "/api/v1/admin/categories,                destination"})
    @DisplayName("each path reaches the service that owns it")
    void pathsReachTheRightService(String path, String expected) {
        get(path, StubAuthServer.tokenFor("SUPER_ADMIN"));

        StubService target = expected.equals("auth") ? AUTH : DESTINATION;
        StubService other = expected.equals("auth") ? DESTINATION : AUTH;

        assertThat(target.requestCount())
                .as("%s should have received %s", expected, path)
                .isEqualTo(1);
        assertThat(other.requestCount()).isZero();
    }

    @Test
    @DisplayName("the two halves of /api/v1/admin go to different services")
    void adminPathsAreSplitBetweenServices() {
        // The reason routing cannot be a simple prefix split. Both services
        // already publish endpoints under /api/v1/admin, and a single
        // /api/v1/admin/** route would send half of them to the wrong place.
        String token = StubAuthServer.tokenFor("ADMIN");

        get("/api/v1/admin/users", token);
        get("/api/v1/admin/destinations", token);

        assertThat(AUTH.lastRequest().orElseThrow().path()).isEqualTo("/api/v1/admin/users");
        assertThat(DESTINATION.lastRequest().orElseThrow().path()).isEqualTo("/api/v1/admin/destinations");
    }

    @Test
    @DisplayName("the path and query reach the service exactly as the client sent them")
    void nothingIsRewritten() {
        get("/api/v1/destinations?search=ella&size=5", null);

        StubService.ReceivedRequest request = DESTINATION.lastRequest().orElseThrow();
        // No prefix stripping, no rewriting. A URL in a log line means the same
        // thing on both sides of the gateway, and a developer can bypass the
        // gateway while debugging and get identical behaviour.
        assertThat(request.path()).isEqualTo("/api/v1/destinations");
        assertThat(request.query()).isEqualTo("search=ella&size=5");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/trips",
            "/api/v1/bookings",
            "/internal/metrics",
            "/"})
    @DisplayName("an unrouted path is a 404 here, never a guess")
    void unroutedPathsAre404(String path) {
        // No catch-all, on purpose: a default route is how a typo in one
        // service's path silently starts reaching another, and how a service
        // that was never meant to be public becomes public.
        ResponseEntity<String> response = get(path, StubAuthServer.tokenFor("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(AUTH.requestCount()).isZero();
        assertThat(DESTINATION.requestCount()).isZero();
    }

    @Test
    @DisplayName("an unrouted path without a token is 401, not 404")
    void unroutedPathsDoNotLeakTheirExistence() {
        // Security runs before routing, so an anonymous caller is turned away
        // before the gateway ever considers whether the path exists. That
        // ordering is worth keeping: a 404 here and a 401 there would let anyone
        // map which endpoints the platform has by watching which status comes
        // back. With a token the same path is a plain 404 — see the test above.
        assertThat(get("/api/v1/trips", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Who may cross ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/login",
            "/api/v1/destinations",
            "/api/v1/destinations/ella",
            "/api/v1/categories",
            "/.well-known/jwks.json"})
    @DisplayName("public paths pass through with no token at all")
    void publicPathsNeedNoToken(String path) {
        // Logging in cannot require being logged in, and the catalog is public by
        // design — a traveler browses Sri Lanka without an account.
        assertThat(get(path, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an admin path with no token is rejected before it reaches the service")
    void unauthenticatedAdminIsRejectedAtTheEdge() {
        ResponseEntity<String> response = get("/api/v1/admin/destinations", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The point of checking at the edge: the downstream service never spent
        // anything on this request.
        assertThat(DESTINATION.requestCount()).isZero();
        assertThat(response.getBody()).contains("TOKEN_INVALID");
    }

    @Test
    @DisplayName("a forged token is rejected, not forwarded")
    void forgedTokensAreRejected() {
        ResponseEntity<String> response = get("/api/v1/admin/destinations", "not-a-real-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(DESTINATION.requestCount()).isZero();
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void foreignIssuerIsRejected() {
        // Same signature algorithm, same shape, different `iss`. Without this
        // check a token minted by a development auth service would be accepted
        // by production.
        ResponseEntity<String> response =
                get("/api/v1/admin/destinations", StubAuthServer.tokenFromForeignIssuer("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(DESTINATION.requestCount()).isZero();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokensAreRejected() {
        ResponseEntity<String> response =
                get("/api/v1/admin/destinations", StubAuthServer.expiredToken("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(DESTINATION.requestCount()).isZero();
    }

    @Test
    @DisplayName("the gateway checks that a token is real, not what it may do")
    void roleChecksAreLeftToTheService() {
        // A TRAVELER token on an admin path is forwarded. This looks wrong and
        // is deliberate: the service that owns the data owns the rule, and it
        // will answer 403. A gateway that knew every service's role map would
        // need redeploying whenever any of them changed, and would eventually
        // disagree with the service it was protecting.
        ResponseEntity<String> response =
                get("/api/v1/admin/destinations", StubAuthServer.tokenFor("TRAVELER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(DESTINATION.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the Authorization header survives the trip")
    void authorizationIsForwarded() {
        String token = StubAuthServer.tokenFor("ADMIN");
        get("/api/v1/admin/destinations", token);

        // Without this the service could never authorize anything, and every
        // admin call would 401 at the far end for no visible reason.
        assertThat(DESTINATION.lastRequest().orElseThrow().header(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + token);
    }

    // ── Headers ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("client-supplied identity headers never reach a service")
    void forgedIdentityHeadersAreStripped() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "00000000-0000-0000-0000-000000000001");
        headers.set("X-User-Role", "SUPER_ADMIN");
        headers.set("X-Forwarded-For", "10.9.9.9");

        http.exchange(RequestEntity.method(HttpMethod.GET, uri("/api/v1/destinations"))
                .headers(headers).build(), String.class);

        StubService.ReceivedRequest request = DESTINATION.lastRequest().orElseThrow();

        // If any of these survived, authentication could be bypassed by typing.
        assertThat(request.hasHeader("X-User-Id")).isFalse();
        assertThat(request.hasHeader("X-User-Role")).isFalse();
        // And a caller could pick their own rate-limit bucket — or poison
        // somebody else's — by choosing what address to claim.
        assertThat(request.header("X-Forwarded-For")).isNotEqualTo("10.9.9.9");
    }

    @Test
    @DisplayName("every request gets an id, forwarded inward and returned to the caller")
    void requestIdIsGeneratedAndPropagated() {
        ResponseEntity<String> response = get("/api/v1/destinations", null);

        String returned = response.getHeaders().getFirst("X-Request-Id");
        assertThat(returned).isNotBlank();

        // The same id on both sides is what turns three unrelated log files into
        // one story when a traveler reports a failure.
        assertThat(DESTINATION.lastRequest().orElseThrow().header("X-Request-Id")).isEqualTo(returned);
    }

    @Test
    @DisplayName("a client cannot choose its own request id")
    void clientSuppliedRequestIdIsReplaced() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "chosen-by-me");

        ResponseEntity<String> response = http.exchange(
                RequestEntity.method(HttpMethod.GET, uri("/api/v1/destinations")).headers(headers).build(),
                String.class);

        // Otherwise anyone could collide with — or forge — somebody else's trace.
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotEqualTo("chosen-by-me");
        assertThat(DESTINATION.lastRequest().orElseThrow().header("X-Request-Id")).isNotEqualTo("chosen-by-me");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * A GET that returns the response instead of throwing on 4xx/5xx — most of
     * these tests are about the rejections.
     */
    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (token != null) {
            headers.setBearerAuth(token);
        }

        RestTemplate lenient = new RestTemplate();
        lenient.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });

        return lenient.exchange(
                RequestEntity.method(HttpMethod.GET, uri(path)).headers(headers).build(), String.class);
    }
}
