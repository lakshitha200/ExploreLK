package com.explorelk.destination.attraction;

import com.explorelk.destination.attraction.dto.AttractionResponse;
import com.explorelk.destination.attraction.dto.NearbyAttractionResponse;
import com.explorelk.destination.common.exception.NotFoundException;
import com.explorelk.destination.destination.ContentStatus;
import com.explorelk.destination.destination.Destination;
import com.explorelk.destination.destination.DestinationRepository;
import com.explorelk.destination.search.NearbyQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public reads of attractions. Read-only, and {@code PUBLISHED} content only —
 * writes live in {@link AttractionAdminService}.
 */
@Service
@RequiredArgsConstructor
public class AttractionService {

    private final AttractionRepository attractionRepository;
    private final DestinationRepository destinationRepository;

    /**
     * The attractions of one destination, addressed by id or slug.
     *
     * <p>The destination is resolved first, and resolved <em>as published</em>.
     * That gives a 404 for the whole request when the place is a draft, rather
     * than an empty list — an empty list would say "Ella has nothing to see",
     * which is a different and wrong answer.
     */
    @Transactional(readOnly = true)
    public List<AttractionResponse> listPublishedOf(String destinationIdOrSlug) {
        Destination destination = asUuid(destinationIdOrSlug)
                .map(id -> destinationRepository.findByIdAndStatus(id, ContentStatus.PUBLISHED))
                .orElseGet(() -> destinationRepository.findBySlugAndStatus(
                        destinationIdOrSlug, ContentStatus.PUBLISHED))
                .orElseThrow(() -> new NotFoundException("Destination", destinationIdOrSlug));

        return attractionRepository.findPublishedOfDestination(destination.getId()).stream()
                .map(AttractionResponse::from)
                .toList();
    }

    /**
     * One attraction, by id only.
     *
     * <p>Attraction slugs are unique only within their destination, so there is
     * nothing to resolve here the way {@code /destinations/{idOrSlug}} does.
     */
    @Transactional(readOnly = true)
    public AttractionResponse getPublished(UUID id) {
        return attractionRepository.findPublishedById(id)
                .map(AttractionResponse::from)
                .orElseThrow(() -> new NotFoundException("Attraction", id));
    }

    /**
     * Published attractions near a point, nearest first.
     *
     * <p>Not cached, now or in Step 8. The key space is every {@code (lat, lng,
     * radius)} a phone GPS ever emits, so the hit rate would be near zero and
     * Redis would fill with entries nobody reads twice. The GiST index is already
     * the fast path.
     */
    @Transactional(readOnly = true)
    public List<NearbyAttractionResponse> findNearby(NearbyQuery query) {
        return attractionRepository.findNearby(
                        query.latitude(), query.longitude(), query.radiusMeters(), query.limit())
                .stream()
                .map(NearbyAttractionResponse::from)
                .toList();
    }

    private static Optional<UUID> asUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
