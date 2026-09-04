package com.explorelk.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The raw token from the emailed link. */
public record VerifyEmailRequest(

        @NotBlank(message = "Token is required")
        String token
) {
}
