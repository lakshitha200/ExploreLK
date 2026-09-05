package com.explorelk.destination;

import com.explorelk.destination.support.StubAuthServer;
import com.explorelk.destination.support.TestContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox, end to end: a committed change becomes a message on the topic.
 *
 * <p><strong>Deliberately the only Kafka test in the suite.</strong> A broker
 * container costs roughly twenty seconds of startup, against a second or two for
 * Postgres and Redis. What needs proving is that the pattern works — that events
 * are written transactionally and drained afterwards — and one test proves that
 * as well as ten would. The per-event rules about which change emits what are
 * cheaper to assert against the outbox table.
 *
 * <p>It does not extend {@code TestContainers} because it needs its own property
 * set with events switched on; the shared Postgres and Redis are still reused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class OutboxKafkaIT extends TestContainers {

    private static final String TOPIC = "explorelk.destination.events";

    /**
     * The same broker image {@code docker-compose.yml} runs, so this test
     * exercises the KRaft-mode Apache distribution the platform actually deploys
     * rather than a differently-configured Confluent one — and pulls nothing a
     * developer who has run {@code docker compose up} does not already have.
     */
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // The test profile disables publishing so the other suites do not wait on
        // a producer with nowhere to send. This is the one place it is turned on.
        registry.add("explorelk.events.enabled", () -> true);
        registry.add("explorelk.events.catalog-events", () -> TOPIC);
        registry.add("explorelk.events.replicas", () -> 1);
        registry.add("explorelk.events.poll-interval-ms", () -> 200);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("publishing a destination puts DESTINATION_PUBLISHED on the topic")
    void publishedEventReachesKafka() throws Exception {
        String name = "Kafka Test Place " + UUID.randomUUID().toString().substring(0, 8);

        String created = mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", name,
                                "district", "Trincomalee",
                                "province", "Eastern",
                                "summary", "Written by the Kafka integration test.",
                                "latitude", 8.5874,
                                "longitude", 81.2152,
                                "categories", List.of("BEACH")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode destination = json.readTree(created);
        String id = destination.get("id").asString();

        // Creating a DRAFT announces nothing: no consumer has seen the content, so
        // there is nothing to correct.
        mvc.perform(patch("/api/v1/admin/destinations/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        // The publisher is a scheduled poller, so the event arrives shortly after
        // the commit rather than during the request.
        ConsumerRecord<String, String> record = await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> pollFor(id), r -> r != null);

        // Keyed by aggregate id, so every event about this destination lands on
        // one partition and stays ordered.
        assertThat(record.key()).isEqualTo(id);

        JsonNode event = json.readTree(record.value());
        assertThat(event.get("eventType").asString()).isEqualTo("DESTINATION_PUBLISHED");
        assertThat(event.get("eventId").asString()).isNotBlank();
        assertThat(event.get("occurredAt").asString()).isNotBlank();

        // The payload has to be actionable on its own. A consumer that must call
        // back to find out what changed has gained nothing from the event.
        JsonNode payload = event.get("destination");
        assertThat(payload.get("id").asString()).isEqualTo(id);
        assertThat(payload.get("name").asString()).isEqualTo(name);
        assertThat(payload.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(payload.get("latitude").asDouble()).isEqualTo(8.5874);
        assertThat(payload.get("categories")).hasSize(1);
    }

    @Test
    @DisplayName("an event written while the broker is unreachable arrives once it returns")
    void eventsSurviveABrokerOutage() throws Exception {
        // The entire reason the outbox exists. Publishing straight to Kafka from
        // the request would lose this event, or — worse — fail the admin's edit
        // because a broker they have never heard of is restarting.
        pauseBroker();
        String id;
        try {
            id = publishNewDestination("Broker Outage Bay");

            // The row is committed and waiting. The catalog itself never noticed:
            // the write returned 200 with the broker unreachable.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(unpublishedRows(id)).isEqualTo(1));

        } finally {
            // In a finally block so a failed assertion above cannot leave a paused
            // broker behind for whatever runs next.
            unpauseBroker();
        }

        // Nothing re-sends this by hand. The publisher polls, finds the row still
        // unpublished, and drains it.
        ConsumerRecord<String, String> record = await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> pollFor(id), r -> r != null);

        assertThat(json.readTree(record.value()).get("eventType").asString())
                .isEqualTo("DESTINATION_PUBLISHED");

        // And it is marked published exactly once it genuinely was.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(unpublishedRows(id)).isZero());
    }

    /**
     * Paused rather than stopped: {@code stop()} then {@code start()} would give
     * the container a new random host port, and the producer — configured once at
     * startup — would be reconnecting to an address that no longer exists. That
     * would test Testcontainers, not the outbox.
     */
    private static void pauseBroker() {
        DockerClientFactory.instance().client()
                .pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unpauseBroker() {
        DockerClientFactory.instance().client()
                .unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private Integer unpublishedRows(String aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND published_at IS NULL",
                Integer.class, aggregateId);
    }

    /** Creates a complete destination and publishes it, returning its id. */
    private String publishNewDestination(String label) throws Exception {
        String name = label + " " + UUID.randomUUID().toString().substring(0, 8);

        String created = mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", name,
                                "district", "Trincomalee",
                                "province", "Eastern",
                                "summary", "Written by the Kafka integration test.",
                                "latitude", 8.5874,
                                "longitude", 81.2152,
                                "categories", List.of("BEACH")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = json.readTree(created).get("id").asString();

        mvc.perform(patch("/api/v1/admin/destinations/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        return id;
    }

    /** Drains the topic from the beginning and returns the record for this id, if any. */
    private ConsumerRecord<String, String> pollFor(String aggregateId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(3));
            for (ConsumerRecord<String, String> record : records) {
                if (aggregateId.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    private static String admin() {
        return "Bearer " + StubAuthServer.tokenFor("SUPER_ADMIN");
    }
}
