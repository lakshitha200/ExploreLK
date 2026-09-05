package com.explorelk.auth;

import com.explorelk.auth.support.IntegrationTest;
import com.explorelk.auth.support.TestContainers;
import com.explorelk.auth.support.TestMailConfig;
import com.explorelk.auth.support.TestMailConfig.CapturingEmailSender.Kind;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which change writes which event — all seven, against the outbox table.
 *
 * <p><strong>No broker here on purpose.</strong> {@link OutboxKafkaIT} proves a
 * committed row reaches Kafka, and that proof does not get stronger by being
 * repeated seven times at twenty seconds of container startup each. What is left
 * to check is the mapping in §7, and that is a question about rows.
 *
 * <p>The negative cases matter as much as the positive ones. An event stream
 * full of things that did not happen — a rejected provider, a duplicate signup —
 * is what teaches consumers to stop reading it.
 */
@IntegrationTest
@Import(TestMailConfig.class)
class OutboxEventsIT extends TestContainers {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestMailConfig.CapturingEmailSender mail;

    @BeforeEach
    void clearMail() {
        mail.clear();
    }

    // ── Registration and verification ────────────────────────────────────────

    @Test
    @DisplayName("registering a traveler emits USER_REGISTERED, and verifying emits USER_EMAIL_VERIFIED")
    void registrationAndVerification() throws Exception {
        register("traveler@example.com", "TRAVELER").andExpect(status().isAccepted());

        UUID id = users.findByEmail("traveler@example.com").orElseThrow().getId();
        assertThat(eventsFor(id)).containsExactly("USER_REGISTERED");

        // The token travels with the event because the Notification Service
        // cannot build the verification link without it. It is the one sensitive
        // value that is allowed on the topic — and a password hash never is.
        JsonNode registered = payloadOf(id, "USER_REGISTERED");
        assertThat(registered.get("data").get("verificationToken").asString()).isNotBlank();
        assertThat(registered.toString()).doesNotContain("passwordHash").doesNotContain("$2a$");

        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + mail.lastTokenOfKind(Kind.VERIFICATION) + "\"}"))
                .andExpect(status().isOk());

        assertThat(eventsFor(id)).containsExactly("USER_REGISTERED", "USER_EMAIL_VERIFIED");
    }

    @Test
    @DisplayName("a provider registration emits both USER_REGISTERED and PROVIDER_REGISTERED")
    void providerRegistrationEmitsTwo() throws Exception {
        register("provider@example.com", "PROVIDER").andExpect(status().isAccepted());

        UUID id = users.findByEmail("provider@example.com").orElseThrow().getId();

        // Somebody has to be told there is an approval queue, and that is a
        // different fact from "a user signed up".
        assertThat(eventsFor(id)).containsExactlyInAnyOrder("USER_REGISTERED", "PROVIDER_REGISTERED");
    }

    @Test
    @DisplayName("a duplicate registration announces nothing")
    void duplicateRegistrationIsSilentOnTheTopic() throws Exception {
        register("traveler@example.com", "TRAVELER").andExpect(status().isAccepted());
        UUID id = users.findByEmail("traveler@example.com").orElseThrow().getId();

        register("traveler@example.com", "TRAVELER").andExpect(status().isAccepted());

        // No account was created, so there is nothing for a consumer to do. A
        // second USER_REGISTERED would have the Notification Service send a
        // verification link for an account that already exists.
        assertThat(eventsFor(id)).containsExactly("USER_REGISTERED");
    }

    // ── Administrative changes ───────────────────────────────────────────────

    @Test
    @DisplayName("suspending and disabling emit USER_SUSPENDED and USER_DISABLED")
    void statusChangesEmitEvents() throws Exception {
        String adminToken = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User traveler = activeUser("traveler@example.com", UserRole.TRAVELER);

        setStatus(traveler.getId(), "SUSPENDED", adminToken);
        assertThat(eventsFor(traveler.getId())).containsExactly("USER_SUSPENDED");

        setStatus(traveler.getId(), "DISABLED", adminToken);
        assertThat(eventsFor(traveler.getId())).containsExactly("USER_SUSPENDED", "USER_DISABLED");

        // Booking and Trip need to know what it was before, to decide whether
        // anything of theirs was already blocked.
        assertThat(payloadOf(traveler.getId(), "USER_DISABLED")
                .get("data").get("previousStatus").asString()).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("restoring a user to ACTIVE announces nothing")
    void reactivationIsSilent() throws Exception {
        String adminToken = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User traveler = activeUser("traveler@example.com", UserRole.TRAVELER);

        setStatus(traveler.getId(), "SUSPENDED", adminToken);
        setStatus(traveler.getId(), "ACTIVE", adminToken);

        // Nothing downstream blocked anything on "this user is fine again" — the
        // account simply works, which consumers discover by the tokens working.
        assertThat(eventsFor(traveler.getId())).containsExactly("USER_SUSPENDED");
    }

    @Test
    @DisplayName("setting the same status twice emits one event, not two")
    void statusChangeIsIdempotent() throws Exception {
        String adminToken = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User traveler = activeUser("traveler@example.com", UserRole.TRAVELER);

        setStatus(traveler.getId(), "SUSPENDED", adminToken);
        setStatus(traveler.getId(), "SUSPENDED", adminToken);

        assertThat(eventsFor(traveler.getId())).containsExactly("USER_SUSPENDED");
    }

    @Test
    @DisplayName("approving a provider emits PROVIDER_APPROVED; rejecting emits nothing")
    void providerApprovalEmitsOnlyOnApproval() throws Exception {
        String adminToken = tokenFor(activeUser("admin@example.com", UserRole.ADMIN));
        User provider = activeUser("provider@example.com", UserRole.PROVIDER);

        setApproval(provider.getId(), false, adminToken);
        // Nothing changed that anyone was relying on: they could not publish
        // before and still cannot.
        assertThat(eventsFor(provider.getId())).isEmpty();

        setApproval(provider.getId(), true, adminToken);
        assertThat(eventsFor(provider.getId())).containsExactly("PROVIDER_APPROVED");
    }

    @Test
    @DisplayName("creating an admin emits ADMIN_CREATED, with who did it")
    void adminCreationIsAudited() throws Exception {
        User superAdmin = activeUser("super@example.com", UserRole.SUPER_ADMIN);
        String token = tokenFor(superAdmin);

        mvc.perform(post("/api/v1/super-admin/admins")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new-admin@example.com","password":"Sigiriya_2026",
                                 "fullName":"New Admin"}"""))
                .andExpect(status().isCreated());

        UUID adminId = users.findByEmail("new-admin@example.com").orElseThrow().getId();
        assertThat(eventsFor(adminId)).containsExactly("ADMIN_CREATED");

        // Granting privilege is the one action where "who did it" is the whole
        // value of the record.
        assertThat(payloadOf(adminId, "ADMIN_CREATED").get("data").get("createdBy").asString())
                .isEqualTo(superAdmin.getId().toString());
    }

    // ── The envelope ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("every event carries the same envelope, and waits unpublished")
    void envelopeIsConsistent() throws Exception {
        register("traveler@example.com", "TRAVELER").andExpect(status().isAccepted());
        UUID id = users.findByEmail("traveler@example.com").orElseThrow().getId();

        JsonNode event = payloadOf(id, "USER_REGISTERED");
        assertThat(event.get("eventId").asString()).isNotBlank();
        assertThat(event.get("eventType").asString()).isEqualTo("USER_REGISTERED");
        assertThat(event.get("occurredAt").asString()).isNotBlank();
        // Present from the first event on purpose: adding it later is a
        // migration every consumer has to cope with.
        assertThat(event.get("version").asInt()).isEqualTo(1);
        assertThat(event.get("aggregateId").asString()).isEqualTo(id.toString());

        // The row id and the event id are the same value, which is what lets an
        // operator trace a Kafka message back to the row that produced it.
        String rowId = jdbc.queryForObject(
                "SELECT id::text FROM outbox_events WHERE aggregate_id = ?::uuid", String.class, id.toString());
        assertThat(event.get("eventId").asString()).isEqualTo(rowId);

        // Events are disabled in the test profile, so nothing drains the table —
        // which is exactly the state this service is in whenever Kafka is down.
        // The registration succeeded anyway.
        Integer unpublished = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
        assertThat(unpublished).isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<String> eventsFor(UUID aggregateId) {
        return jdbc.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ?::uuid ORDER BY created_at, event_type",
                String.class, aggregateId.toString());
    }

    private JsonNode payloadOf(UUID aggregateId, String eventType) {
        return json.readTree(jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = ?",
                String.class, aggregateId.toString(), eventType));
    }

    private org.springframework.test.web.servlet.ResultActions register(String email, String role) throws Exception {
        return mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Sigiriya_2026","fullName":"Test User","role":"%s"}"""
                        .formatted(email, role)));
    }

    private void setStatus(UUID id, String status, String token) throws Exception {
        mvc.perform(patch("/api/v1/admin/users/" + id + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private void setApproval(UUID id, boolean approved, String token) throws Exception {
        mvc.perform(patch("/api/v1/admin/providers/" + id + "/approval")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":" + approved + "}"))
                .andExpect(status().isOk());
    }

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

    private String tokenFor(User user) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Sigiriya_2026\"}".formatted(user.getEmail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return json.readTree(body).get("accessToken").asString();
    }
}
