package com.explorelk.destination.destination;

import com.explorelk.destination.category.CategoryService;
import com.explorelk.destination.common.PageResponse;
import com.explorelk.destination.common.exception.NotFoundException;
import com.explorelk.destination.common.exception.ValidationException;
import com.explorelk.destination.destination.dto.DestinationDetailResponse;
import com.explorelk.destination.destination.dto.DestinationSummaryResponse;
import com.explorelk.destination.search.DestinationQuery;
import com.explorelk.destination.search.DestinationSearchSpecs;
import com.explorelk.destination.search.DestinationSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * An uncapped page size is a free denial-of-service on a public endpoint:
     * {@code ?size=1000000} would materialise the whole table and every category
     * collection on it. Clamped rather than rejected — a client asking for too
     * much gets the maximum, not an error.
     */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

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
    @Transactional(readOnly = true)
    public PageResponse<DestinationSummaryResponse> list(DestinationQuery query,
                                                         int page,
                                                         int size,
                                                         String sort,
                                                         String direction) {
        requireKnownCategory(query.category());

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampSize(size),
                DestinationSort.from(sort).toSort(direction));

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
    @Transactional(readOnly = true)
    public DestinationDetailResponse getPublished(String idOrSlug) {
        Destination destination = asUuid(idOrSlug)
                .map(id -> destinationRepository.findByIdAndStatus(id, ContentStatus.PUBLISHED))
                .orElseGet(() -> destinationRepository.findBySlugAndStatus(idOrSlug, ContentStatus.PUBLISHED))
                .orElseThrow(() -> new NotFoundException("Destination", idOrSlug));

        return DestinationDetailResponse.from(destination);
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

    private static int clampSize(int size) {
        return size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    private static Optional<UUID> asUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
