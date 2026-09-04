package com.explorelk.auth.auth.dto;

import com.explorelk.auth.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

/** Changes the signed-in user's password. Requires the current one. */
public record ChangePasswordRequest(

        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @ValidPassword
        String newPassword
) {
}
