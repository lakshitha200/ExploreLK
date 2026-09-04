package com.explorelk.destination.attraction.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A partial update of an attraction. Null means "leave this alone", exactly as
 * for {@code UpdateDestinationRequest}, and for the same reasons.
 *
 * <p>Neither the slug nor the parent destination can be changed here. Moving an
 * attraction between destinations is not an edit — it changes what the row
 * <em>is</em>, invalidates the slug's uniqueness scope, and would silently break
 * every itinerary built around "these things are near each other". If it is ever
 * needed it deserves its own endpoint that says so.
 *
 * <p>There is no {@code version} field either: unlike destinations, attractions
 * carry no optimistic-locking column. Two admins editing the same attraction is
 * far less likely than two editing the same headline destination, and the table
 * was built without one. If that changes, the column, the entity field and this
 * record all move together.
 */
public record UpdateAttractionRequest(

        @Size(min = 1, max = 120, message = "must be between 1 and 120 characters")
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

        /**
         * Changing this is the single most consequential edit in the service. An
         * itinerary built around a 90-minute visit is quietly wrong once the
         * visit becomes 180 — which is why {@code ATTRACTION_UPDATED} exists in
         * the event list for Step 9.
         */
        @Min(value = 1, message = "must be at least 1 minute")
        Short visitDurationMinutes,

        Boolean free,

        @DecimalMin(value = "0", message = "cannot be negative")
        @Digits(integer = 8, fraction = 2, message = "allows at most 2 decimal places")
        BigDecimal entranceFee,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code")
        String currency,

        Boolean alwaysOpen,

        /** When present, replaces the whole schedule rather than merging into it. */
        Map<String, List<String>> openingHours,

        @Size(max = 500, message = "must be at most 500 characters")
        String imageUrl,

        @Min(value = 0, message = "cannot be negative")
        Integer popularityScore,

        /** When present, replaces the whole tag set rather than adding to it. */
        List<String> categories
) {
}
