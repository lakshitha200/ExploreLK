package com.explorelk.destination.destination.dto;

import com.explorelk.destination.destination.NearbyDestinationProjection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One result of {@code GET /api/v1/destinations/nearby}.
 *
 * <p>A distinct shape from {@link DestinationSummaryResponse}, not a superset of
 * it with a distance bolted on. A proximity result answers "how far, and in what
 * order", and the query that produces it is a single native statement that
 * cannot cheaply carry category tags — one extra query per row would undo the
 * point of using the spatial index. Keeping the two shapes separate makes that a
 * documented contract instead of a surprise absent field.
 *
 * @param distanceKm distance from the requested point along the surface of the
 *                   Earth, in kilometres. Rounded to metres, because six decimal
 *                   places of a kilometre is a millimetre and reads as noise.
 */
public record NearbyDestinationResponse(
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
        double distanceKm
) {

    public static NearbyDestinationResponse from(NearbyDestinationProjection row) {
        return new NearbyDestinationResponse(
                row.getId(),
                row.getSlug(),
                row.getName(),
                row.getDistrict(),
                row.getProvince(),
                row.getSummary(),
                row.getLatitude(),
                row.getLongitude(),
                row.getRecommendedDays(),
                row.getCoverImageUrl(),
                row.getPopularityScore(),
                Math.round(row.getDistanceKm() * 1000.0) / 1000.0);
    }
}
