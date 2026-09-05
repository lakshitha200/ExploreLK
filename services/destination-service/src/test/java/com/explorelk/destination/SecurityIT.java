package com.explorelk.destination;

import com.explorelk.destination.support.IntegrationTest;
import com.explorelk.destination.support.StubAuthServer;
import com.explorelk.destination.support.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security boundary, exercised with tokens from a throwaway keypair.
 *
 * <p>Nothing here imports the Auth Service or reads its keys. That is the point:
 * these tests prove this service can verify a token from <em>any</em> issuer
 * publishing a JWKS, rather than proving the two services happen to share a file
 * on disk.
 */
@IntegrationTest
class SecurityIT extends TestContainers {

    @Autowired
    private MockMvc mvc;

    // ── The Step 5 checkpoint, as tests ──────────────────────────────────────

    @Test
    @DisplayName("no token on an admin endpoint is 401")
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/admin/destinations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("a TRAVELER token is 403, not 500")
    void travellerIsForbidden() throws Exception {
        // @PreAuthorize throws inside the MVC dispatch, so without an explicit
        // AccessDeniedException handler the catch-all turns this into a 500 and
        // an authorization failure looks like a server bug.
        mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, bearer("TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("an ADMIN or SUPER_ADMIN token gets through")
    void adminIsAllowed(String role) throws Exception {
        mvc.perform(get("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(role)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a PROVIDER token is not an admin")
    void providerIsForbidden() throws Exception {
        // Every role that is not explicitly allowed must be refused, not just the
        // one the product happens to think about.
        mvc.perform(get("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, bearer("PROVIDER")))
                .andExpect(status().isForbidden());
    }

    // ── Token validation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a token from another issuer is rejected")
    void foreignIssuerIsRejected() throws Exception {
        // Correctly signed by the same key, wrong `iss`. Without this check a
        // token minted by a dev Auth Service would be accepted in production.
        mvc.perform(get("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + StubAuthServer.tokenFromForeignIssuer("ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + StubAuthServer.expiredToken("ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not.a.jwt", "Bearer", "", "eyJhbGciOiJub25lIn0.e30."})
    @DisplayName("malformed and unsigned tokens are rejected")
    void malformedTokensAreRejected(String token) throws Exception {
        // The last one is the classic "alg: none" forgery. RS256 is pinned in the
        // decoder precisely so the token cannot nominate its own algorithm.
        mvc.perform(get("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ── The public surface stays public ──────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/destinations",
            "/api/v1/destinations/ella",
            "/api/v1/destinations/ella/attractions",
            "/api/v1/destinations/nearby?lat=6.8667&lng=81.0466",
            "/api/v1/attractions/nearby?lat=6.8667&lng=81.0466",
            "/api/v1/categories",
            "/actuator/health",
            "/actuator/health/readiness",
    })
    @DisplayName("the traveler-facing surface needs no token")
    void publicEndpointsStayPublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a valid token does not unlock draft content on a public endpoint")
    void adminTokenDoesNotChangePublicResponses() throws Exception {
        // Public reads are filtered in the repository, not by role, so presenting
        // an admin token to a public endpoint must change nothing at all.
        String anonymous = mvc.perform(get("/api/v1/destinations"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/v1/destinations")
                        .header(HttpHeaders.AUTHORIZATION, bearer("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(anonymous, true));
    }

    private static String bearer(String role) {
        return "Bearer " + StubAuthServer.tokenFor(role);
    }
}
