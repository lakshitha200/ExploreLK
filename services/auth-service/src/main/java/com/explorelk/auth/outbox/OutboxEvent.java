package com.explorelk.auth.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A domain event waiting to be published to Kafka.
 *
 * <p>Rows are inserted in the <em>same transaction</em> as the business change
 * they describe, so the event can never be lost by a crash between the database
 * commit and the Kafka send:
 *
 * <pre>
 *   BEGIN
 *     INSERT INTO users ...
 *     INSERT INTO outbox_events ...
 *   COMMIT
 *          |
 *   OutboxPublisher (scheduled)  ->  Kafka  ->  published_at = now()
 * </pre>
 *
 * <p>Delivery is at-least-once, so consumers must be idempotent — that is what
 * {@link #id} is for once it travels as the event id.
 *
 * <p>Written by {@link OutboxWriter} inside the caller's transaction and
 * drained by {@link OutboxPublisher}.
 */
@Entity
@Table(name = "outbox_events")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    /**
     * Travels to Kafka as the event id; consumers dedupe on it.
     *
     * <p><strong>Assigned, not generated.</strong> {@code @GeneratedValue} would
     * have Hibernate mint the id at persist time — after {@link OutboxWriter}
     * has already serialized it into the payload, so the row id and the
     * {@code eventId} inside the JSON would be two different values and an
     * operator holding a Kafka message could not find the row that produced it.
     */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Always {@code USER} for this service. */
    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    /** The user id. Used as the Kafka partition key, so one user's events stay ordered. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** {@code USER_REGISTERED}, {@code PROVIDER_APPROVED}, ... */
    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    /** JSONB. Never contains a password hash. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until the event reaches Kafka. The partial index covers exactly these rows. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    // ── Behaviour ────────────────────────────────────────────────────────────

    public boolean isPublished() {
        return publishedAt != null;
    }
}
