package com.explorelk.destination;

import com.explorelk.destination.support.IntegrationTest;
import com.explorelk.destination.support.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The traveler-facing catalog, against the real seed data and with no token
 * anywhere.
 *
 * <p>Asserting on the actual Sri Lankan content rather than invented fixtures is
 * deliberate: Trip and Itinerary development both build against this seed, so a
 * change that breaks the product breaks this build too.
 */
@IntegrationTest
class PublicCatalogIT extends TestContainers {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("browsing needs no token")
    void listIsPublic() throws Exception {
        mvc.perform(get("/api/v1/destinations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(10))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("search matches the district as well as the name")
    void searchCoversDistrict() throws Exception {
        // "badulla" appears in no destination name — only in Ella's district.
        mvc.perform(get("/api/v1/destinations").param("search", "badulla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Ella"));
    }

    @Test
    @DisplayName("filters compose with AND")
    void filtersCompose() throws Exception {
        mvc.perform(get("/api/v1/destinations").param("category", "BEACH"))
                .andExpect(jsonPath("$.totalItems").value(3));

        mvc.perform(get("/api/v1/destinations")
                        .param("category", "BEACH").param("province", "Southern"))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    @DisplayName("an unknown category is a 400, not an empty page")
    void unknownCategoryIsRejected() throws Exception {
        // An empty page reads as "there are no beaches", and the typo survives to
        // production.
        mvc.perform(get("/api/v1/destinations").param("category", "BEECH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("category"));
    }

    @Test
    @DisplayName("page size is clamped, both ways")
    void pageSizeIsClamped() throws Exception {
        // Uncapped, ?size=1000000 is a free denial-of-service on a public endpoint.
        mvc.perform(get("/api/v1/destinations").param("size", "1000000"))
                .andExpect(jsonPath("$.size").value(100));
        mvc.perform(get("/api/v1/destinations").param("size", "0"))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("an internal column cannot be used as a sort")
    void sortIsAWhitelist() throws Exception {
        String byPopularity = mvc.perform(get("/api/v1/destinations"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/v1/destinations").param("sort", "version"))
                .andExpect(status().isOk())
                .andExpect(content().json(byPopularity));
    }

    @Test
    @DisplayName("a wildcard in the search term is matched literally")
    void searchWildcardsAreEscaped() throws Exception {
        // Unescaped, "%" matches every row — a one-character request that scans
        // the table.
        mvc.perform(get("/api/v1/destinations").param("search", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    @DisplayName("slug and UUID return byte-identical bodies")
    void slugAndUuidAgree() throws Exception {
        String bySlug = mvc.perform(get("/api/v1/destinations/ella"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/v1/destinations/d0000000-0000-4000-8000-000000000004"))
                .andExpect(status().isOk())
                .andExpect(content().json(bySlug, true));
    }

    @Test
    @DisplayName("an unknown identifier is a clean 404 with a traceId, not a stack trace")
    void unknownIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/destinations/atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Not found"));
    }

    @Test
    @DisplayName("a public response never carries status or version")
    void publicResponseHidesInternals() throws Exception {
        // Exposing status invites clients to branch on a value that can only ever
        // be PUBLISHED; version is an optimistic-locking counter.
        mvc.perform(get("/api/v1/destinations/ella"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    @DisplayName("attractions of a destination carry their visit duration")
    void attractionsHaveDurations() throws Exception {
        // The Itinerary Service packs days with this field, so its absence would
        // make the whole list useless to the thing that consumes it.
        mvc.perform(get("/api/v1/destinations/ella/attractions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.name == 'Nine Arches Bridge')].visitDurationMinutes")
                        .value(90));
    }

    @Test
    @DisplayName("the category vocabulary is public, for filter UIs")
    void categoriesArePublic() throws Exception {
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].code").value("NATURE"));
    }
}
