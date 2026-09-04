package com.explorelk.destination.destination.dto;

import com.explorelk.destination.category.Category;
import com.explorelk.destination.destination.Destination;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A destination as it appears in a list — enough for a card, no more.
 *
 * <p>{@code description} is deliberately absent: it is the long body text, and
 * shipping twenty of them in a page is wasted bandwidth on a screen that shows
 * none of it. Categories are codes only for the same reason; names come from
 * {@code GET /api/v1/categories}, which a client fetches once.
 */
public record DestinationSummaryResponse(
        UUID id,
        String slug,
        String name,
        String district,
        String province,
        String summary,
        BigDecimal latitude,
        BigDecimal longitude,
        Short recommendedDays,
        String coverImageUrl,
        int popularityScore,
        List<String> categories
) {

    public static DestinationSummaryResponse from(Destination destination) {
        return new DestinationSummaryResponse(
                destination.getId(),
                destination.getSlug(),
                destination.getName(),
                destination.getDistrict(),
                destination.getProvince(),
                destination.getSummary(),
                destination.getLatitude(),
                destination.getLongitude(),
                destination.getRecommendedDays(),
                destination.getCoverImageUrl(),
                destination.getPopularityScore(),
                destination.getCategories().stream()
                        .sorted(java.util.Comparator.comparing(Category::getSortOrder))
                        .map(Category::getCode)
                        .toList());
    }
}
