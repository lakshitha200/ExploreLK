package com.explorelk.auth.admin.dto;

import com.explorelk.auth.common.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creating an ADMIN — the one account type no registration form can produce.
 *
 * <p>The password policy is the same one every other account is held to. An
 * administrator with a weaker password than the travelers they manage is the
 * wrong way round, and it is set by the super-admin only until the new admin's
 * first login forces a change.
 */
public record CreateAdminRequest(
        @NotBlank(message = "is required")
        @Email(message = "must be a valid email address")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @NotBlank(message = "is required")
        @ValidPassword
        String password,

        @NotBlank(message = "is required")
        @Size(max = 150, message = "must be at most 150 characters")
        String fullName) {
}
