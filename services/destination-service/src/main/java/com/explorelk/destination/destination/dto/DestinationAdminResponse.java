package com.explorelk.destination.destination.dto;

import com.explorelk.destination.category.Category;
import com.explorelk.destination.category.dto.CategoryResponse;
import com.explorelk.destination.destination.ContentStatus;
import com.explorelk.destination.destination.Destination;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A destination as an admin sees it — the same content as
 * {@link DestinationDetailResponse} plus the three things a public response
 * deliberately withholds.
 *
 * <ul>
 *   <li>{@code status}, because an admin screen has to show drafts as drafts.</li>
 *   <li>{@code version}, which the admin sends back on the next edit so a stale
 *       save is rejected rather than silently overwriting a colleague.</li>
 *   <li>{@code createdAt} / {@code updatedAt}, for the "last edited" column.</li>
 * </ul>
 *
 * <p>A separate record rather than a nullable-field superset of the public one:
 * the moment a single DTO can carry {@code status}, it takes exactly one
 * mistaken reuse for a draft to appear on a public endpoint.
 *
 * @param completeForPublishing whether {@code PATCH /status} to {@code PUBLISHED}
 *                              would succeed right now — so a CMS screen can grey
 *                              out the button instead of offering it and failing
 */
public record DestinationAdminResponse(
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
        ContentStatus status,
        boolean completeForPublishing,
        int version,
        List<CategoryResponse> categories,
        Instant createdAt,
        Instant updatedAt
) {

    public static DestinationAdminResponse from(Destination destination) {
        return new DestinationAdminResponse(
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
                destination.getStatus(),
                destination.isCompleteForPublishing(),
                destination.getVersion(),
                destination.getCategories().stream()
                        .sorted(Comparator.comparing(Category::getSortOrder))
                        .map(CategoryResponse::from)
                        .toList(),
                destination.getCreatedAt(),
                destination.getUpdatedAt());
    }
}
