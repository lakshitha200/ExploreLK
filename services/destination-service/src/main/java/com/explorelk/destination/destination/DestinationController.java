package com.explorelk.destination.destination;

import com.explorelk.destination.common.PageResponse;
import com.explorelk.destination.destination.dto.DestinationDetailResponse;
import com.explorelk.destination.destination.dto.DestinationSummaryResponse;
import com.explorelk.destination.destination.dto.NearbyDestinationResponse;
import com.explorelk.destination.search.DestinationQuery;
import com.explorelk.destination.search.NearbyQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The traveler-facing destination catalog. No token required, and only
 * {@link ContentStatus#PUBLISHED} content is ever returned.
 *
 * <p>Admin endpoints live under {@code /api/v1/admin/destinations} and are a
 * different controller calling a different service, so the two can never share
 * a code path by accident.
 *
 * <p>The attractions of a destination hang off this path too, at
 * {@code /{idOrSlug}/attractions}, but are served by {@code AttractionController}
 * — see the note there on why.
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
     * What is near here.
     *
     * <pre>
     * GET /api/v1/destinations/nearby?lat=6.8667&amp;lng=81.0466
     * GET /api/v1/destinations/nearby?lat=6.8667&amp;lng=81.0466&amp;radiusKm=10&amp;limit=5
     * </pre>
     *
     * <p>Results carry a {@code distanceKm} and come back nearest first. Spring
     * matches this literal path ahead of {@code /{idOrSlug}} below, so
     * {@code nearby} is never read as a slug.
     *
     * <p>{@code radiusKm} defaults to 25 and is capped at 100; {@code limit}
     * defaults to 20 and is capped at 50 — an uncapped proximity search on a
     * public endpoint is a request to sort the whole table by distance.
     *
     * <p>Coordinates are (lat, lng) here, as a person writes them. PostGIS wants
     * them the other way round; that conversion happens once, in the repository,
     * and getting it wrong produces empty results rather than an error.
     */
    @GetMapping("/nearby")
    public List<NearbyDestinationResponse> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) Integer limit) {

        return destinationService.findNearby(NearbyQuery.of(lat, lng, radiusKm, limit));
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
