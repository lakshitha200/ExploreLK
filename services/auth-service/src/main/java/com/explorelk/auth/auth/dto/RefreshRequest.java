package com.explorelk.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for refresh and logout. The refresh token travels in the body, not a header. */
public record RefreshRequest(

        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
}
