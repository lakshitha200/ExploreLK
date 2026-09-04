package com.explorelk.destination.destination;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the native proximity query returns, row by row.
 *
 * <p>An interface projection rather than the {@link Destination} entity, because
 * the query computes a column no entity has — {@code distanceKm} — and because
 * loading entities would mean a second round of queries for the category
 * collections. A proximity list is a map pin with a label; it is not a catalog
 * card, and it does not carry tags.
 *
 * <p>Each getter binds to the matching quoted alias in the query. Change one and
 * change the other, or the property silently comes back null.
 */
public interface NearbyDestinationProjection {

    UUID getId();

    String getSlug();

    String getName();

    String getDistrict();

    String getProvince();

    String getSummary();

    BigDecimal getLatitude();

    BigDecimal getLongitude();

    Short getRecommendedDays();

    String getCoverImageUrl();

    int getPopularityScore();

    /** Great-circle distance from the requested point, in kilometres. */
    double getDistanceKm();
}
