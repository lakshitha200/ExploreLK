package com.explorelk.destination;

import com.explorelk.destination.support.IntegrationTest;
import com.explorelk.destination.support.StubAuthServer;
import com.explorelk.destination.support.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin write paths: lifecycle, slug generation, optimistic locking, and the
 * rule that nothing is ever deleted.
 *
 * <p>Every test creates its own content with a unique name, so they neither
 * depend on ordering nor disturb the seeded catalog the public tests assert
 * against.
 */
@IntegrationTest
class AdminCatalogIT extends TestContainers {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    // ── Creation and publication ─────────────────────────────────────────────

    @Test
    @DisplayName("a created destination lands as a DRAFT, invisible to travelers")
    void createLandsAsDraft() throws Exception {
        JsonNode created = create("Trincomalee Harbour");

        assertThat(created.get("status").asString()).isEqualTo("DRAFT");
        assertThat(created.get("slug").asString()).isEqualTo("trincomalee-harbour");
        assertThat(created.get("version").asInt()).isZero();

        // Publishing is a transition with rules, never a field a client sets on
        // the way in.
        mvc.perform(get("/api/v1/destinations/trincomalee-harbour"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("publishing makes it public; archiving takes it away again")
    void lifecycleControlsVisibility() throws Exception {
        JsonNode created = create("Kalpitiya Lagoon");
        String id = created.get("id").asString();
        String slug = created.get("slug").asString();

        changeStatus(id, "PUBLISHED").andExpect(status().isOk());
        mvc.perform(get("/api/v1/destinations/" + slug)).andExpect(status().isOk());

        changeStatus(id, "ARCHIVED").andExpect(status().isOk());
        mvc.perform(get("/api/v1/destinations/" + slug)).andExpect(status().isNotFound());

        // The row survives, and its id still resolves — Trip and Itinerary hold
        // these ids in their own databases with no foreign key to protect them.
        mvc.perform(get("/api/v1/admin/destinations/" + id).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("DELETE archives, and archiving twice is not an error")
    void deleteArchivesIdempotently() throws Exception {
        String id = create("Pigeon Island").get("id").asString();

        mvc.perform(delete("/api/v1/admin/destinations/" + id).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNoContent());
        // A client retrying after a dropped response must not be punished for
        // succeeding twice.
        mvc.perform(delete("/api/v1/admin/destinations/" + id).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/admin/destinations/" + id).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    // ── The rules that refuse things ─────────────────────────────────────────

    @Test
    @DisplayName("publishing incomplete content is a 409 that names the reason")
    void incompleteCannotBePublished() throws Exception {
        String id = createBare("Half Written Place").get("id").asString();

        changeStatus(id, "PUBLISHED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCOMPLETE_FOR_PUBLISH"));
    }

    @Test
    @DisplayName("archived content cannot go straight back to published")
    void archivedMustBeRestoredFirst() throws Exception {
        String id = create("Delft Island").get("id").asString();
        changeStatus(id, "ARCHIVED").andExpect(status().isOk());

        changeStatus(id, "PUBLISHED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // Restoring to a draft first forces someone to look at it.
        changeStatus(id, "DRAFT").andExpect(status().isOk());
        changeStatus(id, "PUBLISHED").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a colliding slug is refused rather than silently suffixed")
    void slugCollisionIsRejected() throws Exception {
        // "ella-2" would be created silently, live in a URL forever, and be
        // indistinguishable from "ella" in any admin list.
        mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ella\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLUG_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("a half-set coordinate is a field error, not a place in the ocean")
    void halfCoordinateIsRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lonely Latitude\",\"latitude\":6.9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("longitude"));
    }

    @Test
    @DisplayName("an unknown category rejects the whole write")
    void unknownCategoryRejectsTheWrite() throws Exception {
        // Saving ["BEACH","BEECH"] as a single BEACH tag is the kind of partial
        // success discovered months later by a traveler who cannot find the place.
        mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Typo Town\",\"categories\":[\"BEACH\",\"BEECH\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("categories"));
    }

    @Test
    @DisplayName("a client cannot set the slug or the status on the way in")
    void clientCannotSetSlugOrStatus() throws Exception {
        // fail-on-unknown-properties turns an attempt into a 400 rather than a
        // silently ignored field, so nobody believes it worked.
        mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sneaky\",\"slug\":\"chosen-by-me\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ── Optimistic locking ───────────────────────────────────────────────────

    @Test
    @DisplayName("an edit against a stale version is a 409")
    void staleVersionConflicts() throws Exception {
        String id = create("Wilpattu Gate").get("id").asString();

        // First writer wins.
        patchDestination(id, "{\"summary\":\"First writer.\",\"version\":0}")
                .andExpect(status().isOk());

        // Second writer was looking at version 0 too.
        patchDestination(id, "{\"summary\":\"Second writer.\",\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("the version in a response is the one the edit produced")
    void responseCarriesTheNewVersion() throws Exception {
        String id = create("Madu River").get("id").asString();

        JsonNode first = json.readTree(patchDestination(id, "{\"popularityScore\":10,\"version\":0}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        // @Version is written BY the flush, so mapping the entity beforehand
        // returns the version the edit started from — and the admin's very next
        // save is then rejected as a conflict with themselves.
        assertThat(first.get("version").asInt()).isEqualTo(1);

        patchDestination(id, "{\"popularityScore\":11,\"version\":" + first.get("version").asInt() + "}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("omitting the version skips the check, for scripted fixes")
    void versionIsOptional() throws Exception {
        String id = create("Knuckles Range").get("id").asString();
        patchDestination(id, "{\"summary\":\"No version supplied.\"}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a partial update leaves omitted fields alone")
    void patchIsPartial() throws Exception {
        String id = create("Horton Plains Gate").get("id").asString();

        mvc.perform(patch("/api/v1/admin/destinations/" + id)
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"popularityScore\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popularityScore").value(42))
                .andExpect(jsonPath("$.district").value("Nuwara Eliya"))
                .andExpect(jsonPath("$.province").value("Central"));
    }

    // ── Admin reads ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the admin list shows drafts; the public one never does")
    void adminSeesDrafts() throws Exception {
        create("Secret Draft Place");

        mvc.perform(get("/api/v1/admin/destinations")
                        .param("status", "DRAFT")
                        .header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.slug == 'secret-draft-place')]").exists());

        mvc.perform(get("/api/v1/destinations").param("size", "100"))
                .andExpect(jsonPath("$.items[?(@.slug == 'secret-draft-place')]").doesNotExist());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A complete destination, ready to publish. */
    private JsonNode create(String name) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "name", name,
                "district", "Nuwara Eliya",
                "province", "Central",
                "summary", "Seeded by an integration test.",
                "latitude", 6.9497,
                "longitude", 80.7891,
                "categories", java.util.List.of("NATURE")));

        return json.readTree(mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    /** A bare draft — name only, deliberately not publishable. */
    private JsonNode createBare(String name) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(String id, String target) throws Exception {
        return mvc.perform(patch("/api/v1/admin/destinations/" + id + "/status")
                .header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + target + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions patchDestination(String id, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = patch("/api/v1/admin/destinations/" + id)
                .header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mvc.perform(request);
    }

    private static String admin() {
        return "Bearer " + StubAuthServer.tokenFor("SUPER_ADMIN");
    }
}
