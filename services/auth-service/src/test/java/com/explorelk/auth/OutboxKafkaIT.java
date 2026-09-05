package com.explorelk.auth;

import com.explorelk.auth.support.TestContainers;
import com.explorelk.auth.support.TestMailConfig;
import com.explorelk.auth.user.UserRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox, end to end: a committed change becomes a message on the topic, and
 * a broker outage delays that rather than losing it.
 *
 * <p><strong>Deliberately the only Kafka test in the suite.</strong> A broker
 * container costs roughly twenty seconds of startup against a second for
 * Postgres. What needs proving is that the pattern works; the per-event rules
 * about which change emits what are cheaper to assert against the outbox table,
 * and {@link OutboxEventsIT} does exactly that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Import(TestMailConfig.class)
class OutboxKafkaIT extends TestContainers {

    private static final String TOPIC = "explorelk.auth.events";

    /**
     * The same broker image {@code docker-compose.yml} runs, so this exercises
     * the KRaft-mode Apache distribution the platform actually deploys — and
     * pulls nothing a developer who has run {@code docker compose up} does not
     * already have.
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
        // a producer with nowhere to send. This is the one place it is on.
        registry.add("explorelk.events.enabled", () -> true);
        registry.add("explorelk.events.auth-events", () -> TOPIC);
        registry.add("explorelk.events.replicas", () -> 1);
        registry.add("explorelk.events.poll-interval-ms", () -> 200);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("registering puts USER_REGISTERED on the topic, keyed by the user id")
    void registrationReachesKafka() throws Exception {
        String email = "kafka-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

        register(email).andExpect(status().isAccepted());
        String id = users.findByEmail(email).orElseThrow().getId().toString();

        // The publisher is a scheduled poller, so the event arrives shortly after
        // the commit rather than during the request. The HTTP response never
        // waited for Kafka — that is the point.
        ConsumerRecord<String, String> record = await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> pollFor(id), r -> r != null);

        // Keyed by user id, so one user's events land on one partition and stay
        // ordered — a suspension cannot overtake the registration it applies to.
        assertThat(record.key()).isEqualTo(id);

        JsonNode event = json.readTree(record.value());
        assertThat(event.get("eventType").asString()).isEqualTo("USER_REGISTERED");
        assertThat(event.get("eventId").asString()).isNotBlank();
        assertThat(event.get("data").get("email").asString()).isEqualTo(email);
        // Kafka retains records for days and every consumer sees all of them.
        assertThat(record.value()).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("an event written while the broker is unreachable arrives once it returns")
    void eventsSurviveABrokerOutage() throws Exception {
        String email = "outage-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

        // The entire reason the outbox exists. Publishing straight to Kafka from
        // the request would lose this registration's event — or, worse, fail the
        // registration because of a broker the user has never heard of.
        pauseBroker();
        String id;
        try {
            register(email).andExpect(status().isAccepted());
            id = users.findByEmail(email).orElseThrow().getId().toString();

            // Committed and waiting. The user registered successfully with the
            // broker unreachable, which is the behaviour being tested.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(unpublishedRows(id)).isEqualTo(1));

        } finally {
            // In a finally block so a failed assertion cannot leave a paused
            // broker behind for whatever runs next.
            unpauseBroker();
        }

        // Nothing re-sends this by hand. The publisher polls, finds the row still
        // unpublished, and drains it.
        ConsumerRecord<String, String> record = await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> pollFor(id), r -> r != null);

        assertThat(json.readTree(record.value()).get("eventType").asString()).isEqualTo("USER_REGISTERED");

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
        DockerClientFactory.instance().client().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unpauseBroker() {
        DockerClientFactory.instance().client().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private Integer unpublishedRows(String aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND published_at IS NULL",
                Integer.class, aggregateId);
    }

    private org.springframework.test.web.servlet.ResultActions register(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Sigiriya_2026","fullName":"Kafka Test","role":"TRAVELER"}"""
                        .formatted(email)));
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
}
