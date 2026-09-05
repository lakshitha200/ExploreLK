package com.explorelk.destination.search;

import com.explorelk.destination.common.Pagination;

import java.util.Locale;

/**
 * The cache key for one page of the destination list.
 *
 * <p>Shaped as {@code {search}|{category}|{district}|{province}|{page}|{size}|{sort}|{direction}},
 * which combined with the cache prefix gives the {@code destlist:…} key from §8
 * of the design.
 *
 * <p><strong>The inputs are normalized first, and that is the whole point of
 * having this class.</strong> {@code ?size=0}, {@code ?size=20} and no
 * {@code size} at all are the same request and must not become three cache
 * entries; neither must {@code ?sort=popularity} and {@code ?sort=nonsense},
 * which both fall back to popularity. Keying on the raw query string instead
 * gives a cache that fills up with synonyms and hits on almost none of them.
 */
public final class DestinationListKey {

    private DestinationListKey() {
    }

    public static String of(DestinationQuery query, int page, int size, String sort, String direction) {
        DestinationSort resolvedSort = DestinationSort.from(sort);

        return String.join("|",
                nullSafe(query.search()).toLowerCase(Locale.ROOT),
                nullSafe(query.category()),
                nullSafe(query.district()).toLowerCase(Locale.ROOT),
                nullSafe(query.province()).toLowerCase(Locale.ROOT),
                String.valueOf(Pagination.clampPage(page)),
                String.valueOf(Pagination.clampSize(size)),
                resolvedSort.name(),
                // Direction is resolved through the Sort itself rather than the raw
                // parameter, so "asc", "ASC" and an unparseable value that falls back
                // to ascending all land on one key.
                resolvedSort.toSort(direction).toString());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
