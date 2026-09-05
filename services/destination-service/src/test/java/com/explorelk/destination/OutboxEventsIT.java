package com.explorelk.destination;

import com.explorelk.destination.support.IntegrationTest;
import com.explorelk.destination.support.StubAuthServer;
import com.explorelk.destination.support.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which change writes which event — all six of them, against the outbox table.
 *
 * <p><strong>No broker here on purpose.</strong> {@link OutboxKafkaIT} already
 * proves a committed row reaches Kafka, and that proof does not get stronger by
 * being repeated six times at twenty seconds of container startup each. What is
 * left to check is the mapping in §7 — that publishing emits
 * {@code *_PUBLISHED}, editing published content emits {@code *_UPDATED},
 * archiving emits {@code *_ARCHIVED}, and that work on a draft emits nothing at
 * all. That is a question about rows in {@code outbox_events}, and reading them
 * directly is both faster and a sharper assertion than watching a topic.
 *
 * <p>The rule the negative cases protect is the one that keeps the stream worth
 * subscribing to: <strong>only changes to published content are announced.</strong>
 * A consumer that receives a stream of edits to drafts it has never seen learns
 * to ignore the stream, and then misses the event that mattered.
 */
@IntegrationTest
class OutboxEventsIT extends TestContainers {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    // ── Destinations ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a destination emits PUBLISHED, then UPDATED, then ARCHIVED")
    void destinationLifecycleEmitsThreeEvents() throws Exception {
        String id = createDestination("Outbox Bay").get("id").asString();

        // A draft is nobody's business yet.
        assertThat(eventsFor(id)).isEmpty();

        setStatus("destinations", id, "PUBLISHED");
        assertThat(eventsFor(id)).containsExactly("DESTINATION_PUBLISHED");

        patchDestination(id, "{\"summary\":\"Rewritten after publication.\"}")
                .andExpect(status().isOk());
        assertThat(eventsFor(id)).containsExactly("DESTINATION_PUBLISHED", "DESTINATION_UPDATED");

        setStatus("destinations", id, "ARCHIVED");
        assertThat(eventsFor(id)).containsExactly(
                "DESTINATION_PUBLISHED", "DESTINATION_UPDATED", "DESTINATION_ARCHIVED");

        assertThat(aggregateTypeOf(id)).isEqualTo("DESTINATION");
    }

    @Test
    @DisplayName("editing a draft destination announces nothing")
    void editingADraftIsSilent() throws Exception {
        String id = createDestination("Outbox Draft Bay").get("id").asString();

        patchDestination(id, "{\"summary\":\"Still being written.\"}")
                .andExpect(status().isOk());

        assertThat(eventsFor(id)).isEmpty();
    }

    @Test
    @DisplayName("DELETE archives, and the archive is announced like any other")
    void deleteEmitsArchived() throws Exception {
        String id = createDestination("Outbox Retired Bay").get("id").asString();
        setStatus("destinations", id, "PUBLISHED");

        mvc.perform(delete("/api/v1/admin/destinations/" + id)
                        .header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNoContent());

        // Trip and Itinerary hold this id in their own databases with no foreign
        // key to protect them; this event is the only way they ever find out.
        assertThat(eventsFor(id)).containsExactly("DESTINATION_PUBLISHED", "DESTINATION_ARCHIVED");
    }

    // ── Attractions ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an attraction emits PUBLISHED, then UPDATED, then ARCHIVED")
    void attractionLifecycleEmitsThreeEvents() throws Exception {
        String destinationId = createDestination("Outbox Host Town").get("id").asString();
        String id = createAttraction(destinationId, "Outbox Falls").get("id").asString();

        assertThat(eventsFor(id)).isEmpty();

        setStatus("attractions", id, "PUBLISHED");
        assertThat(eventsFor(id)).containsExactly("ATTRACTION_PUBLISHED");

        // The event that matters most in the whole service: an itinerary built
        // around a 90-minute visit is quietly wrong once that becomes 180.
        mvc.perform(patch("/api/v1/admin/attractions/" + id)
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitDurationMinutes\":180}"))
                .andExpect(status().isOk());
        assertThat(eventsFor(id)).containsExactly("ATTRACTION_PUBLISHED", "ATTRACTION_UPDATED");

