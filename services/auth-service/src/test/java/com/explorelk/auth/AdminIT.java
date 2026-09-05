package com.explorelk.auth;

import com.explorelk.auth.support.IntegrationTest;
import com.explorelk.auth.support.TestContainers;
import com.explorelk.auth.support.TestMailConfig;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The administrative surface, and the wall around it.
 *
 * <p>Two questions, and the second matters more: what an admin can do, and what
 * everybody else cannot. A role check that is merely present is worth nothing —
 * it has to be checked from the outside, with a real token belonging to a real
 * TRAVELER, which is what most of this class does.
 */
@IntegrationTest
@Import(TestMailConfig.class)
class AdminIT extends TestContainers {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── The wall ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/admin/users",
            "/api/v1/super-admin/admins"})
    @DisplayName("no token is a 401, never a 403 or a redirect")
    void noTokenIsUnauthorized(String path) throws Exception {
        // 401, not 403: the caller has not said who they are, which is a
        // different problem from having said it and not being allowed. And a
        // JSON body, not a login redirect — this is an API, and a 302 to a form
        // is what a browser-shaped security config does to one.
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("a TRAVELER token is a 403 on every admin path")
    void travelerIsForbidden() throws Exception {
        String token = tokenFor(activeUser("traveler@example.com", UserRole.TRAVELER));

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(post("/api/v1/super-admin/admins")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"me-too@example.com","password":"Sigiriya_2026","fullName":"Me"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an ADMIN cannot reach the super-admin endpoints")
    void adminCannotCreateAdmins() throws Exception {
        String token = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));

        // An ADMIN who can mint ADMINs can quietly grant themselves a second
        // account nobody is watching. Privilege has to be granted from above it.
        mvc.perform(post("/api/v1/super-admin/admins")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"second@example.com","password":"Sigiriya_2026","fullName":"Second"}"""))
                .andExpect(status().isForbidden());

        assertThat(users.findByEmail("second@example.com")).isEmpty();
    }

    // ── What an admin can do ─────────────────────────────────────────────────

    @Test
    @DisplayName("an admin can list and filter users")
    void adminListsUsers() throws Exception {
        String token = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        activeUser("traveler@example.com", UserRole.TRAVELER);
        activeUser("provider@example.com", UserRole.PROVIDER);

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));

        mvc.perform(get("/api/v1/admin/users")
                        .param("role", "PROVIDER")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].email").value("provider@example.com"));
    }

    @Test
    @DisplayName("an admin list never carries a password hash")
    void adminListLeaksNoHashes() throws Exception {
        String token = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));

        String body = mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The entity has the field and the DTO does not. This is the assertion
        // that fails the day somebody "simplifies" the mapping to return entities.
        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("suspending a user ends their session immediately")
    void suspensionEndsTheSession() throws Exception {
        String adminToken = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User traveler = activeUser("traveler@example.com", UserRole.TRAVELER);

        JsonNode session = json.readTree(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"traveler@example.com","password":"Sigiriya_2026"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        setStatus(traveler.getId(), "SUSPENDED", adminToken).andExpect(status().isOk());

        // The refresh token is revoked, so no new access token can be minted.
        // Their current one keeps working until it expires — at most 15 minutes,
        // the same accepted trade as logout.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + session.get("refreshToken").asString() + "\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"traveler@example.com","password":"Sigiriya_2026"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    @DisplayName("an admin cannot suspend themselves")
    void adminCannotSuspendThemselves() throws Exception {
        User admin = activeUser("admin@example.com", UserRole.ADMIN);
        String token = tokenFor(admin);

        // Otherwise the first mis-click is a support ticket nobody left in the
        // system can resolve.
        setStatus(admin.getId(), "SUSPENDED", token).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an admin cannot touch a SUPER_ADMIN")
    void adminCannotTouchSuperAdmin() throws Exception {
        String token = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User superAdmin = activeUser("super@example.com", UserRole.SUPER_ADMIN);

        setStatus(superAdmin.getId(), "DISABLED", token).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("approving a provider is what lets them sell")
    void providerApproval() throws Exception {
        String token = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User provider = activeUser("provider@example.com", UserRole.PROVIDER);

        assertThat(provider.isProviderApproved()).isFalse();

        mvc.perform(patch("/api/v1/admin/providers/" + provider.getId() + "/approval")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerApproved").value(true));
    }

    @Test
    @DisplayName("only a PROVIDER can be approved")
    void approvingANonProviderIsRejected() throws Exception {
        String token = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User traveler = activeUser("traveler@example.com", UserRole.TRAVELER);

        mvc.perform(patch("/api/v1/admin/providers/" + traveler.getId() + "/approval")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isBadRequest());
    }

    // ── SUPER_ADMIN ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a super-admin creates an admin who must change their password")
    void superAdminCreatesAdmin() throws Exception {
        String token = tokenFor(activeUser("super@example.com", UserRole.SUPER_ADMIN));

        mvc.perform(post("/api/v1/super-admin/admins")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new-admin@example.com","password":"Sigiriya_2026",
                                 "fullName":"New Admin"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // Verified without an email round trip: a super-admin typed the
                // address deliberately, and an admin who cannot log in until they
                // find an email is the worse failure.
                .andExpect(jsonPath("$.emailVerified").value(true))
                // The password came from an environment somebody else can read.
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    @DisplayName("an admin created with a weak password is not created at all")
    void adminPasswordPolicyApplies() throws Exception {
        String token = tokenFor(activeUser("super@example.com", UserRole.SUPER_ADMIN));

        mvc.perform(post("/api/v1/super-admin/admins")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"weak-admin@example.com","password":"admin","fullName":"Weak"}"""))
                .andExpect(status().isBadRequest());

        assertThat(users.findByEmail("weak-admin@example.com")).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A ready-to-use account, created directly so the test does not re-test signup. */
    private User activeUser(String email, UserRole role) {
        return users.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("Sigiriya_2026"))
                .fullName("Test " + role)
                .role(role)
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .providerApproved(false)
                .mustChangePassword(false)
                .failedLoginAttempts(0)
                .build());
    }

    /** A real access token, minted by logging in — not hand-built. */
    private String tokenFor(User user) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Sigiriya_2026\"}".formatted(user.getEmail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return json.readTree(body).get("accessToken").asString();
    }

    private ResultActions setStatus(UUID id, String status, String token) throws Exception {
        return mvc.perform(patch("/api/v1/admin/users/" + id + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }
}
