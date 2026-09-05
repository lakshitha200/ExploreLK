package com.explorelk.auth.outbox;

import com.explorelk.auth.user.User;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The envelope every event on {@code explorelk.auth.events} shares.
 *
 * <p>One shape for all seven types, so a consumer can deserialize the envelope,
 * switch on {@code eventType} and only then look inside {@code data}. Without a
 * common envelope every consumer needs seven parsers and a new one breaks them
 * all.
 *
 * <pre>
 * {
 *   "eventId":    "uuid",
 *   "eventType":  "USER_REGISTERED",
 *   "occurredAt": "2026-09-05T10:15:30Z",
 *   "version":    1,
 *   "aggregateId":"user-uuid",
 *   "data":       { "userId": "...", "email": "...", "role": "TRAVELER" }
 * }
 * </pre>
 *
 * <p>{@code version} is there from the first event on purpose. Adding it later
 * means every consumer has to cope with its absence, which is a migration
 * nobody schedules.
 *
 * <p>The {@code data} of a user event is assembled by {@link #dataFor} rather
 * than by serializing the entity: {@link User} has a {@code passwordHash} field,
 * and an event is the last place that may ever appear. Listing the fields by
 * hand means adding a sensitive column to the table cannot quietly add it to
 * the topic too.
 */
public record AuthEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        int version,
        UUID aggregateId,
        Map<String, Object> data) {

    /** Bumped only when a payload change would break an existing consumer. */
    private static final int CURRENT_VERSION = 1;

    public static AuthEvent of(AuthEventType type, User user) {
        return of(type, user, Map.of());
    }

    /**
     * @param extra fields specific to one event type — the verification token on
     *              {@code USER_REGISTERED}, the reason on a suspension.
     */
    public static AuthEvent of(AuthEventType type, User user, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>(dataFor(user));
        data.putAll(extra);

        return new AuthEvent(
                // Replaced by the outbox row id in OutboxWriter, so a Kafka
                // message can always be traced back to the row that produced it.
                UUID.randomUUID(),
                type.name(),
                Instant.now(),
                CURRENT_VERSION,
                user.getId(),
                Map.copyOf(data));
    }

    /** Field by field, deliberately — see the class comment. */
    private static Map<String, Object> dataFor(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId().toString());
        data.put("email", user.getEmail());
        data.put("fullName", user.getFullName());
        data.put("role", user.getRole().name());
        data.put("status", user.getStatus().name());
        data.put("emailVerified", user.isEmailVerified());
        data.put("providerApproved", user.isProviderApproved());
        return data;
    }

    /** Stamps the row id in as the event id, so the two are the same value. */
    public AuthEvent withEventId(UUID id) {
        return new AuthEvent(id, eventType, occurredAt, version, aggregateId, data);
    }
}
