package com.explorelk.destination.outbox;

import com.explorelk.destination.attraction.Attraction;
import com.explorelk.destination.destination.Destination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Records a catalog event in the outbox table.
 *
 * <p><strong>{@link Propagation#MANDATORY} is the whole design, in one
 * annotation.</strong> This has to run inside the caller's transaction — the one
 * already changing the destination or attraction — because that is what makes
 * the business change and its event atomic. Calling it outside a transaction
 * throws immediately instead of quietly opening a second one, which would
 * reintroduce exactly the gap the outbox exists to close: a committed row with
 * no event, or an event describing a change that then rolled back.
 *
 * <p>Nothing here talks to Kafka. Publishing is {@link OutboxPublisher}'s job,
 * after the commit, on its own schedule, with retries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(CatalogEventType eventType, Destination destination) {
        write(eventType, destination.getId(), CatalogEvent.of(eventType, destination));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(CatalogEventType eventType, Attraction attraction) {
        write(eventType, attraction.getId(), CatalogEvent.of(eventType, attraction));
    }

    private void write(CatalogEventType eventType, UUID aggregateId, CatalogEvent event) {
        // The id is minted here rather than by the database, so it can be stamped
        // into the payload before serializing. Row id and event id being the same
        // value is what lets an operator trace a Kafka message back to its row.
        UUID eventId = UUID.randomUUID();

        OutboxEvent row = OutboxEvent.builder()
                .id(eventId)
                .aggregateType(eventType.aggregateType())
                .aggregateId(aggregateId)
                .eventType(eventType.name())
                .payload(objectMapper.writeValueAsString(event.withEventId(eventId)))
                .build();

        outboxRepository.save(row);
        log.debug("Outbox: {} for {} ({})", eventType, aggregateId, eventId);
    }
}
