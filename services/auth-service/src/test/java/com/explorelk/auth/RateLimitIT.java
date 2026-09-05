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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Brute-force protection: the per-account lockout, and the per-IP limit.
 *
 * <p>The two are deliberately different mechanisms. The lockout lives in
 * Postgres and protects one account from a password list; the request limit
 * lives in Redis and protects the machine from the cost of answering. They fail
 * differently on purpose — see {@code LoginAttemptService} for why the important
 * one is not in the cache.
 */
@IntegrationTest
@Import(TestMailConfig.class)
class RateLimitIT extends TestContainers {

    private static final String EMAIL = "traveler@example.com";
    private static final String PASSWORD = "Sigiriya_2026";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("five failures lock the account, and the right password no longer helps")
    void accountLocksAfterFiveFailures() throws Exception {
        activeUser();

        for (int attempt = 1; attempt <= 5; attempt++) {
            login("Wrong_Password_" + attempt)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        // The sixth attempt is the one that changes: knowing the password is no
        // longer enough, which is what makes an online password list useless.
        login(PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        User locked = users.findByEmail(EMAIL).orElseThrow();
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getLockedUntil()).isAfter(Instant.now());
        // Counter cleared with the lock. Left at five, the first failure after
        // the lock expired would lock the account again — permanently, in
        // practice, and by accident.
        assertThat(locked.getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("the counter is consecutive failures, not lifetime ones")
    void successResetsTheCounter() throws Exception {
        activeUser();

        login("Wrong_1").andExpect(status().isUnauthorized());
        login("Wrong_2").andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isOk());

        assertThat(users.findByEmail(EMAIL).orElseThrow().getFailedLoginAttempts()).isZero();

        // Someone who mistypes twice a week for a year is not an attacker, and
        // locking them out on the fifth occasion would be absurd.
        login("Wrong_3").andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a failure against an unknown address creates nothing")
    void unknownAddressesAreNotCounted() throws Exception {
        for (int i = 0; i < 6; i++) {
            login("nobody@example.com", "Wrong_" + i).andExpect(status().isUnauthorized());
        }

        // There is no row to count against, and inventing one would turn login
        // into a way of filling the users table.
        assertThat(users.count()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void activeUser() {
        users.save(User.builder()
                .email(EMAIL)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Test Traveler")
                .role(UserRole.TRAVELER)
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .providerApproved(false)
                .mustChangePassword(false)
                .failedLoginAttempts(0)
                .build());
    }

    private ResultActions login(String password) throws Exception {
        return login(EMAIL, password);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }
}
