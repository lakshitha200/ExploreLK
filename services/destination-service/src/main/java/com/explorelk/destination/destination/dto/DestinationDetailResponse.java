package com.explorelk.destination.destination.dto;

import com.explorelk.destination.category.Category;
import com.explorelk.destination.category.dto.CategoryResponse;
import com.explorelk.destination.destination.Destination;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A single destination, fully rendered.
 *
 * <p>Categories arrive as full objects here rather than codes: a detail page shows
 * labelled tags, and it is one row already loaded.
 *
 * <p>What is <em>not</em> here matters as much: no {@code status}, because a public
 * response is always PUBLISHED and saying so invites clients to branch on it, and
 * no {@code version}, which is an internal optimistic-locking counter.
 */
public record DestinationDetailResponse(
        UUID id,
        String slug,
        String name,
        String district,
        String province,
        String summary,
        String description,
        BigDecimal latitude,
        BigDecimal longitude,
        Short recommendedDays,
        String coverImageUrl,
        int popularityScore,
        List<CategoryResponse> categories
) {

    public static DestinationDetailResponse from(Destination destination) {
        return new DestinationDetailResponse(
                destination.getId(),
                destination.getSlug(),
                destination.getName(),
                destination.getDistrict(),
                destination.getProvince(),
                destination.getSummary(),
                destination.getDescription(),
                destination.getLatitude(),
                destination.getLongitude(),
                destination.getRecommendedDays(),
                destination.getCoverImageUrl(),
                destination.getPopularityScore(),
                destination.getCategories().stream()
                        .sorted(Comparator.comparing(Category::getSortOrder))
                        .map(CategoryResponse::from)
                        .toList());
    }
}
