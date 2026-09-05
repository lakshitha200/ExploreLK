package com.explorelk.destination;

import com.explorelk.destination.support.StubAuthServer;
import com.explorelk.destination.support.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis down must make this service <em>slower</em>, never broken.
 *
 * <p>The catalog keeps nothing that exists only in Redis: every cached value is
 * derived from a row in Postgres. That is what makes falling through to the
 * database a correct answer rather than a way of hiding a bug — and it is the
 * reason {@code CacheConfig} installs a {@code CacheErrorHandler} that logs and
 * returns instead of the default one, which rethrows and turns a cache outage
 * into a catalog outage.
 *
 * <p><strong>The port here is unreachable on purpose.</strong> Stopping the
 * shared Redis container would break every other class in the suite, and
 * pointing this one context at a closed port reproduces the same thing more
 * sharply: <em>every</em> cache operation fails, on every request, for the whole
 * class. Lettuce connects lazily, so the context still starts; each read then
 * fails at the cache and is served from Postgres.
 *
 * <p>This is the test that would have caught declaring the error handler as a
 * plain {@code @Bean}. Spring reads it from {@code CachingConfigurer} and
 * ignores a bare bean of that type, so the mistake passes every other test in
 * this suite — all of which run with Redis up — and only surfaces in production
 * the first time the cache goes away.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class CacheOutageIT extends TestContainers {

    /**
     * Port 1 is reserved, privileged, and nothing listens there. The connection
     * is refused immediately rather than hanging, which is also what the 500 ms
     * Lettuce timeouts in {@code application.yml} are there to guarantee when a
     * real Redis stops answering instead of actively refusing.
     */
    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> 1);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("every cached read path still answers with no Redis at all")
    void readsFallThroughToPostgres() throws Exception {
        // A single destination, by slug and by id — the @Cacheable path.
        mvc.perform(get("/api/v1/destinations/ella"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ella"));

        // A list, whose cache key is built from the whole filter set.
        mvc.perform(get("/api/v1/destinations").param("category", "BEACH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));

        // The two root-level collections. These are the ones that would fail on a
        // serializer mistake rather than a connection one, so they earn their
        // place here as well as in CachingIT.
        mvc.perform(get("/api/v1/destinations/ella/attractions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    @DisplayName("the same read twice is still correct, just uncached")
    void repeatedReadsStayCorrect() throws Exception {
        // With Redis up the second call would never reach Postgres. With Redis
        // gone both do, and the answer must not differ.
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/destinations/ella"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value("ella"));
        }
    }

    @Test
    @DisplayName("writes still commit when their eviction cannot be delivered")
    void writesSurviveAFailedEviction() throws Exception {
        String created = mvc.perform(post("/api/v1/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cache Outage Cove","district":"Puttalam","province":"North Western",
                                 "summary":"Written while the cache was unreachable.",
                                 "latitude":8.0362,"longitude":79.8283,"categories":["BEACH"]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = created.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        // The eviction after this commit has nowhere to go. An error handler that
        // rethrew would turn that into a failed edit — a write rejected because a
        // cache is down, which is the exact inversion of what a cache is for.
        mvc.perform(patch("/api/v1/admin/destinations/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(get("/api/v1/destinations/cache-outage-cove"))
                .andExpect(status().isOk());
    }

    private static String admin() {
        return "Bearer " + StubAuthServer.tokenFor("ADMIN");
    }
}
