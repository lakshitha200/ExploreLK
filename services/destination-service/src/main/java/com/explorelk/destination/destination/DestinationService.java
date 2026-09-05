package com.explorelk.destination.destination;

import com.explorelk.destination.category.CategoryService;
import com.explorelk.destination.common.PageResponse;
import com.explorelk.destination.common.Pagination;
import com.explorelk.destination.config.CacheConfig;
import com.explorelk.destination.common.exception.NotFoundException;
import com.explorelk.destination.common.exception.ValidationException;
import com.explorelk.destination.destination.dto.DestinationDetailResponse;
import com.explorelk.destination.destination.dto.DestinationSummaryResponse;
import com.explorelk.destination.destination.dto.NearbyDestinationResponse;
import com.explorelk.destination.search.DestinationQuery;
import com.explorelk.destination.search.DestinationSearchSpecs;
import com.explorelk.destination.search.DestinationSort;
import com.explorelk.destination.search.NearbyQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public reads of the destination catalog.
 *
 * <p>Everything here is read-only and restricted to {@link ContentStatus#PUBLISHED}.
 * Admin writes live in a separate {@code DestinationAdminService} (Step 5) — the
 * split is structural on purpose: one class that sometimes sees drafts and
 * sometimes does not is one refactor away from leaking them.
 */
@Service
@RequiredArgsConstructor
public class DestinationService {

    /**
     * The limits live in {@link Pagination} now that the admin list endpoints
     * share them; these stay as the names the public API documentation and the
     * controller javadoc refer to.
     */
    public static final int MAX_PAGE_SIZE = Pagination.MAX_PAGE_SIZE;
    public static final int DEFAULT_PAGE_SIZE = Pagination.DEFAULT_PAGE_SIZE;

    private final DestinationRepository destinationRepository;
    private final CategoryService categoryService;

    /**
     * The public list: filtered, sorted, paginated.
     *
     * @param sort      one of the names in {@link DestinationSort}; anything else
     *                  falls back to popularity
     * @param direction {@code asc} or {@code desc}; anything else uses the sort's
     *                  natural direction
     */
    @Cacheable(
            cacheNames = CacheConfig.DESTINATION_LIST,
            key = "T(com.explorelk.destination.search.DestinationListKey)"
                    + ".of(#query, #page, #size, #sort, #direction)")
    @Transactional(readOnly = true)
    public PageResponse<DestinationSummaryResponse> list(DestinationQuery query,
                                                         int page,
                                                         int size,
                                                         String sort,
                                                         String direction) {
        requireKnownCategory(query.category());

        Pageable pageable = Pagination.of(page, size, DestinationSort.from(sort).toSort(direction));

        Page<Destination> results =
                destinationRepository.findAll(DestinationSearchSpecs.publicSearch(query), pageable);

        return PageResponse.from(results, DestinationSummaryResponse::from);
    }

    /**
     * One destination, by UUID or by slug.
     *
     * <p>Machines hold ids, people and URLs hold slugs, and both arrive on the same
     * path. If the segment parses as a UUID it is treated as one; otherwise it is a
     * slug. The two can never collide — the {@code ck_destinations_slug} constraint
     * forbids a slug shaped like a UUID.
     */
    @Cacheable(cacheNames = CacheConfig.DESTINATION, key = "#idOrSlug")
    @Transactional(readOnly = true)
    public DestinationDetailResponse getPublished(String idOrSlug) {
        Destination destination = asUuid(idOrSlug)
                .map(id -> destinationRepository.findByIdAndStatus(id, ContentStatus.PUBLISHED))
                .orElseGet(() -> destinationRepository.findBySlugAndStatus(idOrSlug, ContentStatus.PUBLISHED))
                .orElseThrow(() -> new NotFoundException("Destination", idOrSlug));

        return DestinationDetailResponse.from(destination);
    }

    /**
     * Published destinations near a point, nearest first.
     *
     * <p>The only spatial read in the service. It bypasses the Criteria API and
     * goes straight to a native query, because {@code ST_DWithin} and the
     * {@code <->} nearest-neighbour operator have no JPA equivalent — and because
     * mapping the {@code geog} column into an entity to get them would drag in
     * hibernate-spatial, JTS types and a dialect swap for one endpoint.
     *
     * <p><strong>Not cached</strong>, now or in Step 8. The key space is every
     * {@code (lat, lng, radius)} a phone GPS ever emits, so the hit rate is near
     * zero and Redis fills with entries nobody reads twice. If it ever needs
     * caching, round the coordinates to about three decimals first to create real
     * buckets — but measure before bothering, because the GiST index is already
     * the fast path.
     */
    @Transactional(readOnly = true)
    public List<NearbyDestinationResponse> findNearby(NearbyQuery query) {
        return destinationRepository.findNearby(
                        query.latitude(), query.longitude(), query.radiusMeters(), query.limit())
                .stream()
                .map(NearbyDestinationResponse::from)
                .toList();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * An unknown category code is a client bug, so say so.
     *
     * <p>Silently returning an empty page for {@code ?category=BEECH} reads as
     * "there are no beaches", and the typo survives all the way to production.
     */
    private void requireKnownCategory(String category) {
        if (category != null && !categoryService.exists(category)) {
            throw new ValidationException("category", "is not a known category code");
        }
    }

    private static Optional<UUID> asUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
