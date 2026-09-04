package com.explorelk.destination.destination;

import com.explorelk.destination.common.PageResponse;
import com.explorelk.destination.common.dto.UpdateStatusRequest;
import com.explorelk.destination.destination.dto.CreateDestinationRequest;
import com.explorelk.destination.destination.dto.DestinationAdminResponse;
import com.explorelk.destination.destination.dto.UpdateDestinationRequest;
import com.explorelk.destination.search.DestinationQuery;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Writing the destination catalog. {@code ADMIN} or {@code SUPER_ADMIN} only.
 *
 * <p>A different controller from the public {@link DestinationController}, on a
 * different path prefix, calling a different service. None of that is
 * decoration: it means there is no code path a public request and an admin
 * request share, so a draft cannot leak onto a traveler-facing endpoint by way
 * of a shared method that grew a boolean parameter.
 *
 * <p>The role check is stated twice — once by path in {@code SecurityConfig},
 * once here. The path rule is what protects an endpoint whose author forgot to
 * annotate it; the annotation is what keeps this class protected if it is ever
 * remapped somewhere outside {@code /api/v1/admin/**}.
 */
@RestController
@RequestMapping("/api/v1/admin/destinations")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@RequiredArgsConstructor
public class DestinationAdminController {

    private final DestinationAdminService destinationAdminService;

    /**
     * The catalog as an admin sees it — drafts and archived rows included.
     *
     * <pre>
     * GET /api/v1/admin/destinations
     * GET /api/v1/admin/destinations?status=DRAFT
     * GET /api/v1/admin/destinations?search=ell&amp;sort=createdAt
     * </pre>
     */
    @GetMapping
    public PageResponse<DestinationAdminResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "popularity") String sort,
            @RequestParam(required = false) String direction) {

        DestinationQuery query = DestinationQuery.of(search, category, district, province);
        return destinationAdminService.list(query, status, page, size, sort, direction);
    }

    /** One destination in any status, by id or slug — so a draft can be previewed. */
    @GetMapping("/{idOrSlug}")
    public DestinationAdminResponse get(@PathVariable String idOrSlug) {
        return destinationAdminService.get(idOrSlug);
    }

    /**
     * Creates a destination as a {@code DRAFT}.
     *
     * <p>{@code 201} with a {@code Location} pointing at the admin resource, not
     * the public one — the row is not publicly resolvable yet, and will 404 there
     * until it is published.
     */
    @PostMapping
    public ResponseEntity<DestinationAdminResponse> create(
            @Valid @RequestBody CreateDestinationRequest request) {

        DestinationAdminResponse created = destinationAdminService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/admin/destinations/" + created.id()))
                .body(created);
    }

    /** Partial update. Fields left out are left alone. */
    @PatchMapping("/{id}")
    public DestinationAdminResponse update(@PathVariable UUID id,
                                           @Valid @RequestBody UpdateDestinationRequest request) {
        return destinationAdminService.update(id, request);
    }

    /**
     * Publish, unpublish, archive or restore.
     *
     * <pre>
     * PATCH /api/v1/admin/destinations/{id}/status
     * { "status": "PUBLISHED" }
     * </pre>
     *
     * <p>An illegal move is a {@code 409 INVALID_STATUS_TRANSITION}; publishing
     * something still missing required fields is a {@code 409
     * INCOMPLETE_FOR_PUBLISH}. Both are conflicts with the resource's current
     * state rather than bad requests — the same body would have succeeded a
     * moment earlier, or will succeed once the content is finished.
     */
    @PatchMapping("/{id}/status")
    public DestinationAdminResponse changeStatus(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateStatusRequest request) {
        return destinationAdminService.changeStatus(id, request.status());
    }

    /**
     * Archives. Does not delete — see {@link DestinationAdminService#archive}.
     *
     * <p>{@code 204} either way, including when the destination was already
     * archived: {@code DELETE} is meant to be idempotent, and a client retrying
     * after a dropped response should not be punished for succeeding twice.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        destinationAdminService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
