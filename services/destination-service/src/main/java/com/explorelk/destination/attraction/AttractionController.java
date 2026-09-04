package com.explorelk.destination.attraction;

import com.explorelk.destination.attraction.dto.AttractionResponse;
import com.explorelk.destination.attraction.dto.NearbyAttractionResponse;
import com.explorelk.destination.search.NearbyQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The traveler-facing attraction endpoints. No token required, and only
 * {@code PUBLISHED} attractions of {@code PUBLISHED} destinations are returned.
 *
 * <p>Mapped at {@code /api/v1} rather than {@code /api/v1/attractions} so that
 * the nested list below can live here too. A destination's attractions are a
 * sub-resource of a destination and their URL should say so, but the code that
 * serves them belongs with the rest of the attraction code — otherwise the
 * destination package ends up depending on the attraction package, which already
 * depends on it. The admin controller is arranged the same way.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AttractionController {

    private final AttractionService attractionService;

    /**
     * The attractions of one destination, best first.
     *
     * <pre>
     * GET /api/v1/destinations/ella/attractions
     * GET /api/v1/destinations/d0000000-0000-4000-8000-000000000004/attractions
     * </pre>
     *
     * <p>An unpublished destination 404s here rather than returning an empty
     * list — "this place does not exist publicly" and "this place has nothing to
     * see" are different answers.
     */
    @GetMapping("/destinations/{destinationIdOrSlug}/attractions")
    public List<AttractionResponse> listOf(@PathVariable String destinationIdOrSlug) {
        return attractionService.listPublishedOf(destinationIdOrSlug);
    }

    /**
     * What is near here.
     *
     * <pre>
     * GET /api/v1/attractions/nearby?lat=6.8667&amp;lng=81.0466
     * GET /api/v1/attractions/nearby?lat=6.8667&amp;lng=81.0466&amp;radiusKm=10&amp;limit=5
     * </pre>
     *
     * <p>Declared before {@code /{id}} in the file for readability only — Spring
     * matches the literal path ahead of the template regardless of order, so
     * {@code /nearby} can never be read as an id.
     *
     * <p>{@code radiusKm} defaults to 25 and is capped at 100; {@code limit}
     * defaults to 20 and is capped at 50. See {@code NearbyQuery} for why those
     * are clamped rather than rejected.
     */
    @GetMapping("/attractions/nearby")
    public List<NearbyAttractionResponse> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) Integer limit) {

        return attractionService.findNearby(NearbyQuery.of(lat, lng, radiusKm, limit));
    }

    /**
     * One attraction, by id.
     *
     * <p>Id only, never slug: an attraction slug is unique within its destination,
     * so {@code main-beach} on its own does not identify anything.
     */
    @GetMapping("/attractions/{id}")
    public AttractionResponse get(@PathVariable UUID id) {
        return attractionService.getPublished(id);
    }
}
