package com.explorelk.destination.config;

import com.explorelk.destination.outbox.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Kafka, used only to announce that the catalog changed.
 *
 * <p><strong>Kafka is not on the read path.</strong> Browsing, searching and
 * proximity queries are plain REST against Postgres. Events exist so that other
 * services learn the catalog moved underneath them — above all
 * {@code ATTRACTION_UPDATED}, because an itinerary built around a 90-minute
 * visit is quietly wrong once that becomes 180 and nothing else would ever tell
 * the Itinerary Service.
 *
 * <p>That is also why a broker outage does not break this service: the outbox
 * keeps accepting events into Postgres, and the publisher drains the backlog
 * whenever Kafka returns.
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
     * <p>Three partitions, keyed by aggregate id. Ordering in Kafka is per
     * partition, never global, so this buys the only ordering guarantee that
     * matters: every event about one destination or attraction arrives in the
     * order it happened. Events about <em>different</em> entities have no
     * ordering relative to each other, and nothing should assume otherwise.
     *
     * <p>One replica in development. Production must override
     * {@code explorelk.events.replicas} — with a single replica, losing a broker
     * loses the partition.
     */
    @Bean
    public NewTopic catalogEventsTopic(KafkaTopics topics) {
        log.info("Kafka topic {} ({} partitions, {} replicas)",
                topics.catalogEvents(), topics.partitions(), topics.replicas());

        return TopicBuilder.name(topics.catalogEvents())
                .partitions(topics.partitions())
                .replicas(topics.replicas())
                .build();
    }
}
