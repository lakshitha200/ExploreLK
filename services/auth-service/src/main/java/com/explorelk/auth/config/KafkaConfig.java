package com.explorelk.auth.config;

import com.explorelk.auth.outbox.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Kafka, used only to announce that something happened to a user.
 *
 * <p><strong>Kafka is not on the request path.</strong> Registering, logging in
 * and refreshing are plain REST against Postgres and Redis. Events exist so
 * other services learn about a user without asking this one — which is why a
 * broker outage costs nothing here: the outbox keeps accepting events into
 * Postgres and the publisher drains the backlog whenever Kafka returns.
 *
 * <p>Nothing in this service consumes. It only produces.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(KafkaTopics.class)
@ConditionalOnProperty(name = "explorelk.events.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class KafkaConfig {

    /**
     * Created at startup if it does not exist, so a fresh environment works
     * without anyone running {@code kafka-topics --create} by hand.
     *
     * <p>Three partitions, keyed by the user id. Ordering in Kafka is per
     * partition, never global, so this buys the only ordering guarantee that
     * matters: every event about one user arrives in the order it happened, and
     * a consumer cannot see the suspension before the registration. Events about
     * <em>different</em> users have no ordering relative to each other, and
     * nothing should assume otherwise.
     *
     * <p>One replica in development. Production must override
     * {@code explorelk.events.replicas} — with a single replica, losing a broker
     * loses the partition.
     */
    @Bean
    public NewTopic authEventsTopic(KafkaTopics topics) {
        log.info("Kafka topic {} ({} partitions, {} replicas)",
                topics.authEvents(), topics.partitions(), topics.replicas());

        return TopicBuilder.name(topics.authEvents())
                .partitions(topics.partitions())
                .replicas(topics.replicas())
                .build();
    }
}
