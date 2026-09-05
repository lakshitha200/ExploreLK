package com.explorelk.destination;

import com.explorelk.destination.support.IntegrationTest;
import com.explorelk.destination.support.TestContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proximity search — the only part of the service that touches PostGIS.
 *
 * <p>Distances are asserted against real coordinates from the seed, so a broken
 * projection or a swapped axis shows up as a wrong number rather than as a test
 * that still passes on an empty list.
 */
@IntegrationTest
class NearbyIT extends TestContainers {

    /** Ella, as a person writes it: latitude then longitude. */
    private static final String ELLA_LAT = "6.8667";
    private static final String ELLA_LNG = "81.0466";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("returns nearest first, with a distance on every row")
    void ordersOutward() throws Exception {
        mvc.perform(get("/api/v1/destinations/nearby")
                        .param("lat", ELLA_LAT).param("lng", ELLA_LNG).param("radiusKm", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ella"))
                .andExpect(jsonPath("$[0].distanceKm").value(0.0))
                .andExpect(jsonPath("$[1].name").value("Nuwara Eliya"))
                .andExpect(jsonPath("$[1].distanceKm").value(
                        org.hamcrest.Matchers.closeTo(29.9, 0.5)));
    }

    @Test
    @DisplayName("the radius actually excludes things")
    void radiusExcludes() throws Exception {
        // Nuwara Eliya is 29.9 km away, so a 10 km radius must not reach it.
        mvc.perform(get("/api/v1/destinations/nearby")
                        .param("lat", ELLA_LAT).param("lng", ELLA_LNG).param("radiusKm", "10"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Ella"));
    }

    @Test
    @DisplayName("attraction distances match what was measured against the schema")
    void attractionDistances() throws Exception {
        // Nine Arches Bridge was measured at 1.87 km from Ella by hand when the
        // schema was built. The endpoint has to agree with the schema.
        mvc.perform(get("/api/v1/attractions/nearby")
                        .param("lat", ELLA_LAT).param("lng", ELLA_LNG).param("radiusKm", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Nine Arches Bridge')].distanceKm")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.closeTo(1.87, 0.05))))
                // Every row carries its destination, so a map pin reads on its own.
                .andExpect(jsonPath("$[0].destinationSlug").value("ella"));
    }

    @Test
    @DisplayName("swapping lat and lng returns nothing — the classic PostGIS bug, once, on purpose")
    void swappedCoordinatesFindNothing() throws Exception {
        // 81.0466 is a valid latitude, so nothing rejects this. Sri Lanka simply
        // moves to the middle of Somalia and the result set is empty. The only
        // defence is that the x/y conversion happens in exactly one place.
        mvc.perform(get("/api/v1/destinations/nearby")
                        .param("lat", ELLA_LNG).param("lng", ELLA_LAT).param("radiusKm", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("radius and limit are clamped, not obeyed")
    void clampsAbsurdRequests() throws Exception {
        mvc.perform(get("/api/v1/destinations/nearby")
                        .param("lat", ELLA_LAT).param("lng", ELLA_LNG)
                        .param("radiusKm", "99999").param("limit", "100000"))
                .andExpect(status().isOk())
                // Clamped to 100 km, which reaches five of the ten seeded places.
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @DisplayName("an out-of-range coordinate is a field error")
    void rejectsBadCoordinates() throws Exception {
        mvc.perform(get("/api/v1/destinations/nearby").param("lat", "200").param("lng", "81"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lat"));
    }

    @Test
    @DisplayName("a missing coordinate is a field error, not a 500")
    void rejectsMissingCoordinates() throws Exception {
        mvc.perform(get("/api/v1/destinations/nearby").param("lat", ELLA_LAT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lng"));
    }

    @Test
    @Transactional
    @DisplayName("the query plans as an index scan on the GiST index, not a sequential scan")
    void usesTheGistIndex() {
        // The whole reason the geog column exists. Without this assertion the
        // endpoint could quietly degrade to a table scan the day someone rewrites
        // the query, and every test above would still pass.
        @SuppressWarnings("unchecked")
        List<String> plan = entityManager.createNativeQuery("""
                        EXPLAIN
                        SELECT d.id
                        FROM destinations d
                        WHERE d.status = 'PUBLISHED'
                          AND d.geog IS NOT NULL
                          AND ST_DWithin(d.geog,
                                CAST(ST_SetSRID(ST_MakePoint(81.0466, 6.8667), 4326) AS geography),
                                100000)
                        ORDER BY d.geog <-> CAST(ST_SetSRID(ST_MakePoint(81.0466, 6.8667), 4326) AS geography)
                        LIMIT 20
                        """)
                .getResultList();

        String planText = String.join("\n", plan);
        assertThat(planText)
                .as("query plan:%n%s", planText)
                .contains("Index Scan")
                .contains("ix_destinations_geog");
    }
}
