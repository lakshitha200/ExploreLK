package com.explorelk.destination.destination.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Creating a destination.
 *
 * <p>Only {@code name} is required, and the row lands as {@code DRAFT}. That is
 * the point: a draft is content still being written, so an admin has to be able
 * to save a half-finished destination and come back to it. The fields the
 * traveler-facing UI actually needs are enforced on the transition to
 * {@code PUBLISHED} instead — see {@code Destination.isCompleteForPublishing()}.
 *
 * <p>There is no {@code slug} field. Slugs are generated server-side from the
 * name; accepting one from a client hands out control of a public URL key.
 * There is no {@code status} field either, for the same reason a bank does not
 * let you set your own balance on account creation: publishing is a transition
 * with rules, not an attribute.
 *
 * <p>The coordinate bounds are duplicated by {@code CHECK} constraints in the
 * database. That is intentional belt-and-braces — a destination silently placed
 * in the Indian Ocean is invisible until someone's itinerary routes through it.
 */
public record CreateDestinationRequest(

        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most 120 characters")
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

        /** Editorial ranking for the default list order. Null means zero. */
        @Min(value = 0, message = "cannot be negative")
        Integer popularityScore,

        /**
         * Category codes, e.g. {@code ["NATURE", "HIKING"]}. Every code is checked
         * against the vocabulary — an unknown one is a 400 with a field error, not
         * a row quietly created with no tags.
         */
        List<String> categories
) {
}
