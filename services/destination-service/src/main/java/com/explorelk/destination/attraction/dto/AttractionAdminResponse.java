package com.explorelk.destination.attraction.dto;

import com.explorelk.destination.attraction.Attraction;
import com.explorelk.destination.category.Category;
import com.explorelk.destination.category.dto.CategoryResponse;
import com.explorelk.destination.destination.ContentStatus;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * An attraction as an admin sees it: the public content plus {@code status},
 * timestamps, and whether it could be published right now.
 *
 * <p>Categories arrive as full objects rather than codes, because an admin
 * screen renders labelled tags and the rows are loaded already.
 *
 * @param destinationStatus the parent's state, included because it decides
 *                          visibility as much as the attraction's own does — a
 *                          PUBLISHED attraction under a DRAFT destination is
 *                          invisible to travelers, and an admin looking at the
 *                          row deserves to know that without a second request
 * @param completeForPublishing whether a move to PUBLISHED would succeed now, so
 *                          a CMS can grey out the button rather than offer it
 *                          and fail
 */
public record AttractionAdminResponse(
        UUID id,
        UUID destinationId,
        String destinationSlug,
        String destinationName,
        ContentStatus destinationStatus,
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

        /** Embedded as an object, not an escaped string — see {@link AttractionResponse}. */
        @JsonRawValue
        String openingHours,

        String imageUrl,
        int popularityScore,
        ContentStatus status,
        boolean completeForPublishing,
        List<CategoryResponse> categories,
        Instant createdAt,
        Instant updatedAt
) {

    public static AttractionAdminResponse from(Attraction attraction) {
        return new AttractionAdminResponse(
                attraction.getId(),
                attraction.getDestination().getId(),
                attraction.getDestination().getSlug(),
                attraction.getDestination().getName(),
                attraction.getDestination().getStatus(),
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
                attraction.getStatus(),
                attraction.isCompleteForPublishing(),
                attraction.getCategories().stream()
                        .sorted(Comparator.comparing(Category::getSortOrder))
                        .map(CategoryResponse::from)
                        .toList(),
                attraction.getCreatedAt(),
                attraction.getUpdatedAt());
    }
}
