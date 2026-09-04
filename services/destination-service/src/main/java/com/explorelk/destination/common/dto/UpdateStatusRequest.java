package com.explorelk.destination.common.dto;

import com.explorelk.destination.destination.ContentStatus;
import jakarta.validation.constraints.NotNull;

/**
 * The body of every status change, for destinations and attractions alike.
 *
 * <pre>
 * PATCH /api/v1/admin/destinations/{id}/status
 * { "status": "PUBLISHED" }
 * </pre>
 *
 * <p>A body rather than a path segment such as {@code /publish}: the legal
 * transitions are a property of the content's current state (see
 * {@link ContentStatus#canTransitionTo}), not of four separate endpoints that
 * would each have to re-derive the same rules.
 */
public record UpdateStatusRequest(

        @NotNull(message = "is required")
        ContentStatus status
) {
}
