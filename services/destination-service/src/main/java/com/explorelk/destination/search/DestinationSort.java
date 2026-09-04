package com.explorelk.destination.search;

import org.springframework.data.domain.Sort;

import java.util.Locale;

/**
 * The sort orders a client is allowed to ask for.
 *
 * <p>A whitelist, not a passthrough. Spring's {@code Pageable} resolver will
 * happily sort by any property named in the query string, which on a public
 * endpoint means a caller can order by an internal column, probe for its
 * existence, or force an expensive unindexed sort. Mapping a fixed set of public
 * names onto entity properties closes all three.
 */
public enum DestinationSort {

    /** Default. Editorially ranked, best first. */
    POPULARITY("popularity", "popularityScore", Sort.Direction.DESC),

    /** Alphabetical. */
    NAME("name", "name", Sort.Direction.ASC),

    /** Newest additions to the catalog first. */
    CREATED("createdAt", "createdAt", Sort.Direction.DESC);

    private final String publicName;
    private final String property;
    private final Sort.Direction defaultDirection;

    DestinationSort(String publicName, String property, Sort.Direction defaultDirection) {
        this.publicName = publicName;
        this.property = property;
        this.defaultDirection = defaultDirection;
    }

    /** Unknown or missing values fall back to {@link #POPULARITY} rather than failing. */
    public static DestinationSort from(String value) {
        if (value != null) {
            String wanted = value.trim().toLowerCase(Locale.ROOT);
            for (DestinationSort candidate : values()) {
                if (candidate.publicName.toLowerCase(Locale.ROOT).equals(wanted)) {
                    return candidate;
                }
            }
        }
        return POPULARITY;
    }

    /**
     * Builds the actual {@link Sort}, with {@code name} appended as a tiebreak.
     *
     * <p>Without a tiebreak, rows with equal popularity come back in whatever order
     * Postgres feels like, which differs between pages — so an item can appear on
     * both page 1 and page 2, or on neither. A total ordering is what makes
     * pagination stable.
     */
    public Sort toSort(String requestedDirection) {
        Sort.Direction direction = parseDirection(requestedDirection, defaultDirection);
        Sort primary = Sort.by(direction, property);
        return this == NAME ? primary : primary.and(Sort.by(Sort.Direction.ASC, "name"));
    }

    private static Sort.Direction parseDirection(String value, Sort.Direction fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> fallback;
        };
    }
}
