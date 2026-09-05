package com.explorelk.auth;

import com.explorelk.auth.support.IntegrationTest;
import com.explorelk.auth.support.TestContainers;
import com.explorelk.auth.support.TestMailConfig;
import com.explorelk.auth.support.TestMailConfig.CapturingEmailSender.Kind;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Register → verify → log in → refresh → log out, and the ways each of those
 * refuses.
 *
 * <p>Everything goes through HTTP rather than the services directly, because
 * half of what is being tested is the filter chain: which endpoints are public,
 * what a rejection looks like, and whether a token is accepted.
 */
@IntegrationTest
@Import(TestMailConfig.class)
class AuthFlowIT extends TestContainers {

    private static final String EMAIL = "traveler@example.com";
    private static final String PASSWORD = "Sigiriya_2026";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestMailConfig.CapturingEmailSender mail;

    @BeforeEach
    void clearMail() {
        mail.clear();
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("registration accepts, but issues no tokens until the address is verified")
    void registrationDoesNotLogYouIn() throws Exception {
        register(EMAIL, PASSWORD)
                .andExpect(status().isAccepted())
                // No accessToken anywhere in the response: an unverified account
                // that is already logged in has made verification decorative.
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        var user = users.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(user.isEmailVerified()).isFalse();
        // Stored hashed, obviously — but worth asserting once, because the day
        // this fails is the day something started storing it raw.
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
    }

    @Test
    @DisplayName("a duplicate registration is indistinguishable from a new one")
    void duplicateRegistrationRevealsNothing() throws Exception {
        String first = register(EMAIL, PASSWORD).andReturn().getResponse().getContentAsString();
        verify(mail.lastTokenOfKind(Kind.VERIFICATION)).andExpect(status().isOk());
        mail.clear();

        String second = register(EMAIL, "Different_Pass_9")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Same status, same body. Anything else lets an attacker feed in a list
        // of addresses and learn which ones have accounts here.
        assertThat(second).isEqualTo(first);

        // The owner is told somebody tried, and their password is untouched.
        assertThat(mail.lastOfKind(Kind.DUPLICATE_REGISTRATION)).isPresent();
        assertThat(users.count()).isEqualTo(1);
        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the duplicate notice is never sent to an unconfirmed address")
    void duplicateNoticeNeedsAVerifiedAddress() throws Exception {
        register(EMAIL, PASSWORD).andExpect(status().isAccepted());
        mail.clear();

        register(EMAIL, "Different_Pass_9").andExpect(status().isAccepted());

        // Nobody has proved they own this address yet. Mailing it because a
        // stranger typed it into a signup form would make the service a way of
        // sending unsolicited mail to any address at all.
        assertThat(mail.lastOfKind(Kind.DUPLICATE_REGISTRATION)).isEmpty();
    }

    @Test
    @DisplayName("a weak password is a field error, not a stored account")
    void weakPasswordIsRejected() throws Exception {
        register("weak@example.com", "password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));

        assertThat(users.findByEmail("weak@example.com")).isEmpty();
    }

    @Test
    @DisplayName("nobody can register themselves as an ADMIN")
    void adminCannotSelfRegister() throws Exception {
        // The whole privilege model rests on this: if the role in a request body
        // were trusted, every other admin check would be decoration.
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sneaky@example.com","password":"Sigiriya_2026",
                                 "fullName":"Sneaky","role":"ADMIN"}"""))
                .andExpect(status().isBadRequest());

        assertThat(users.findByEmail("sneaky@example.com")).isEmpty();
    }

    // ── Verification and login ───────────────────────────────────────────────

    @Test
    @DisplayName("an unverified account cannot log in; a verified one can")
    void verificationGatesLogin() throws Exception {
        register(EMAIL, PASSWORD);

        login(EMAIL, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        verify(mail.lastTokenOfKind(Kind.VERIFICATION)).andExpect(status().isOk());

        login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.role").value("TRAVELER"));

        assertThat(users.findByEmail(EMAIL).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("a verification token works once")
    void verificationTokenIsSingleUse() throws Exception {
        register(EMAIL, PASSWORD);
        String token = mail.lastTokenOfKind(Kind.VERIFICATION);

        verify(token).andExpect(status().isOk());
        verify(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REUSED"));
    }

    @Test
    @DisplayName("an unknown address and a wrong password give the same answer")
    void failedLoginsAreIndistinguishable() throws Exception {
        registerAndVerify(EMAIL, PASSWORD);

        String wrongPassword = login(EMAIL, "Wrong_Password_1")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = login("nobody@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Byte for byte, apart from the trace id and timestamp — so the two are
        // compared on the code, which is the part a client can see and use.
        assertThat(json.readTree(wrongPassword).get("code").asString())
                .isEqualTo(json.readTree(unknownUser).get("code").asString())
                .isEqualTo("INVALID_CREDENTIALS");
    }

    // ── Refresh rotation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("refreshing rotates the token, and the old one stops working")
    void refreshRotates() throws Exception {
        registerAndVerify(EMAIL, PASSWORD);
        JsonNode first = tokens(login(EMAIL, PASSWORD));

        JsonNode second = tokens(refresh(first.get("refreshToken").asString())
                .andExpect(status().isOk()));

        assertThat(second.get("refreshToken").asString())
                .isNotEqualTo(first.get("refreshToken").asString());

        // The rotated-away token is dead. Without this, a stolen refresh token
        // stays valid for thirty days beside the legitimate one.
        refresh(first.get("refreshToken").asString())
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reusing a rotated token revokes the whole family")
    void reuseRevokesTheFamily() throws Exception {
        registerAndVerify(EMAIL, PASSWORD);
        JsonNode first = tokens(login(EMAIL, PASSWORD));
        JsonNode second = tokens(refresh(first.get("refreshToken").asString()));

        // Somebody replays the old token. Either the legitimate client or an
        // attacker holds a copy, and there is no way to tell which — so both
        // lose the session.
        refresh(first.get("refreshToken").asString()).andExpect(status().isUnauthorized());

        // The still-current token dies with it. This is the whole point of
        // family revocation, and the assertion that would fail if someone
        // "fixed" reuse detection to only reject the replayed token.
        refresh(second.get("refreshToken").asString())
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logging out ends the session")
    void logoutRevokesTheRefreshToken() throws Exception {
        registerAndVerify(EMAIL, PASSWORD);
        JsonNode issued = tokens(login(EMAIL, PASSWORD));

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + issued.get("accessToken").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + issued.get("refreshToken").asString() + "\"}"))
                .andExpect(status().isNoContent());

        refresh(issued.get("refreshToken").asString()).andExpect(status().isUnauthorized());
    }

    // ── Password reset ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a reset sets the new password and kills every existing session")
    void passwordResetRevokesSessions() throws Exception {
        registerAndVerify(EMAIL, PASSWORD);
        JsonNode issued = tokens(login(EMAIL, PASSWORD));

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isAccepted());

        String resetToken = mail.lastTokenOfKind(Kind.PASSWORD_RESET);

        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + resetToken + "\",\"newPassword\":\"Yala_Safari_77\"}"))
                .andExpect(status().isOk());

        // Whoever reset the password may have done so because the old one leaked.
        // Leaving the previous session alive would defeat the recovery.
        refresh(issued.get("refreshToken").asString()).andExpect(status().isUnauthorized());

        login(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
        login(EMAIL, "Yala_Safari_77").andExpect(status().isOk());
    }

    @Test
    @DisplayName("forgot-password says the same thing for an address that does not exist")
    void forgotPasswordRevealsNothing() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isAccepted());

        assertThat(mail.lastOfKind(Kind.PASSWORD_RESET)).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Test Traveler","role":"TRAVELER"}"""
                        .formatted(email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions verify(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    private void registerAndVerify(String email, String password) throws Exception {
        register(email, password).andExpect(status().isAccepted());
        verify(mail.lastTokenOfKind(Kind.VERIFICATION)).andExpect(status().isOk());
    }

    private JsonNode tokens(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
