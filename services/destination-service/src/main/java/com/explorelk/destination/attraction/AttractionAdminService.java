package com.explorelk.destination.attraction;

import com.explorelk.destination.attraction.dto.AttractionAdminResponse;
import com.explorelk.destination.attraction.dto.CreateAttractionRequest;
import com.explorelk.destination.attraction.dto.UpdateAttractionRequest;
import com.explorelk.destination.category.CategoryService;
import com.explorelk.destination.common.CatalogCache;
import com.explorelk.destination.common.ErrorCode;
import com.explorelk.destination.common.SlugGenerator;
import com.explorelk.destination.common.exception.AppException;
import com.explorelk.destination.common.exception.NotFoundException;
import com.explorelk.destination.common.exception.ValidationException;
import com.explorelk.destination.destination.ContentStatus;
import com.explorelk.destination.destination.Destination;
import com.explorelk.destination.destination.DestinationAdminService;
import com.explorelk.destination.outbox.CatalogEventType;
import com.explorelk.destination.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Everything that writes attractions.
 *
 * <p>Split from {@link AttractionService} for the same reason the destination
 * services are split: public reads see only published content, admin operations
 * see and change everything, and one class that does both is one careless edit
 * away from leaking drafts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttractionAdminService {

    /** The default when a fee is recorded without one. Sri Lankan rupees. */
    private static final String DEFAULT_CURRENCY = "LKR";

    private final AttractionRepository attractionRepository;
    private final DestinationAdminService destinationAdminService;
    private final CategoryService categoryService;
    private final OpeningHoursCodec openingHoursCodec;
    private final CatalogCache catalogCache;
    private final OutboxWriter outboxWriter;

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Every attraction of a destination, in any status. */
    @Transactional(readOnly = true)
    public List<AttractionAdminResponse> listOf(UUID destinationId) {
        // Resolves the destination first so an unknown id is a 404 rather than an
        // empty list that looks like a place with nothing to do.
        destinationAdminService.require(destinationId);

        return attractionRepository
                .findByDestinationIdOrderByPopularityScoreDescNameAsc(destinationId).stream()
                .map(AttractionAdminResponse::from)
                .toList();
    }

    /**
     * One attraction in any status.
     *
     * <p>This is what keeps an archived attraction reachable: the public endpoint
     * 404s on it, but its id still resolves here, so an admin can look at what
     * was retired and restore it.
     */
    @Transactional(readOnly = true)
    public AttractionAdminResponse get(UUID id) {
        return AttractionAdminResponse.from(require(id));
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Creates an attraction under a destination, as a {@code DRAFT}.
     *
     * <p>The parent may itself be a draft. Writing up Nine Arches Bridge while
     * Ella is still being drafted is ordinary editorial work, and the visibility
     * rule handles the consequence: nothing under an unpublished destination
     * reaches a public endpoint, whatever its own status says.
     */
    @Transactional
    public AttractionAdminResponse create(UUID destinationId, CreateAttractionRequest request) {
        Destination destination = destinationAdminService.require(destinationId);

        String slug = SlugGenerator.from(request.name(), "name");
        requireSlugAvailable(destinationId, slug);

        Attraction attraction = Attraction.builder()
                .destination(destination)
                .slug(slug)
                .name(request.name().trim())
                .summary(request.summary())
                .description(request.description())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .visitDurationMinutes(request.visitDurationMinutes())
                .free(Boolean.TRUE.equals(request.free()))
                .entranceFee(request.entranceFee())
                .currency(normalizeCurrency(request.currency()))
                .alwaysOpen(Boolean.TRUE.equals(request.alwaysOpen()))
                .openingHours(openingHoursCodec.toJson(request.openingHours(), "openingHours"))
                .imageUrl(request.imageUrl())
                .popularityScore(request.popularityScore() == null ? 0 : request.popularityScore())
                .status(ContentStatus.DRAFT)
                .categories(categoryService.resolve(request.categories(), "categories"))
                .build();

        requireCoordinatePair(attraction);
        requireCoherentPricing(attraction);

        Attraction saved = attractionRepository.save(attraction);
        log.info("Attraction created: {} under {} ({})",
                saved.getSlug(), destination.getSlug(), saved.getId());

        return AttractionAdminResponse.from(saved);
    }

    /** Partial update. Fields left out are left alone; the parent cannot change. */
    @Transactional
    public AttractionAdminResponse update(UUID id, UpdateAttractionRequest request) {
        Attraction attraction = require(id);

        if (request.name() != null) {
            attraction.setName(request.name().trim());
        }
        if (request.summary() != null) {
            attraction.setSummary(request.summary());
        }
        if (request.description() != null) {
            attraction.setDescription(request.description());
        }
        if (request.latitude() != null) {
            attraction.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            attraction.setLongitude(request.longitude());
        }
        if (request.visitDurationMinutes() != null) {
            attraction.setVisitDurationMinutes(request.visitDurationMinutes());
        }
        if (request.free() != null) {
            attraction.setFree(request.free());
        }
        if (request.entranceFee() != null) {
            attraction.setEntranceFee(request.entranceFee());
        }
        if (request.currency() != null) {
            attraction.setCurrency(normalizeCurrency(request.currency()));
        }
        if (request.alwaysOpen() != null) {
            attraction.setAlwaysOpen(request.alwaysOpen());
        }
        if (request.openingHours() != null) {
            attraction.setOpeningHours(
                    openingHoursCodec.toJson(request.openingHours(), "openingHours"));
        }
        if (request.imageUrl() != null) {
            attraction.setImageUrl(request.imageUrl());
        }
        if (request.popularityScore() != null) {
            attraction.setPopularityScore(request.popularityScore());
        }
        if (request.categories() != null) {
            attraction.setCategories(categoryService.resolve(request.categories(), "categories"));
        }

        requireCoordinatePair(attraction);
        requireCoherentPricing(attraction);

        // Flushed so the response carries the `updatedAt` this edit produced. The
        // audit timestamp is written at flush; mapping before it reports the value
        // from the previous save.
        Attraction saved = attractionRepository.saveAndFlush(attraction);
        evictParent(saved);

        // The most consequential event in the service. An itinerary built around
        // a 90-minute visit is quietly wrong the moment that becomes 180, and
        // nothing else would ever tell the Itinerary Service.
        if (saved.getStatus() == ContentStatus.PUBLISHED) {
            outboxWriter.write(CatalogEventType.ATTRACTION_UPDATED, saved);
        }
        log.info("Attraction updated: {} ({})", saved.getSlug(), saved.getId());

        return AttractionAdminResponse.from(saved);
    }

    /**
     * Publish, unpublish, archive or restore — the same lifecycle destinations
     * follow, enforced by the same {@link ContentStatus#canTransitionTo} rules.
     */
    @Transactional
    public AttractionAdminResponse changeStatus(UUID id, ContentStatus target) {
        Attraction attraction = require(id);
        ContentStatus current = attraction.getStatus();

        if (!current.canTransitionTo(target)) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Attraction " + id + " cannot go from " + current + " to " + target);
        }
        if (target == ContentStatus.PUBLISHED && !attraction.isCompleteForPublishing()) {
            throw new AppException(ErrorCode.INCOMPLETE_FOR_PUBLISH,
                    "Attraction " + id + " is missing fields required for publishing");
        }

        attraction.setStatus(target);

        Attraction saved = attractionRepository.saveAndFlush(attraction);
        evictParent(saved);

        if (target == ContentStatus.PUBLISHED) {
            outboxWriter.write(CatalogEventType.ATTRACTION_PUBLISHED, saved);
        } else if (target == ContentStatus.ARCHIVED) {
            outboxWriter.write(CatalogEventType.ATTRACTION_ARCHIVED, saved);
        }
        log.info("Attraction {} moved {} -> {}", saved.getSlug(), current, target);

        return AttractionAdminResponse.from(saved);
    }

    /**
     * Archives. As with destinations, this never deletes a row — the Itinerary
     * Service will hold attraction ids in its own database, and a hard delete
     * would leave it pointing at nothing with no foreign key to catch it.
     *
     * <p>Idempotent: archiving something already archived succeeds quietly.
     */
    @Transactional
    public void archive(UUID id) {
        Attraction attraction = require(id);

        if (attraction.getStatus() == ContentStatus.ARCHIVED) {
            return;
        }
        attraction.setStatus(ContentStatus.ARCHIVED);
        evictParent(attraction);
        outboxWriter.write(CatalogEventType.ATTRACTION_ARCHIVED, attraction);
        log.info("Attraction archived: {} ({})", attraction.getSlug(), id);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Every attraction write invalidates its destination's cached list.
     *
     * <p>Creating one does not, and does not call this: a new attraction is a
     * DRAFT, so no public read can be holding it yet.
     */
    private void evictParent(Attraction attraction) {
        Destination destination = attraction.getDestination();
        catalogCache.evictAttractionsOf(destination.getId(), destination.getSlug());
    }

    private Attraction require(UUID id) {
        return attractionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Attraction", id));
    }

    /**
     * Uniqueness is per destination, not global — two places may each have a
     * {@code main-beach}. Refused rather than suffixed, for the same reason as
     * destinations: {@code ella-rock-2} in a URL helps nobody.
     */
    private void requireSlugAvailable(UUID destinationId, String slug) {
        if (attractionRepository.existsByDestinationIdAndSlug(destinationId, slug)) {
            throw new AppException(ErrorCode.SLUG_ALREADY_EXISTS,
                    "Attraction slug already in use in this destination: " + slug);
        }
    }

    private void requireCoordinatePair(Attraction attraction) {
        BigDecimal latitude = attraction.getLatitude();
        BigDecimal longitude = attraction.getLongitude();

        if (latitude != null && longitude == null) {
            throw new ValidationException("longitude", "is required when latitude is set");
        }
        if (longitude != null && latitude == null) {
            throw new ValidationException("latitude", "is required when longitude is set");
        }
    }

    /**
     * "Free" and "costs 500 rupees" cannot both be true.
     *
     * <p>The {@code ck_attractions_free_fee} constraint refuses this too, but a
     * database constraint violation reaches the admin as a generic conflict. Here
     * it becomes a field error naming the field to fix.
     *
     * <p>A zero fee alongside {@code free} is allowed: that is the same fact
     * stated twice, not a contradiction.
     */
    private void requireCoherentPricing(Attraction attraction) {
        BigDecimal fee = attraction.getEntranceFee();
        if (attraction.isFree() && fee != null && fee.signum() > 0) {
            throw new ValidationException("entranceFee",
                    "must be zero or omitted when the attraction is free");
        }
    }

    /** Stored upper case to satisfy {@code ck_attractions_currency}. */
    private static String normalizeCurrency(String currency) {
        return currency == null ? DEFAULT_CURRENCY : currency.trim().toUpperCase(Locale.ROOT);
    }
}
