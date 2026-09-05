package com.explorelk.destination.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The next batch to publish, locked so a second instance skips it.
     *
     * <p><strong>{@code SKIP LOCKED} is what makes this safe to run on more than
     * one instance.</strong> A plain {@code FOR UPDATE} would make the second
     * publisher block on the first one's rows and then publish the same events
     * again once the lock cleared; without any lock, both would publish every
     * row. With {@code SKIP LOCKED} the second poller simply steps over the
     * locked rows and takes the next ones, so throughput scales with instances
     * instead of duplicating work.
     *
     * <p>Ordered by {@code created_at} so events are published roughly in the
     * order they happened. Ordering is only guaranteed <em>per aggregate</em>,
     * and that guarantee comes from Kafka partitioning on the aggregate id, not
     * from this query.
     *
     * <p>The partial index {@code ix_outbox_unpublished} covers exactly this
     * predicate, so the scan stays small no matter how many published rows have
     * accumulated.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublishedBatch(Limit limit);
}
