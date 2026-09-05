package com.explorelk.auth;

import com.explorelk.auth.support.TestContainers;
import com.explorelk.auth.support.TestMailConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-IP request limit, in a context of its own.
 *
 * <p><strong>It needs its own limit, and that is the point of the separate
 * class.</strong> Every other IT sends dozens of requests from the same
 * MockMvc "address", so the shared test profile raises the ceiling out of the
 * way; a class that must actually exceed it would otherwise have to send
 * hundreds of BCrypt-costing requests, take longer than the 60-second window,
 * and then fail because the fixed window had rolled over halfway through.
 * Lowering the limit here makes the same assertion in five requests.
 *
 * <p>What this protects is not really the account — {@code RateLimitIT} covers
 * that — but the machine. BCrypt at strength 12 costs about a quarter-second of
 * CPU by design, so an unthrottled login endpoint is a denial-of-service
 * amplifier whether or not any password is ever correct.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Import(TestMailConfig.class)
class IpRateLimitIT extends TestContainers {

    private static final int LIMIT = 5;

    @DynamicPropertySource
    static void tightLimit(DynamicPropertyRegistry registry) {
        registry.add("explorelk.rate-limit.login-per-ip", () -> LIMIT);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("past the limit is a 429 with a Retry-After, before any password is checked")
    void tooManyRequestsFromOneIp() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            // 401, not 429: still under the limit, so these are answered normally.
            login().andExpect(status().isUnauthorized());
        }

        login()
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                // Without this a client either gives up or retries immediately,
                // and the immediate retry is what turns a limit into a hot loop.
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("registration is limited too, not just login")
    void registrationIsAlsoLimited() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"burst-%d@example.com","password":"Sigiriya_2026",
                                     "fullName":"Burst","role":"TRAVELER"}""".formatted(i)))
                    .andExpect(status().isAccepted());
        }

        // Registration is the cheapest way to fill the users table, and the
        // limiter counts every unauthenticated auth endpoint against one bucket.
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"one-too-many@example.com","password":"Sigiriya_2026",
                                 "fullName":"Burst","role":"TRAVELER"}"""))
                .andExpect(status().isTooManyRequests());
    }

    private ResultActions login() throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"nobody@example.com","password":"Wrong_Password_1"}"""));
    }
}
