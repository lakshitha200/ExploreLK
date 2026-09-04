package com.explorelk.destination.search;

/**
 * The filters a traveler can apply to the destination list. Every field is
 * optional and they compose with AND.
 *
 * <p>A record rather than four loose parameters threaded through the service: the
 * set will grow (a {@code minDays} or a {@code nearLat/nearLng} are obvious
 * additions) and this way only one signature changes.
 *
 * @param search   free text, matched against name and district
 * @param category category code, e.g. {@code BEACH} — validated before use
 * @param district exact match
 * @param province exact match
 */
public record DestinationQuery(
        String search,
        String category,
        String district,
        String province
) {

    /** Trims and nulls out blanks, so {@code ?search=} behaves as "no filter". */
    public static DestinationQuery of(String search, String category, String district, String province) {
        return new DestinationQuery(
                normalize(search),
                // Codes are upper case by convention; accept ?category=beach too.
                normalize(category) == null ? null : normalize(category).toUpperCase(),
                normalize(district),
                normalize(province));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