        setStatus("attractions", id, "ARCHIVED");
        assertThat(eventsFor(id)).containsExactly(
                "ATTRACTION_PUBLISHED", "ATTRACTION_UPDATED", "ATTRACTION_ARCHIVED");

        assertThat(aggregateTypeOf(id)).isEqualTo("ATTRACTION");
    }

    @Test
    @DisplayName("every event carries a payload a consumer can act on alone")
    void payloadIsSelfContained() throws Exception {
        String id = createDestination("Outbox Payload Cove").get("id").asString();
        setStatus("destinations", id, "PUBLISHED");

        JsonNode event = json.readTree(jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ?::uuid", String.class, id));

        assertThat(event.get("eventId").asString()).isNotBlank();
        assertThat(event.get("occurredAt").asString()).isNotBlank();
        assertThat(event.get("eventType").asString()).isEqualTo("DESTINATION_PUBLISHED");

        // A consumer that has to call back to find out what changed has gained
        // nothing from being told.
        JsonNode destination = event.get("destination");
        assertThat(destination.get("id").asString()).isEqualTo(id);
        // The helper appends a random suffix so parallel runs cannot collide on
        // the slug, hence startsWith rather than an exact match.
        assertThat(destination.get("name").asString()).startsWith("Outbox Payload Cove");
        assertThat(destination.get("status").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("a row waits unpublished until a publisher takes it")
    void rowsStartUnpublished() throws Exception {
        String id = createDestination("Outbox Pending Point").get("id").asString();
        setStatus("destinations", id, "PUBLISHED");

        // Events are disabled in the test profile, so nothing drains the table
        // here — which is exactly the state this service is in whenever Kafka is
        // down. The write succeeded anyway, and the event is waiting.
        Integer unpublished = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND published_at IS NULL",
                Integer.class, id);
        assertThat(unpublished).isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Event types for one aggregate, oldest first — the order they happened in. */
    private List<String> eventsFor(String aggregateId) {
        return jdbc.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ?::uuid ORDER BY created_at, event_type",
                String.class, aggregateId);
    }

    private String aggregateTypeOf(String aggregateId) {
        return jdbc.queryForList(
                        "SELECT DISTINCT aggregate_type FROM outbox_events WHERE aggregate_id = ?::uuid",
                        String.class, aggregateId)
                .get(0);   // getFirst() is Java 21; this build targets 17
    }

    private JsonNode createDestination(String name) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", name + " " + UUID.randomUUID().toString().substring(0, 6),
                "district", "Matale",
                "province", "Central",
                "summary", "Written by the outbox integration test.",
                "latitude", 7.4675,
                "longitude", 80.6234,
                "categories", List.of("NATURE")));

        return json.readTree(mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode createAttraction(String destinationId, String name) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", name + " " + UUID.randomUUID().toString().substring(0, 6),
                "summary", "Written by the outbox integration test.",
                "visitDurationMinutes", 90,
                // isCompleteForPublishing() demands a price or an explicit "free":
                // an attraction published with neither leaves a traveler unable to
                // tell whether it costs nothing or nobody filled the field in.
                "free", true,
                "latitude", 7.4675,
                "longitude", 80.6234));

        return json.readTree(mvc.perform(
                        post("/api/v1/admin/destinations/" + destinationId + "/attractions")
                                .header(HttpHeaders.AUTHORIZATION, admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private void setStatus(String collection, String id, String target) throws Exception {
        mvc.perform(patch("/api/v1/admin/" + collection + "/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + target + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions patchDestination(String id, String body) throws Exception {
        return mvc.perform(patch("/api/v1/admin/destinations/" + id)
                .header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String admin() {
        return "Bearer " + StubAuthServer.tokenFor("ADMIN");
    }
}
