package com.explorelk.auth.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * @param approved true approves the provider, false withdraws approval.
 *                 {@code Boolean} rather than {@code boolean}: a primitive
 *                 defaults to false when the field is missing, so a malformed
 *                 request would silently read as a rejection
 */
public record ProviderApprovalRequest(
        @NotNull(message = "is required")
        Boolean approved) {
}
