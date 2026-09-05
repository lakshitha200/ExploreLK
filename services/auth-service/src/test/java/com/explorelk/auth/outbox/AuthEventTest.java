package com.explorelk.auth.outbox;

import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event envelope, and the one rule that must never break: no credential ever
 * reaches the topic.
 */
class AuthEventTest {

    @ParameterizedTest
    @EnumSource(AuthEventType.class)
    @DisplayName("no event type carries the password hash")
    void noEventCarriesTheHash(AuthEventType type) {
        // Parameterised over the enum on purpose: adding an eighth event type
        // adds a case here automatically, so a new event cannot quietly leak the
        // field that a hand-written list of six tests would have missed.
        AuthEvent event = AuthEvent.of(type, user());

        assertThat(event.data()).doesNotContainKey("passwordHash");
        assertThat(event.data().values().toString()).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("the envelope has every field a consumer switches on")
    void envelopeIsComplete() {
        User user = user();
        AuthEvent event = AuthEvent.of(AuthEventType.USER_REGISTERED, user);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventType()).isEqualTo("USER_REGISTERED");
        assertThat(event.occurredAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(user.getId());
        assertThat(event.data()).containsEntry("email", "traveler@example.com");
        assertThat(event.data()).containsEntry("role", "TRAVELER");
    }

    @Test
    @DisplayName("extra fields are merged, not replacing the user data")
    void extraFieldsAreAdditive() {
        AuthEvent event = AuthEvent.of(AuthEventType.USER_REGISTERED, user(),
                Map.of("verificationToken", "abc123"));

        assertThat(event.data())
                .containsEntry("verificationToken", "abc123")
                .containsEntry("email", "traveler@example.com");
    }

    @Test
    @DisplayName("withEventId replaces the id and leaves everything else alone")
    void withEventIdKeepsTheRest() {
        AuthEvent original = AuthEvent.of(AuthEventType.USER_REGISTERED, user());
        UUID rowId = UUID.randomUUID();

        AuthEvent stamped = original.withEventId(rowId);

        // Row id and event id being the same value is what lets an operator trace
        // a Kafka message back to the row that produced it.
        assertThat(stamped.eventId()).isEqualTo(rowId);
        assertThat(stamped.eventType()).isEqualTo(original.eventType());
        assertThat(stamped.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(stamped.aggregateId()).isEqualTo(original.aggregateId());
        assertThat(stamped.data()).isEqualTo(original.data());
    }

    @Test
    @DisplayName("every event type is a USER aggregate")
    void aggregateTypeMatchesTheCheckConstraint() {
        // The column has CHECK (aggregate_type IN ('USER')). A mismatch here
        // would fail at flush time — taking down the business change the event
        // was only meant to describe.
        assertThat(AuthEventType.AGGREGATE_TYPE).isEqualTo("USER");
    }

    private static User user() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("traveler@example.com")
                .passwordHash("$2a$12$abcdefghijklmnopqrstuv")
                .fullName("Test Traveler")
                .role(UserRole.TRAVELER)
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .build();
    }
}
