package com.explorelk.destination.attraction.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Creating an attraction under a destination.
 *
 * <p>The destination comes from the path, not from the body: an attraction
 * belongs to exactly one place, and letting the body name a different parent
 * from the URL is an ambiguity with no upside.
 *
 * <p>As with destinations, only the name is required and the row lands as
 * {@code DRAFT}. What publishing needs — a summary, a location, a visit duration
 * and a clear answer on cost — is checked on the transition.
 */
public record CreateAttractionRequest(

        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most 120 characters")
        String name,

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

        /** How long to allow for a visit. The Itinerary Service plans days with it. */
        @Min(value = 1, message = "must be at least 1 minute")
        Short visitDurationMinutes,

        /**
         * True means "costs nothing". Leaving both this and {@code entranceFee}
         * unset means "not recorded yet", which is a different fact and is shown
         * differently.
         */
        Boolean free,

        @DecimalMin(value = "0", message = "cannot be negative")
        @Digits(integer = 8, fraction = 2, message = "allows at most 2 decimal places")
        BigDecimal entranceFee,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code")
        String currency,

        /** True for a viewpoint or a bridge — somewhere with no gate and no hours. */
        Boolean alwaysOpen,

        /**
         * {@code {"mon": ["06:00", "18:00"], "sat": ["06:00", "20:00"]}}. Days
         * left out are closed. Validated by {@code OpeningHoursCodec} before it is
         * stored — Postgres only checks that JSONB is valid JSON, which
         * {@code {"funday": ["25:99"]}} also is.
         */
        Map<String, List<String>> openingHours,

        @Size(max = 500, message = "must be at most 500 characters")
        String imageUrl,

        @Min(value = 0, message = "cannot be negative")
        Integer popularityScore,

        List<String> categories
) {
}
