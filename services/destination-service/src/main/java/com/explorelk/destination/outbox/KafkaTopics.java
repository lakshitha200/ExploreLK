package com.explorelk.destination.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code explorelk.events.*} — the topic this service writes to, and the poller
 * settings around it.
 *
 * @param catalogEvents   topic name; every catalog event goes to one topic so a
 *                        consumer subscribes once and filters on
 *                        {@code eventType}, rather than tracking six topic names
 *                        that grow every time the catalog learns a new verb
 * @param partitions      three, matching the design. The partition key is the
 *                        aggregate id, so ordering holds per destination or
 *                        attraction — which is the only ordering that means
 *                        anything here
 * @param replicas        one in development. Production overrides it; a single
 *                        replica means a broker failure loses the partition
 * @param pollIntervalMs  how often the publisher drains the outbox
 * @param enabled         false turns publishing off entirely, for tests and for
 *                        running the catalog with no broker present
 */
@ConfigurationProperties(prefix = "explorelk.events")
public record KafkaTopics(
        String catalogEvents,
        int partitions,
        short replicas,
        long pollIntervalMs,
        boolean enabled
) {
}
