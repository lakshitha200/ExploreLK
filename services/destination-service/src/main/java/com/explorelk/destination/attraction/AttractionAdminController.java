package com.explorelk.destination.attraction;

import com.explorelk.destination.attraction.dto.AttractionAdminResponse;
import com.explorelk.destination.attraction.dto.CreateAttractionRequest;
import com.explorelk.destination.attraction.dto.UpdateAttractionRequest;
import com.explorelk.destination.common.dto.UpdateStatusRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Writing attractions. {@code ADMIN} or {@code SUPER_ADMIN} only.
 *
 * <p>The paths are asymmetric on purpose. Creating and listing go through the
 * parent — {@code /admin/destinations/{destinationId}/attractions} — because an
 * attraction cannot exist without one, and the URL should say so. Editing an
 * attraction that already exists addresses it directly at
 * {@code /admin/attractions/{id}}, because its id is sufficient and threading
 * the parent through every edit URL only creates a second thing that can be
 * wrong.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@RequiredArgsConstructor
public class AttractionAdminController {

    private final AttractionAdminService attractionAdminService;

    /** Every attraction of a destination, drafts and archived rows included. */
    @GetMapping("/destinations/{destinationId}/attractions")
    public List<AttractionAdminResponse> listOf(@PathVariable UUID destinationId) {
        return attractionAdminService.listOf(destinationId);
    }

    /** Creates an attraction under a destination, as a {@code DRAFT}. */
    @PostMapping("/destinations/{destinationId}/attractions")
    public ResponseEntity<AttractionAdminResponse> create(
            @PathVariable UUID destinationId,
            @Valid @RequestBody CreateAttractionRequest request) {

        AttractionAdminResponse created = attractionAdminService.create(destinationId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/admin/attractions/" + created.id()))
                .body(created);
    }

    /**
     * One attraction in any status.
     *
     * <p>This is the endpoint that keeps archived content reachable: the public
     * {@code /attractions/{id}} 404s once something is retired, while the id
     * still resolves here for whoever has to look at it or restore it.
     */
    @GetMapping("/attractions/{id}")
    public AttractionAdminResponse get(@PathVariable UUID id) {
        return attractionAdminService.get(id);
    }

    /** Partial update. Fields left out are left alone. */
    @PatchMapping("/attractions/{id}")
    public AttractionAdminResponse update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateAttractionRequest request) {
        return attractionAdminService.update(id, request);
    }

    /** Publish, unpublish, archive or restore. */
    @PatchMapping("/attractions/{id}/status")
    public AttractionAdminResponse changeStatus(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateStatusRequest request) {
        return attractionAdminService.changeStatus(id, request.status());
    }

    /** Archives. Never deletes — the row and its id outlive the content. */
    @DeleteMapping("/attractions/{id}")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        attractionAdminService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
