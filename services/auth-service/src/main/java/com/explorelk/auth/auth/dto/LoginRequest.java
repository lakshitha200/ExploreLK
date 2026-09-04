package com.explorelk.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login credentials.
 *
 * <p>No {@code @Email} or password-policy validation here on purpose. A rejected
 * login must look the same whatever was wrong, and "that is not a valid email" is a
 * different answer from "wrong password" — an attacker learns the address format is
 * off, or worse, that the format was fine. Everything that fails, fails as
 * {@code INVALID_CREDENTIALS}.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {
}
