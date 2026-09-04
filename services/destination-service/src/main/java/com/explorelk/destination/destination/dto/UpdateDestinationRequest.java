package com.explorelk.destination.destination.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * A partial update. Every field is optional, and <strong>null means "leave this
 * alone"</strong>, not "set it to null".
 *
 * <p>That rule is the well-known limitation of PATCH-with-a-DTO: it gives no way
 * to clear a nullable field back to empty. The alternatives were a wrapper type
 * such as {@code JsonNullable} on every field, which costs a dependency and
 * makes every read site unwrap twice, or a JSON Merge Patch document, which
 * gives up Bean Validation entirely. For this catalog the trade is easy —
 * clearing a description is rare, and it is spelled as sending {@code ""}.
 *
 * <p>{@code slug} and {@code status} are absent by design. The slug is generated
 * once at creation and then immutable: public URLs and other services' stored
 * ids both resolve on it, so renaming a destination must not break links.
 * Status changes go through {@code PATCH /status}, which enforces the transition
 * rules.
 *
 * @param version the value the admin last read. When supplied, an edit made
 *                against a stale copy is rejected with {@code CONFLICT} instead
 *                of silently overwriting whoever saved first. Optional, because
 *                a scripted fix-up has nothing to be stale about.
 */
public record UpdateDestinationRequest(

        @Size(min = 1, max = 120, message = "must be between 1 and 120 characters")
        String name,

        @Size(max = 60, message = "must be at most 60 characters")
        String district,

        @Size(max = 40, message = "must be at most 40 characters")
        String province,

        @Size(max = 300, message = "must be at most 300 characters")
        String summary,

        String description,

        @DecimalMin(value = "-90", message = "must be between -90 and 90")
        @DecimalMax(value = "90", message = "must be between -90 and 90")
        @Digits(integer = 3, fraction = 6, message = "allows at most 6 decimal places")
        BigDecimal latitude,

        @DecimalMin(value = "-180", message = "must be between -180 and 180")
        @DecimalMax(value = "180", message = "must be between -180 and 180")
        @Digits(integer = 3, fraction = 6, message = "allows at most 6 decimal places")
        BigDecimal longitude,

        @Min(value = 1, message = "must be at least 1 day")
        Short recommendedDays,

        @Size(max = 500, message = "must be at most 500 characters")
        String coverImageUrl,

        @Min(value = 0, message = "cannot be negative")
        Integer popularityScore,

        /** When present, replaces the whole tag set rather than adding to it. */
        List<String> categories,

        Integer version
) {
}
