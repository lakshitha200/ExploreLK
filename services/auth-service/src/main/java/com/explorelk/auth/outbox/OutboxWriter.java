package com.explorelk.auth.outbox;

import com.explorelk.auth.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Records a domain event in the outbox table.
 *
 * <p><strong>{@link Propagation#MANDATORY} is the whole design, in one
 * annotation.</strong> This has to run inside the caller's transaction — the one
 * already inserting the user or changing their status — because that is what
 * makes the business change and its event atomic. Calling it outside a
 * transaction throws immediately instead of quietly opening a second one, which
 * would reintroduce exactly the gap the outbox exists to close: a registered
 * user whose event was lost, or an event describing a registration that then
 * rolled back.
 *
 * <p>Nothing here talks to Kafka. Publishing is {@link OutboxPublisher}'s job,
 * after the commit, on its own schedule, with retries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AuthEventType eventType, User user) {
        write(eventType, user, Map.of());
    }

    /**
     * @param extra event-specific fields — the verification token on
     *              {@code USER_REGISTERED}, the reason on a suspension.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AuthEventType eventType, User user, Map<String, Object> extra) {
        // The id is minted here rather than by the database, so it can be stamped
        // into the payload before serializing. Row id and event id being the same
        // value is what lets an operator trace a Kafka message back to its row.
        UUID eventId = UUID.randomUUID();

        AuthEvent event = AuthEvent.of(eventType, user, extra).withEventId(eventId);

        outboxRepository.save(OutboxEvent.builder()
                .id(eventId)
                .aggregateType(AuthEventType.AGGREGATE_TYPE)
                .aggregateId(user.getId())
                .eventType(eventType.name())
                .payload(objectMapper.writeValueAsString(event))
                .build());

        log.debug("Outbox: {} for user {} ({})", eventType, user.getId(), eventId);
    }
}
