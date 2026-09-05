package com.explorelk.auth.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The paged response shape every list endpoint returns.
 *
 * <pre>
 * {
 *   "items": [ ... ],
 *   "page": 0,
 *   "size": 20,
 *   "totalItems": 137,
 *   "totalPages": 7
 * }
 * </pre>
 *
 * <p>Spring's own {@code Page} serialises to a much larger object full of
 * {@code pageable}, {@code sort} and {@code numberOfElements} — internals that
 * clients then start depending on, and whose JSON has already changed between
 * Spring versions. Wrapping it keeps the contract ours.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {

    /** Maps a page of entities to a page of DTOs without leaking the entity type. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
