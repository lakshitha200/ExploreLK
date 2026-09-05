package com.explorelk.auth.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed outbox rows to Kafka.
 *
 * <p>Runs on a fixed delay rather than reacting to writes, because the point of
 * the outbox is that publishing survives this process dying. A row is already
 * durable when this method first sees it; if the JVM is killed mid-batch, the
 * next poll — in this instance or another one — picks the same rows up.
 *
 * <p><strong>Delivery is at-least-once and cannot be anything else.</strong>
 * There is no atomic commit spanning Kafka and Postgres, so a crash after the
 * send but before {@code published_at} is written re-sends the event. Consumers
 * dedupe on the event id. Trying to make it exactly-once means distributed
 * transactions, which cost far more than the idempotency check they replace.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "explorelk.events.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboxPublisher {

    /**
     * Small enough that one slow batch does not hold its row locks for long,
     * large enough that a burst of registrations drains in a poll or two.
     */
    private static final int BATCH_SIZE = 100;

    /**
     * How long to wait for the broker before treating a send as failed. Bounded
     * because this runs in a transaction holding row locks — an unbounded wait
     * on a wedged broker would hold them until someone noticed.
     */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopics topics;

    /**
     * <p>The whole batch runs in one transaction, and that is deliberate: the
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} lock has to be held for as long
     * as the rows are being published, or a second instance would pick up rows
     * this one is midway through sending.
     *
     * <p>A failed send marks the row and moves on rather than aborting the
     * batch. One event Kafka refuses — a payload over the message size limit,
     * say — must not block every event behind it forever.
     */
    @Scheduled(fixedDelayString = "${explorelk.events.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.findUnpublishedBatch(Limit.of(BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }

        int sent = 0;
        for (OutboxEvent event : batch) {
            if (send(event)) {
                sent++;
            }
        }
        log.info("Outbox: published {}/{} events", sent, batch.size());
    }

    private boolean send(OutboxEvent event) {
        try {
            // Blocking on purpose. The row must not be marked published until the
            // broker has acknowledged it, and the surrounding transaction is what
            // makes "acknowledged" and "marked" commit together.
            kafkaTemplate
                    .send(topics.authEvents(), event.getAggregateId().toString(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            event.setPublishedAt(Instant.now());
            return true;

        } catch (InterruptedException e) {
            // Restore the flag and stop touching the row: shutdown is in progress
            // and the next startup will find this event still unpublished.
            Thread.currentThread().interrupt();
            log.warn("Outbox publishing interrupted at event {}", event.getId());
            return false;

        } catch (Exception e) {
            event.setAttempts(event.getAttempts() + 1);
            log.warn("Outbox: {} for {} failed (attempt {}): {}",
                    event.getEventType(), event.getAggregateId(), event.getAttempts(), e.getMessage());
            return false;
        }
    }
}
