package com.explorelk.destination.search;

import com.explorelk.destination.common.exception.ValidationException;

/**
 * A validated, clamped proximity request: "what is near here".
 *
 * <p>Coordinates are <strong>rejected</strong> when out of range, while radius
 * and limit are <strong>clamped</strong>. That asymmetry is deliberate. A
 * latitude of 200 is a bug in the caller and silently correcting it would return
 * results for somewhere else entirely; a radius of 5000 km is a reasonable thing
 * to ask for and an unreasonable thing to answer, so the honest response is the
 * biggest radius this endpoint serves.
 *
 * <p>The clamps are not politeness. {@code /nearby} is public and unauthenticated:
 * without them, {@code ?radiusKm=20000&limit=100000} asks Postgres to sort the
 * whole table by distance, on demand, for free.
 *
 * @param latitude  degrees north, -90 to 90
 * @param longitude degrees east, -180 to 180
 */
public record NearbyQuery(double latitude, double longitude, double radiusKm, int limit) {

    public static final double DEFAULT_RADIUS_KM = 25;
    public static final double MAX_RADIUS_KM = 100;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    /**
     * <p><strong>Argument order is the trap here, not the validation.</strong>
     * Everything on the API side is (latitude, longitude) because that is how a
     * person reads a coordinate; everything on the PostGIS side is
     * {@code ST_MakePoint(longitude, latitude)} because that is X then Y. Swap
     * them and no error appears anywhere — Sri Lanka simply moves to the middle
     * of Somalia and every query comes back empty.
     */
    public static NearbyQuery of(double latitude, double longitude, Double radiusKm, Integer limit) {
        if (latitude < -90 || latitude > 90) {
            throw new ValidationException("lat", "must be between -90 and 90");
        }
        if (longitude < -180 || longitude > 180) {
            throw new ValidationException("lng", "must be between -180 and 180");
        }
        return new NearbyQuery(latitude, longitude, clampRadius(radiusKm), clampLimit(limit));
    }

    /** PostGIS distance functions work in metres on a geography column. */
    public double radiusMeters() {
        return radiusKm * 1000.0;
    }

    private static double clampRadius(Double radiusKm) {
        if (radiusKm == null || radiusKm <= 0) {
            return DEFAULT_RADIUS_KM;
        }
        return Math.min(radiusKm, MAX_RADIUS_KM);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
