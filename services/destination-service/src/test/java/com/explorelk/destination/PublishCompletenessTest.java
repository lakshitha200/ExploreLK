package com.explorelk.destination;

import com.explorelk.destination.attraction.Attraction;
import com.explorelk.destination.category.Category;
import com.explorelk.destination.destination.Destination;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What "finished enough to show a traveler" means.
 *
 * <p>These rules are checked on the publish transition and nowhere else, on
 * purpose: enforcing them at creation would stop an admin saving half-written
 * work, which is the entire point of a draft.
 */
class PublishCompletenessTest {

    private static final Category NATURE = Category.builder()
            .code("NATURE").name("Nature").sortOrder((short) 10).build();

    @Nested
    class Destinations {

        private Destination complete() {
            return Destination.builder()
                    .slug("ella").name("Ella")
                    .district("Badulla").province("Uva")
                    .summary("Misty hill town of tea estates.")
                    .latitude(new BigDecimal("6.866700"))
                    .longitude(new BigDecimal("81.046600"))
                    .categories(Set.of(NATURE))
                    .build();
        }

        @Test
        @DisplayName("a fully filled destination is publishable")
        void completeIsPublishable() {
            assertThat(complete().isCompleteForPublishing()).isTrue();
        }

        @Test
        @DisplayName("a bare draft is not")
        void bareDraftIsNot() {
            assertThat(Destination.builder().slug("x").name("X").build()
                    .isCompleteForPublishing()).isFalse();
        }

        @Test
        @DisplayName("every required field is genuinely required")
        void eachFieldIsRequired() {
            // Asserted field by field rather than as one negative case, so that
            // relaxing any single rule fails a test that names it.
            assertThat(mutate(d -> d.setDistrict(null))).isFalse();
            assertThat(mutate(d -> d.setProvince(null))).isFalse();
            assertThat(mutate(d -> d.setSummary(null))).isFalse();
            assertThat(mutate(d -> d.setLatitude(null))).isFalse();
            assertThat(mutate(d -> d.setLongitude(null))).isFalse();
            assertThat(mutate(d -> d.setCategories(Set.of()))).isFalse();
        }

        @Test
        @DisplayName("whitespace is not content")
        void blankIsNotFilledIn() {
            // A summary of "   " passes a null check and renders as an empty card.
            assertThat(mutate(d -> d.setSummary("   "))).isFalse();
            assertThat(mutate(d -> d.setDistrict(" "))).isFalse();
            assertThat(mutate(d -> d.setName(""))).isFalse();
        }

        private boolean mutate(java.util.function.Consumer<Destination> change) {
            Destination destination = complete();
            change.accept(destination);
            return destination.isCompleteForPublishing();
        }
    }

    @Nested
    class Attractions {

        private Attraction complete() {
            return Attraction.builder()
                    .slug("nine-arches-bridge").name("Nine Arches Bridge")
                    .summary("A colonial-era viaduct built without steel.")
                    .latitude(new BigDecimal("6.876700"))
                    .longitude(new BigDecimal("81.060200"))
                    .visitDurationMinutes((short) 90)
                    .free(true)
                    .build();
        }

        @Test
        @DisplayName("a fully filled attraction is publishable")
        void completeIsPublishable() {
            assertThat(complete().isCompleteForPublishing()).isTrue();
        }

        @Test
        @DisplayName("visit duration is required — the Itinerary Service plans with it")
        void durationIsRequired() {
            // An attraction with no duration cannot be packed into a day, so
            // publishing one puts unusable content in front of the planner.
            assertThat(mutate(a -> a.setVisitDurationMinutes(null))).isFalse();
        }

        @Test
        @DisplayName("cost must be answered one way or the other")
        void costMustBeKnown() {
            // Neither free nor priced means "we do not know", which a traveler
            // deciding what to do today cannot act on.
            assertThat(mutate(a -> { a.setFree(false); a.setEntranceFee(null); })).isFalse();

            // Either answer alone is enough.
            assertThat(mutate(a -> { a.setFree(false); a.setEntranceFee(new BigDecimal("500")); })).isTrue();
            assertThat(mutate(a -> { a.setFree(true); a.setEntranceFee(null); })).isTrue();

            // Free with a zero fee is the same fact twice, not a contradiction.
            assertThat(mutate(a -> { a.setFree(true); a.setEntranceFee(BigDecimal.ZERO); })).isTrue();
        }

        @Test
        @DisplayName("location and summary are required")
        void locationAndSummaryRequired() {
            assertThat(mutate(a -> a.setLatitude(null))).isFalse();
            assertThat(mutate(a -> a.setLongitude(null))).isFalse();
            assertThat(mutate(a -> a.setSummary(" "))).isFalse();
        }

        private boolean mutate(java.util.function.Consumer<Attraction> change) {
            Attraction attraction = complete();
            change.accept(attraction);
            return attraction.isCompleteForPublishing();
        }
    }
}
