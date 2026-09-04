package com.explorelk.destination.category.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Adding a term to the category vocabulary.
 *
 * <p>The {@code code} is supplied by the admin rather than derived from the name,
 * unlike a destination slug. It is a deliberate act of API design: this value
 * becomes part of the contract the moment a client writes
 * {@code ?category=TEA_COUNTRY}, so it should be chosen, not generated from
 * whatever display name happened to be typed first.
 *
 * <p>The pattern matches the {@code ck_categories_code_format} constraint, so an
 * invalid code is a field error rather than a database exception.
 */
public record CreateCategoryRequest(

        @NotBlank(message = "is required")
        @Size(max = 24, message = "must be at most 24 characters")
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
                message = "must start with a letter and contain only letters, digits and underscores")
        String code,

        @NotBlank(message = "is required")
        @Size(max = 60, message = "must be at most 60 characters")
        String name,

        @Size(max = 200, message = "must be at most 200 characters")
        String description,

        /** A frontend icon hint — a name such as {@code leaf}, never a URL. */
        @Size(max = 40, message = "must be at most 40 characters")
        String icon,

        @Min(value = 0, message = "cannot be negative")
        Short sortOrder
) {
}
