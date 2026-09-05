package com.explorelk.auth.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code explorelk.events.*} — the topic this service writes to, and the poller
 * settings around it.
 *
 * @param authEvents     topic name. All seven event types go to one topic so a
 *                       consumer subscribes once and filters on
 *                       {@code eventType}, rather than tracking seven topic
 *                       names that grow every time the service learns a new verb
 * @param partitions     three, matching the design. The partition key is the
 *                       user id, so one user's events stay in order — the only
 *                       ordering that means anything here. A suspension
 *                       overtaking the registration it applies to would be a
 *                       real bug in a consumer
 * @param replicas       one in development. Production overrides it; a single
 *                       replica means a broker failure loses the partition
 * @param pollIntervalMs how often the publisher drains the outbox
 * @param enabled        false turns publishing off entirely, for tests and for
 *                       running the service with no broker present
 */
@ConfigurationProperties(prefix = "explorelk.events")
public record KafkaTopics(
        String authEvents,
        int partitions,
        short replicas,
        long pollIntervalMs,
        boolean enabled
) {
}
