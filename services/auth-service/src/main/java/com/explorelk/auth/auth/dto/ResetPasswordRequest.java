package com.explorelk.auth.auth.dto;

import com.explorelk.auth.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

/** Consumes a reset token and sets a new password. */
public record ResetPasswordRequest(

        @NotBlank(message = "Token is required")
        String token,

        @NotBlank(message = "New password is required")
        @ValidPassword
        String newPassword
) {
}
