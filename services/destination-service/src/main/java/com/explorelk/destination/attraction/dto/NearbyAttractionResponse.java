package com.explorelk.destination.attraction.dto;

import com.explorelk.destination.attraction.NearbyAttractionProjection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One result of {@code GET /api/v1/attractions/nearby}.
 *
 * <p>Carries its destination so the row reads on its own — "Nine Arches Bridge,
 * Ella, 1.9 km" — and so a client can link onward without a second request.
 *
 * <p>As with the destination equivalent, categories and opening hours are
 * absent: this is a proximity answer, and enriching every row would mean a query
 * per row. {@code GET /api/v1/attractions/{id}} has the full record.
 *
 * @param distanceKm distance from the requested point, in kilometres, rounded to
 *                   metres
 */
public record NearbyAttractionResponse(
        UUID id,
        UUID destinationId,
        String destinationSlug,
        String destinationName,
        String slug,
        String name,
        String summary,
        BigDecimal latitude,
        BigDecimal longitude,
        Short visitDurationMinutes,
        boolean free,
        BigDecimal entranceFee,
        String currency,
        String imageUrl,
        int popularityScore,
        double distanceKm
) {

    public static NearbyAttractionResponse from(NearbyAttractionProjection row) {
        return new NearbyAttractionResponse(
                row.getId(),
                row.getDestinationId(),
                row.getDestinationSlug(),
                row.getDestinationName(),
                row.getSlug(),
                row.getName(),
                row.getSummary(),
                row.getLatitude(),
                row.getLongitude(),
                row.getVisitDurationMinutes(),
                row.isFree(),
                row.getEntranceFee(),
                row.getCurrency(),
                row.getImageUrl(),
                row.getPopularityScore(),
                Math.round(row.getDistanceKm() * 1000.0) / 1000.0);
    }
}
