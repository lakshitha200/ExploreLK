package com.explorelk.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for resend-verification and forgot-password.
 *
 * <p>No @Email constraint: a malformed address must produce the same bland 202 as a
 * valid one, or the difference in response becomes its own signal.
 */
public record EmailOnlyRequest(

        @NotBlank(message = "Email is required")
        String email
) {
}
