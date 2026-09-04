package com.explorelk.auth.auth.dto;

import com.explorelk.auth.common.validation.ValidPassword;
import com.explorelk.auth.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public registration payload.
 *
 * <p>{@code role} is accepted from the client but only {@code TRAVELER} and
 * {@code PROVIDER} are allowed — the service rejects anything else. An ADMIN can
 * never be created through this endpoint no matter what is posted.
 *
 * <p>Unknown JSON fields are rejected (see {@code spring.jackson.deserialization
 * .fail-on-unknown-properties}), so a client cannot smuggle in a {@code status} or
 * {@code providerApproved} field hoping it gets bound.
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @ValidPassword
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 150, message = "Full name must be at most 150 characters")
        String fullName,

        @Size(max = 30, message = "Phone must be at most 30 characters")
        @Pattern(regexp = "^$|^[+0-9 ()-]{7,30}$", message = "Phone contains invalid characters")
        String phone,

        @NotNull(message = "Role is required")
        UserRole role
) {
}
