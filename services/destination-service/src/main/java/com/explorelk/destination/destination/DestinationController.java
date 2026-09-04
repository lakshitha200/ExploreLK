package com.explorelk.destination.destination;

import com.explorelk.destination.common.PageResponse;
import com.explorelk.destination.destination.dto.DestinationDetailResponse;
import com.explorelk.destination.destination.dto.DestinationSummaryResponse;
import com.explorelk.destination.search.DestinationQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The traveler-facing destination catalog. No token required, and only
 * {@link ContentStatus#PUBLISHED} content is ever returned.
 *
 * <p>Admin endpoints live under {@code /api/v1/admin/destinations} from Step 5 and
 * are a different controller entirely, so the two can never share a code path by
 * accident.
 */
@RestController
@RequestMapping("/api/v1/destinations")
@RequiredArgsConstructor
public class DestinationController {

    private final DestinationService destinationService;

    /**
     * Browse the catalog.
     *
     * <pre>
     * GET /api/v1/destinations
     * GET /api/v1/destinations?search=ell
     * GET /api/v1/destinations?category=BEACH&amp;province=Southern
     * GET /api/v1/destinations?sort=name&amp;direction=asc&amp;page=1&amp;size=5
     * </pre>
     *
     * <p>All filters are optional and compose with AND. {@code size} is clamped to
     * {@value DestinationService#MAX_PAGE_SIZE} and {@code sort} is a whitelist, so
     * neither can be used to make the database work harder than intended.
     */
    @GetMapping
    public PageResponse<DestinationSummaryResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String province,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "popularity") String sort,
            @RequestParam(required = false) String direction) {

        DestinationQuery query = DestinationQuery.of(search, category, district, province);
        return destinationService.list(query, page, size, sort, direction);
    }

    /**
     * One destination, addressed by either form.
     *
     * <pre>
     * GET /api/v1/destinations/ella
     * GET /api/v1/destinations/d0000000-0000-4000-8000-000000000004
     * </pre>
     */
    @GetMapping("/{idOrSlug}")
    public DestinationDetailResponse get(@PathVariable String idOrSlug) {
        return destinationService.getPublished(idOrSlug);
    }
}
