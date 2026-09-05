package com.explorelk.destination.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sort is a whitelist on a public endpoint, so the tests that matter are the
 * ones about what it refuses.
 */
class DestinationSortTest {

    @ParameterizedTest
    @ValueSource(strings = {"popularity", "POPULARITY", "  Popularity  ", "name", "createdAt"})
    @DisplayName("accepts the public names, case and space insensitively")
    void acceptsPublicNames(String value) {
        assertThat(DestinationSort.from(value)).isNotNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"passwordHash", "version", "status", "id", "", "   ", "'; DROP TABLE"})
    @DisplayName("anything not on the whitelist falls back to popularity")
    void unknownNamesFallBack(String value) {
        // Spring's Pageable resolver would happily sort by any property named in
        // the query string. On a public endpoint that lets a caller order by an
        // internal column, probe whether it exists, or force an unindexed sort.
        assertThat(DestinationSort.from(value)).isEqualTo(DestinationSort.POPULARITY);
    }

    @Test
    @DisplayName("every sort carries name as a tiebreak, so paging is stable")
    void alwaysHasATotalOrdering() {
        // Without a total ordering, rows with equal popularity come back in
        // whatever order Postgres chooses, and that differs between pages — so an
        // item can appear on both page 1 and page 2, or on neither.
        Sort sort = DestinationSort.POPULARITY.toSort(null);

        assertThat(sort).hasSize(2);
        assertThat(sort.getOrderFor("popularityScore")).isNotNull();
        assertThat(sort.getOrderFor("name")).isNotNull();
    }

    @Test
    @DisplayName("sorting by name needs no tiebreak, because it already is one")
    void nameSortIsAlreadyTotal() {
        assertThat(DestinationSort.NAME.toSort(null)).hasSize(1);
    }

    @Test
    @DisplayName("each sort has a sensible default direction")
    void defaultDirections() {
        // Best first for popularity and newest first for created; A-Z for name.
        assertThat(DestinationSort.POPULARITY.toSort(null).getOrderFor("popularityScore").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(DestinationSort.CREATED.toSort(null).getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(DestinationSort.NAME.toSort(null).getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("an explicit direction wins; an unparseable one does not")
    void directionOverride() {
        assertThat(DestinationSort.POPULARITY.toSort("asc").getOrderFor("popularityScore").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(DestinationSort.POPULARITY.toSort("sideways").getOrderFor("popularityScore").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }
}
