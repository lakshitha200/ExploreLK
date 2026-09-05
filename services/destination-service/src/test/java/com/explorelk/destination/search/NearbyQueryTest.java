package com.explorelk.destination.search;

import com.explorelk.destination.common.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code /nearby} is public and unauthenticated, so the clamps are the only
 * thing standing between a query string and "sort the whole table by distance".
 *
 * <p>The asymmetry is the interesting part and is asserted deliberately:
 * coordinates are <em>rejected</em>, radius and limit are <em>clamped</em>.
 */
class NearbyQueryTest {

    @Test
    @DisplayName("keeps a valid request untouched")
    void passesValidValues() {
        NearbyQuery query = NearbyQuery.of(6.8667, 81.0466, 10.0, 5);

        assertThat(query.latitude()).isEqualTo(6.8667);
        assertThat(query.longitude()).isEqualTo(81.0466);
        assertThat(query.radiusKm()).isEqualTo(10.0);
        assertThat(query.limit()).isEqualTo(5);
    }

    @Test
    @DisplayName("PostGIS works in metres")
    void convertsRadiusToMetres() {
        assertThat(NearbyQuery.of(6.8667, 81.0466, 10.0, 5).radiusMeters())
                .isCloseTo(10_000.0, within(0.001));
    }

    @Test
    @DisplayName("clamps an absurd radius and limit instead of failing")
    void clampsUpperBounds() {
        // A 20,000 km radius is a reasonable thing to ask and an unreasonable
        // thing to answer, so the honest response is the largest this serves.
        NearbyQuery query = NearbyQuery.of(6.8667, 81.0466, 99_999.0, 100_000);

        assertThat(query.radiusKm()).isEqualTo(NearbyQuery.MAX_RADIUS_KM);
        assertThat(query.limit()).isEqualTo(NearbyQuery.MAX_LIMIT);
    }

    @Test
    @DisplayName("missing, zero and negative all mean 'unset', not 'empty'")
    void fallsBackToDefaults() {
        for (Double radius : new Double[]{null, 0.0, -5.0}) {
            assertThat(NearbyQuery.of(6.8667, 81.0466, radius, null).radiusKm())
                    .as("radius %s", radius)
                    .isEqualTo(NearbyQuery.DEFAULT_RADIUS_KM);
        }
        for (Integer limit : new Integer[]{null, 0, -5}) {
            assertThat(NearbyQuery.of(6.8667, 81.0466, null, limit).limit())
                    .as("limit %s", limit)
                    .isEqualTo(NearbyQuery.DEFAULT_LIMIT);
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {90.001, -90.001, 200, -200})
    @DisplayName("an out-of-range latitude is rejected, never clamped")
    void rejectsBadLatitude(double latitude) {
        // Clamping 200 to 90 would silently answer for the North Pole. A caller
        // that sends this has a bug and needs to be told.
        assertThatThrownBy(() -> NearbyQuery.of(latitude, 81.0466, null, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .singleElement()
                        .satisfies(fe -> assertThat(fe.field()).isEqualTo("lat")));
    }

    @ParameterizedTest
    @ValueSource(doubles = {180.001, -180.001, 400})
    @DisplayName("an out-of-range longitude is rejected too")
    void rejectsBadLongitude(double longitude) {
        assertThatThrownBy(() -> NearbyQuery.of(6.8667, longitude, null, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .singleElement()
                        .satisfies(fe -> assertThat(fe.field()).isEqualTo("lng")));
    }

    @Test
    @DisplayName("the poles and the antimeridian are valid, not off-by-one errors")
    void acceptsExactBounds() {
        assertThat(NearbyQuery.of(90, 180, null, null)).isNotNull();
        assertThat(NearbyQuery.of(-90, -180, null, null)).isNotNull();
    }

    @Test
    @DisplayName("swapping lat and lng passes validation — which is why it is a real bug")
    void swappedSriLankanCoordinatesAreStillValid() {
        // 81.0466 is a legal latitude, so nothing here can catch the classic
        // swap. It surfaces as an empty result set, and the only defence is that
        // the conversion to PostGIS x/y order happens in exactly one place.
        assertThat(NearbyQuery.of(81.0466, 6.8667, null, null)).isNotNull();
    }
}
