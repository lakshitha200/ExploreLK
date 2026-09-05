package com.explorelk.destination.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cache key that does not normalize is a cache that fills with synonyms and
 * hits on almost none of them. These tests pin the equivalences.
 */
class DestinationListKeyTest {

    private static final DestinationQuery EMPTY = DestinationQuery.of(null, null, null, null);

    @Test
    @DisplayName("unset, zero and the default page size are one request")
    void normalizesPaging() {
        String unset = DestinationListKey.of(EMPTY, 0, 0, null, null);
        String explicit = DestinationListKey.of(EMPTY, 0, 20, null, null);

        assertThat(unset).isEqualTo(explicit);
    }

    @Test
    @DisplayName("an oversized page size collapses onto the clamped one")
    void normalizesOversizedPage() {
        assertThat(DestinationListKey.of(EMPTY, 0, 1_000_000, null, null))
                .isEqualTo(DestinationListKey.of(EMPTY, 0, 100, null, null));
    }

    @Test
    @DisplayName("a negative page is page zero")
    void normalizesNegativePage() {
        assertThat(DestinationListKey.of(EMPTY, -5, 20, null, null))
                .isEqualTo(DestinationListKey.of(EMPTY, 0, 20, null, null));
    }

    @Test
    @DisplayName("an unknown sort collapses onto the default it falls back to")
    void normalizesUnknownSort() {
        // ?sort=passwordHash and no sort at all produce identical results, so
        // they must not occupy two cache entries.
        assertThat(DestinationListKey.of(EMPTY, 0, 20, "passwordHash", null))
                .isEqualTo(DestinationListKey.of(EMPTY, 0, 20, "popularity", null));
    }

    @Test
    @DisplayName("direction casing and unparseable values collapse too")
    void normalizesDirection() {
        assertThat(DestinationListKey.of(EMPTY, 0, 20, "name", "ASC"))
                .isEqualTo(DestinationListKey.of(EMPTY, 0, 20, "name", "asc"));
        assertThat(DestinationListKey.of(EMPTY, 0, 20, "name", "sideways"))
                .isEqualTo(DestinationListKey.of(EMPTY, 0, 20, "name", null));
    }

    @Test
    @DisplayName("filter casing does not create separate entries")
    void normalizesFilterCasing() {
        // The filters themselves match case-insensitively, so ?district=Badulla
        // and ?district=badulla return the same rows.
        assertThat(DestinationListKey.of(DestinationQuery.of("ELL", "beach", "Badulla", "Uva"), 0, 20, null, null))
                .isEqualTo(DestinationListKey.of(DestinationQuery.of("ell", "BEACH", "badulla", "uva"), 0, 20, null, null));
    }

    @Test
    @DisplayName("genuinely different requests still get different keys")
    void doesNotOverNormalize() {
        // The failure mode on this side is far worse than a low hit rate: two
        // different result sets sharing a key means one is served for the other.
        String base = DestinationListKey.of(EMPTY, 0, 20, null, null);

        assertThat(DestinationListKey.of(EMPTY, 1, 20, null, null)).isNotEqualTo(base);
        assertThat(DestinationListKey.of(EMPTY, 0, 21, null, null)).isNotEqualTo(base);
        assertThat(DestinationListKey.of(EMPTY, 0, 20, "name", null)).isNotEqualTo(base);
        assertThat(DestinationListKey.of(DestinationQuery.of("ella", null, null, null), 0, 20, null, null))
                .isNotEqualTo(base);
        assertThat(DestinationListKey.of(DestinationQuery.of(null, "BEACH", null, null), 0, 20, null, null))
                .isNotEqualTo(base);
        assertThat(DestinationListKey.of(DestinationQuery.of(null, null, "Galle", null), 0, 20, null, null))
                .isNotEqualTo(base);
        assertThat(DestinationListKey.of(DestinationQuery.of(null, null, null, "Southern"), 0, 20, null, null))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("a filter in one position is not the same as the same text in another")
    void fieldsDoNotBleedTogether() {
        // A separator-free key would make ("ab", "c") and ("a", "bc") collide.
        assertThat(DestinationListKey.of(DestinationQuery.of(null, null, "ab", "c"), 0, 20, null, null))
                .isNotEqualTo(DestinationListKey.of(DestinationQuery.of(null, null, "a", "bc"), 0, 20, null, null));
    }
}
