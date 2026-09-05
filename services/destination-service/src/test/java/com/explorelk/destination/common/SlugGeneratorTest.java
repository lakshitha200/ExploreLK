package com.explorelk.destination.common;

import com.explorelk.destination.common.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The slug is a public URL key generated from admin-supplied text, so the thing
 * worth testing is not the happy path but everything a real place name can throw
 * at it: accents, punctuation, apostrophes, and names long enough to overflow the
 * column.
 */
class SlugGeneratorTest {

    /** The exact shape {@code ck_destinations_slug} enforces in the database. */
    private static final Pattern DB_CONSTRAINT = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "Ella,                      ella",
            "Nuwara Eliya,              nuwara-eliya",
            "GALLE,                     galle",
            "  Arugam  Bay  ,           arugam-bay",
            "Little Adam's Peak,        little-adam-s-peak",
            "Nine Arches Bridge,        nine-arches-bridge",
            "St. Clair's Falls,         st-clair-s-falls",
            "Hikkaduwa / Unawatuna,     hikkaduwa-unawatuna",
            "Yala (Block 1),            yala-block-1",
            "Kandy—Ella,                kandy-ella",
    })
    @DisplayName("normalizes real place names to a URL segment")
    void normalizesNames(String input, String expected) {
        assertThat(SlugGenerator.from(input, "name")).isEqualTo(expected);
    }

    @Test
    @DisplayName("strips diacritics rather than dropping the letters")
    void stripsDiacritics() {
        // Losing the letter entirely would turn "Ampara" into "mpr". Decomposing
        // and dropping only the combining marks keeps the word readable.
        assertThat(SlugGenerator.from("Éllá Rôck", "name")).isEqualTo("ella-rock");
    }

    @Test
    @DisplayName("truncates to the column width without leaving a trailing hyphen")
    void truncatesCleanly() {
        String slug = SlugGenerator.from("a ".repeat(120), "name");

        assertThat(slug).hasSizeLessThanOrEqualTo(SlugGenerator.MAX_LENGTH);
        // Truncation happens before the trim, so cutting mid-word can never leave
        // a dangling separator that the CHECK constraint would then reject.
        assertThat(slug).doesNotEndWith("-");
        assertThat(DB_CONSTRAINT.matcher(slug).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---", "!!!", "...", "の"})
    @DisplayName("fails loudly when nothing usable survives normalization")
    void rejectsUnusableNames(String input) {
        // The alternative is an empty slug reaching the database and failing there
        // with a constraint error no admin can act on.
        assertThatThrownBy(() -> SlugGenerator.from(input, "name"))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .singleElement()
                        .satisfies(fe -> assertThat(fe.field()).isEqualTo("name")));
    }

    @Test
    @DisplayName("null is a validation failure, not a crash")
    void rejectsNull() {
        assertThatThrownBy(() -> SlugGenerator.from(null, "name"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("every output satisfies the database CHECK constraint")
    void alwaysMatchesTheConstraint() {
        String[] names = {
                "Ella", "Nuwara Eliya", "Yala (Block 1)", "St. Clair's Falls",
                "Kandy—Ella", "  spaced  out  ", "MIXED case 123", "Éllá Rôck"
        };
        for (String name : names) {
            assertThat(DB_CONSTRAINT.matcher(SlugGenerator.from(name, "name")).matches())
                    .as("slug for '%s'", name)
                    .isTrue();
        }
    }
}
