package com.explorelk.destination.attraction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the native attraction proximity query returns, row by row.
 *
 * <p>Carries its destination's id, slug and name so a client can render "Nine
 * Arches Bridge — Ella, 1.9 km" and link onward without a second request. As
 * with the destination projection, categories are absent: this is a map pin, not
 * a catalog card, and tagging every row would mean a query per row.
 *
 * <p>Each getter binds to the matching quoted alias in the query.
 */
public interface NearbyAttractionProjection {

    UUID getId();

    String getSlug();

    String getName();

    String getSummary();

    BigDecimal getLatitude();

    BigDecimal getLongitude();

    Short getVisitDurationMinutes();

    boolean isFree();

    BigDecimal getEntranceFee();

    String getCurrency();

    String getImageUrl();

    int getPopularityScore();

    UUID getDestinationId();

    String getDestinationSlug();

    String getDestinationName();

    /** Great-circle distance from the requested point, in kilometres. */
    double getDistanceKm();
}
