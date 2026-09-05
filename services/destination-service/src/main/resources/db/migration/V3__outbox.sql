-- ════════════════════════════════════════════════════════════════════════════
-- Transactional outbox.
--
-- The same table shape as auth-service, on purpose: one pattern, one mental
-- model, and the publisher code copies across with only the aggregate type
-- changing.
--
-- Why an outbox at all. Publishing to Kafka from inside a service method has a
-- window between COMMIT and send. Crash there and the database change is
-- permanent while the event is gone forever, and nothing downstream ever learns
-- that a destination was published. Writing the event as a ROW in the same
-- transaction removes the window entirely: either both land or neither does.
-- A poller then moves rows to Kafka afterwards, and may safely retry.
--
--   BEGIN
--     UPDATE destinations SET status = 'PUBLISHED' ...
--     INSERT INTO outbox_events ...
--   COMMIT
--        |
--   OutboxPublisher (scheduled) --> Kafka --> published_at = now()
--
-- Delivery is at-least-once, never exactly-once: a crash after the Kafka send
-- but before published_at is written re-sends the row. Consumers dedupe on the
-- event id. Anything else is a distributed-transaction problem nobody needs.
--
-- V3, not V2 — V2 is the corrected trigram search indexes from Step 4.
-- ════════════════════════════════════════════════════════════════════════════

CREATE TABLE outbox_events
(
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- DESTINATION or ATTRACTION. Unlike auth-service, which only ever emits
    -- about users, this service owns two aggregates.
    aggregate_type VARCHAR(32) NOT NULL,

    -- The destination or attraction id. Used as the Kafka partition key, so all
    -- events about one entity stay in order relative to each other — which is
    -- what makes ATTRACTION_UPDATED followed by ATTRACTION_ARCHIVED safe to
    -- apply blindly on the consumer side.
    aggregate_id   UUID        NOT NULL,

    event_type     VARCHAR(48) NOT NULL,
    payload        JSONB       NOT NULL,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Null until the event reaches Kafka. The partial index covers exactly these.
    published_at   TIMESTAMPTZ,
    attempts       INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT ck_outbox_aggregate_type CHECK (aggregate_type IN ('DESTINATION', 'ATTRACTION')),
    CONSTRAINT ck_outbox_event_type CHECK (event_type IN (
        'DESTINATION_PUBLISHED', 'DESTINATION_UPDATED', 'DESTINATION_ARCHIVED',
        'ATTRACTION_PUBLISHED',  'ATTRACTION_UPDATED',  'ATTRACTION_ARCHIVED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

-- Partial index: the publisher only ever scans unpublished rows, so only those
-- are indexed. It stays tiny however large the table grows, and rows drop out of
-- it automatically once published. No MySQL equivalent.
CREATE INDEX ix_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;
