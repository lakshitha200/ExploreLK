package com.explorelk.destination.destination;

import com.explorelk.destination.category.CategoryService;
import com.explorelk.destination.common.ErrorCode;
import com.explorelk.destination.common.PageResponse;
import com.explorelk.destination.common.Pagination;
import com.explorelk.destination.common.SlugGenerator;
import com.explorelk.destination.common.exception.AppException;
import com.explorelk.destination.common.exception.NotFoundException;
import com.explorelk.destination.common.exception.ValidationException;
import com.explorelk.destination.destination.dto.CreateDestinationRequest;
import com.explorelk.destination.destination.dto.DestinationAdminResponse;
import com.explorelk.destination.destination.dto.UpdateDestinationRequest;
import com.explorelk.destination.search.DestinationQuery;
import com.explorelk.destination.search.DestinationSearchSpecs;
import com.explorelk.destination.search.DestinationSort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that writes to the destination catalog.
 *
 * <p>A separate class from {@link DestinationService} on purpose. Public reads
 * are restricted to {@code PUBLISHED} and never write; admin operations write,
 * and see drafts and archived rows. Merged into one class, every method would
 * have to remember which world it is in — and the day someone forgets, drafts
 * appear on a public endpoint. Two classes make that boundary structural rather
 * than a matter of care.
 *
 * <p>Nothing here deletes a row. See {@link #archive(UUID)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DestinationAdminService {

    private final DestinationRepository destinationRepository;
    private final CategoryService categoryService;

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * The admin list: drafts and archived content included, optionally narrowed
     * to one status.
     *
     * <p>Shares the search, sort and paging machinery with the public list — the
     * only difference is that {@code published()} is not forced on.
     */
    @Transactional(readOnly = true)
    public PageResponse<DestinationAdminResponse> list(DestinationQuery query,
                                                       ContentStatus status,
                                                       int page,
                                                       int size,
                                                       String sort,
                                                       String direction) {
        requireKnownCategory(query.category());

        Pageable pageable = Pagination.of(page, size, DestinationSort.from(sort).toSort(direction));
        Page<Destination> results = destinationRepository.findAll(
                DestinationSearchSpecs.adminSearch(query, status), pageable);

        return PageResponse.from(results, DestinationAdminResponse::from);
    }

    /**
     * One destination in any status, by id or slug.
     *
     * <p>The public endpoint 404s on a draft; this one returns it. That is the
     * whole reason an admin read exists — someone has to be able to look at what
     * they are writing before it goes live.
     */
    @Transactional(readOnly = true)
    public DestinationAdminResponse get(String idOrSlug) {
        return DestinationAdminResponse.from(require(idOrSlug));
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Creates a destination. It always lands as {@code DRAFT}: publishing is a
     * transition with rules, not a field a client can set on the way in.
     */
    @Transactional
    public DestinationAdminResponse create(CreateDestinationRequest request) {
        String slug = SlugGenerator.from(request.name(), "name");
        requireSlugAvailable(slug);

        Destination destination = Destination.builder()
                .slug(slug)
                .name(request.name().trim())
                .district(request.district())
                .province(request.province())
                .summary(request.summary())
                .description(request.description())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .recommendedDays(request.recommendedDays())
                .coverImageUrl(request.coverImageUrl())
                .popularityScore(request.popularityScore() == null ? 0 : request.popularityScore())
                .status(ContentStatus.DRAFT)
                .categories(categoryService.resolve(request.categories(), "categories"))
                .build();

        requireCoordinatePair(destination);

        Destination saved = destinationRepository.save(destination);
        log.info("Destination created: {} ({})", saved.getSlug(), saved.getId());

        return DestinationAdminResponse.from(saved);
    }

    /**
     * Partial update. Null fields are left alone — see
     * {@link UpdateDestinationRequest} for why, and for why neither the slug nor
     * the status can be changed here.
     */
    @Transactional
    public DestinationAdminResponse update(UUID id, UpdateDestinationRequest request) {
        Destination destination = require(id);
        requireCurrentVersion(destination, request.version());

        if (request.name() != null) {
            destination.setName(request.name().trim());
        }
        if (request.district() != null) {
            destination.setDistrict(request.district());
        }
        if (request.province() != null) {
            destination.setProvince(request.province());
        }
        if (request.summary() != null) {
            destination.setSummary(request.summary());
        }
        if (request.description() != null) {
            destination.setDescription(request.description());
        }
        if (request.latitude() != null) {
            destination.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            destination.setLongitude(request.longitude());
        }
        if (request.recommendedDays() != null) {
            destination.setRecommendedDays(request.recommendedDays());
        }
        if (request.coverImageUrl() != null) {
            destination.setCoverImageUrl(request.coverImageUrl());
        }
        if (request.popularityScore() != null) {
            destination.setPopularityScore(request.popularityScore());
        }
        if (request.categories() != null) {
            destination.setCategories(categoryService.resolve(request.categories(), "categories"));
        }

        requireCoordinatePair(destination);

        // Flushed rather than left to commit, because the response carries `version`
        // and `updatedAt` — and both are written by the flush. Returning the entity
        // as it looks beforehand hands the admin a version number that is already
        // stale, so their next edit would be rejected as a conflict with themselves.
        Destination saved = destinationRepository.saveAndFlush(destination);
        log.info("Destination updated: {} ({})", saved.getSlug(), saved.getId());

        return DestinationAdminResponse.from(saved);
    }

    /**
     * Moves content along the lifecycle.
     *
     * <pre>
     * create -&gt; DRAFT --publish--&gt; PUBLISHED --archive--&gt; ARCHIVED
     *             ^                     |                      |
     *             +---- unpublish ------+                      |
     *             +--------------- restore ---------------------+
     * </pre>
     *
     * <p>Two rules, and both belong here rather than on the entity setter:
     * the transition itself must be legal, and publishing additionally requires
     * the fields the traveler-facing UI needs. Completeness is checked on the
     * transition and not on create, because otherwise an admin could never save
     * half-finished work.
     */
    @Transactional
    public DestinationAdminResponse changeStatus(UUID id, ContentStatus target) {
        Destination destination = require(id);
        ContentStatus current = destination.getStatus();

        if (!current.canTransitionTo(target)) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Destination " + id + " cannot go from " + current + " to " + target);
        }
        if (target == ContentStatus.PUBLISHED && !destination.isCompleteForPublishing()) {
            throw new AppException(ErrorCode.INCOMPLETE_FOR_PUBLISH,
                    "Destination " + id + " is missing fields required for publishing");
        }

        destination.setStatus(target);

        // Flushed for the same reason as update(): the response reports the version
        // this change produced, not the one it started from.
        Destination saved = destinationRepository.saveAndFlush(destination);
        log.info("Destination {} moved {} -> {}", saved.getSlug(), current, target);

        return DestinationAdminResponse.from(saved);
    }

    /**
     * The {@code DELETE} endpoint. It archives; it does not delete.
     *
     * <p>Trip and Itinerary services store {@code destination_id} values in their
     * own databases, where no foreign key can protect them. A hard delete turns
     * every one of those into a dangling reference, silently and permanently.
     * Archiving keeps the id resolvable forever, which is exactly what a
     * cross-service reference needs. The verb stays {@code DELETE} because that
     * is what an admin UI expects to call.
     *
     * <p>Archiving something already archived is a no-op rather than a conflict:
     * {@code DELETE} is supposed to be idempotent, and a client retrying after a
     * dropped response should not get an error for succeeding twice.
     */
    @Transactional
    public void archive(UUID id) {
        Destination destination = require(id);

        if (destination.getStatus() == ContentStatus.ARCHIVED) {
            return;
        }
        destination.setStatus(ContentStatus.ARCHIVED);
        log.info("Destination archived: {} ({})", destination.getSlug(), id);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Loads a destination in any status, or 404s.
     *
     * <p>Public because the attraction admin service hangs content off a
     * destination and needs the same "exists, in any status" resolution — and
     * needs it to fail the same way, so creating an attraction under a
     * nonexistent id reads as a missing destination rather than a missing
     * attraction.
     */
    public Destination require(UUID id) {
        return destinationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Destination", id));
    }

    private Destination require(String idOrSlug) {
        return asUuid(idOrSlug)
                .map(destinationRepository::findById)
                .orElseGet(() -> destinationRepository.findBySlug(idOrSlug))
                .orElseThrow(() -> new NotFoundException("Destination", idOrSlug));
    }

    /**
     * A generated slug that is already taken is refused rather than suffixed.
     *
     * <p>{@code ella-2} would be created silently and then live in a URL forever,
     * and no admin looking at the list would know which of the two is which. A
     * 409 asks the person who knows to pick a distinguishing name — "Ella" and
     * "Ella (Uva)" — which is information only they have.
     */
    private void requireSlugAvailable(String slug) {
        if (destinationRepository.existsBySlug(slug)) {
            throw new AppException(ErrorCode.SLUG_ALREADY_EXISTS,
                    "Destination slug already in use: " + slug);
        }
    }

    /**
     * Rejects an edit made against a copy someone else has since changed.
     *
     * <p>The database enforces this too, at flush time, through {@code @Version}.
     * Checking here as well turns the common case into a clean 409 before any
     * work is done, and gives the admin a message about the row they were
     * looking at rather than a Hibernate exception about a stale entity.
     */
    private void requireCurrentVersion(Destination destination, Integer expected) {
        if (expected != null && expected != destination.getVersion()) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Destination " + destination.getId() + " is at version "
                            + destination.getVersion() + ", edit was based on " + expected);
        }
    }

    /**
     * A half-set coordinate is always a bug, so it is caught here as a field
     * error as well as by the {@code ck_destinations_latlng} constraint. Without
     * the check a PATCH that sets only the latitude would put the place on the
     * prime meridian, off the coast of Ghana.
     */
    private void requireCoordinatePair(Destination destination) {
        BigDecimal latitude = destination.getLatitude();
        BigDecimal longitude = destination.getLongitude();

        if (latitude != null && longitude == null) {
            throw new ValidationException("longitude", "is required when latitude is set");
        }
        if (longitude != null && latitude == null) {
            throw new ValidationException("latitude", "is required when longitude is set");
        }
    }

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
