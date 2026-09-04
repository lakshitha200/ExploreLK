package com.explorelk.auth.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Editable profile fields.
 *
 * <p>Deliberately excludes email, role and status. Email change needs verification on
 * both addresses and is out of MVP scope; role and status are decisions an admin makes,
 * never the account holder. Since unknown JSON fields are rejected, posting
 * {@code "role":"ADMIN"} here is a 400 rather than something to defend against.
 *
 * <p>A null field means "leave it alone" — this is a PATCH, not a replace.
 */
public record UpdateProfileRequest(

        @Size(min = 1, max = 150, message = "Full name must be between 1 and 150 characters")
        String fullName,

        @Size(max = 30, message = "Phone must be at most 30 characters")
        @Pattern(regexp = "^$|^[+0-9 ()-]{7,30}$", message = "Phone contains invalid characters")
        String phone
) {
}
