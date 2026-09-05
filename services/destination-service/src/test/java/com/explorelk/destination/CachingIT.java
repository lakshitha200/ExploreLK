package com.explorelk.destination;

import com.explorelk.destination.config.CacheConfig;
import com.explorelk.destination.support.IntegrationTest;
import com.explorelk.destination.support.StubAuthServer;
import com.explorelk.destination.support.TestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Caching, asserted through the cache itself rather than by counting SQL.
 *
 * <p>Counting statements is the obvious approach and a brittle one — it breaks
 * whenever an unrelated query is added. Reading the {@link CacheManager} asks the
 * question directly: is this value stored, and is it gone after a write.
 */
@IntegrationTest
class CachingIT extends TestContainers {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void clearCaches() {
        // Tests share one Redis for the whole suite, so each starts from empty.
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("a public read populates the cache")
    void readsArePopulated() throws Exception {
        assertThat(cached(CacheConfig.DESTINATION, "ella")).isNull();

        mvc.perform(get("/api/v1/destinations/ella")).andExpect(status().isOk());

        assertThat(cached(CacheConfig.DESTINATION, "ella")).isNotNull();
    }

    @Test
    @DisplayName("a list, an attraction list and the vocabulary are all cached")
    void everyCachedReadPathWorks() throws Exception {
        mvc.perform(get("/api/v1/destinations")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/destinations/ella/attractions")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());

        assertThat(cached(CacheConfig.ATTRACTIONS_OF, "ella")).isNotNull();
        assertThat(cached(CacheConfig.CATEGORIES, "all")).isNotNull();
    }

    @Test
    @DisplayName("a root-level list survives the round trip")
    void collectionsDeserializeCorrectly() throws Exception {
        // The generic type-embedding serializer writes a root-level array one way
        // and reads it back another, so the second request 500s while the first
        // succeeds. Both endpoints below return a bare list, which is why they are
        // the ones that caught it.
        for (String path : new String[]{"/api/v1/categories", "/api/v1/destinations/ella/attractions"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("an admin edit evicts both the id and the slug entry, and every list")
    void writesEvict() throws Exception {
        String ella = "d0000000-0000-4000-8000-000000000004";

        mvc.perform(get("/api/v1/destinations/ella")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/destinations/" + ella)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/destinations")).andExpect(status().isOk());

        assertThat(cached(CacheConfig.DESTINATION, "ella")).isNotNull();
        assertThat(cached(CacheConfig.DESTINATION, ella)).isNotNull();

        JsonNode before = json.readTree(mvc.perform(get("/api/v1/admin/destinations/" + ella)
                        .header(HttpHeaders.AUTHORIZATION, admin()))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(patch("/api/v1/admin/destinations/" + ella)
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Edited by the caching test.\",\"version\":"
                                + before.get("version").asInt() + "}"))
                .andExpect(status().isOk());

        // A destination is cached under both forms because the public endpoint
        // resolves either, so an edit has to clear two keys, not one.
        assertThat(cached(CacheConfig.DESTINATION, "ella")).isNull();
        assertThat(cached(CacheConfig.DESTINATION, ella)).isNull();

        // And the new value is what a traveler now sees.
        mvc.perform(get("/api/v1/destinations/ella"))
                .andExpect(jsonPath("$.summary").value("Edited by the caching test."));

        // Restore, so the public catalog tests keep asserting on the real seed.
        JsonNode current = json.readTree(mvc.perform(get("/api/v1/admin/destinations/" + ella)
                        .header(HttpHeaders.AUTHORIZATION, admin()))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/api/v1/admin/destinations/" + ella)
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "summary", "Misty hill town of tea estates, short hikes and the most "
                                        + "photographed bridge in the country.",
                                "version", current.get("version").asInt()))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("adding a category clears the vocabulary and every list")
    void categoryWriteEvicts() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        assertThat(cached(CacheConfig.CATEGORIES, "all")).isNotNull();

        mvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CACHE_TEST\",\"name\":\"Cache Test\"}"))
                .andExpect(status().isCreated());

        assertThat(cached(CacheConfig.CATEGORIES, "all")).isNull();
        mvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[?(@.code == 'CACHE_TEST')]").exists());
    }

    @Test
    @DisplayName("proximity results are never cached")
    void nearbyIsNotCached() throws Exception {
        // The key space is every (lat, lng, radius) a phone GPS emits, so the hit
        // rate is near zero and Redis fills with entries nobody reads twice.
        mvc.perform(get("/api/v1/destinations/nearby")
                        .param("lat", "6.8667").param("lng", "81.0466"))
                .andExpect(status().isOk());

        assertThat(cacheManager.getCacheNames())
                .noneMatch(name -> name.toLowerCase().contains("nearby"));
    }

    private Object cached(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        assertThat(cache).as("cache '%s' is configured", cacheName).isNotNull();
        Cache.ValueWrapper wrapper = cache.get(key);
        return wrapper == null ? null : wrapper.get();
    }

    private static String admin() {
        return "Bearer " + StubAuthServer.tokenFor("SUPER_ADMIN");
    }
}
