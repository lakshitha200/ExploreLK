package com.explorelk.destination.attraction.dto;

import com.explorelk.destination.attraction.Attraction;
import com.explorelk.destination.category.Category;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * An attraction as a traveler sees it.
 *
 * <p>{@code visitDurationMinutes} is the field the rest of the platform cares
 * about most — the Itinerary Service packs attractions into days using it —
 * which is why publishing requires it.
 *
 * <p>No {@code status}: a public response is always PUBLISHED, and including it
 * invites clients to branch on a value that can only have one meaning.
 *
 * @param free        the definitive answer on cost. A null {@code entranceFee}
 *                    means "not recorded", which is a different fact from free
 *                    and is shown differently
 * @param openingHours the stored JSONB, embedded as an object rather than as a
 *                    quoted string — see the note on the field
 */
public record AttractionResponse(
        UUID id,
        UUID destinationId,
        String destinationSlug,
        String slug,
        String name,
        String summary,
        String description,
        BigDecimal latitude,
        BigDecimal longitude,
        Short visitDurationMinutes,
        boolean free,
        BigDecimal entranceFee,
        String currency,
        boolean alwaysOpen,

        /**
         * The opening hours are held as a JSON string in the entity, because
         * nothing in this service queries inside them. Serializing that string
         * normally would produce {@code "openingHours": "{\"mon\":[...]}"} — an
         * escaped string the client has to parse a second time.
         * {@code @JsonRawValue} writes the stored JSON straight into the
         * response, so the client sees the object it expects.
         */
        @JsonRawValue
        String openingHours,

        String imageUrl,
        int popularityScore,
        List<String> categories
) {

    public static AttractionResponse from(Attraction attraction) {
        return new AttractionResponse(
                attraction.getId(),
                attraction.getDestination().getId(),
                attraction.getDestination().getSlug(),
                attraction.getSlug(),
                attraction.getName(),
                attraction.getSummary(),
                attraction.getDescription(),
                attraction.getLatitude(),
                attraction.getLongitude(),
                attraction.getVisitDurationMinutes(),
                attraction.isFree(),
                attraction.getEntranceFee(),
                attraction.getCurrency(),
                attraction.isAlwaysOpen(),
                attraction.getOpeningHours(),
                attraction.getImageUrl(),
                attraction.getPopularityScore(),
                attraction.getCategories().stream()
                        .sorted(Comparator.comparing(Category::getSortOrder))
                        .map(Category::getCode)
                        .toList());
    }
}
