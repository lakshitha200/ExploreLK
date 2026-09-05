package com.explorelk.destination.destination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle rules, stated once as a table.
 *
 * <p>These are the rules that decide whether unpublished content can reach a
 * traveler, so they are worth pinning exhaustively rather than by example — the
 * matrix below covers all nine transitions, not the three anyone thinks of.
 */
class ContentStatusTest {

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
            // Publishing and pulling back are the everyday moves.
            "DRAFT,     PUBLISHED, true",
            "DRAFT,     ARCHIVED,  true",
            "PUBLISHED, DRAFT,     true",
            "PUBLISHED, ARCHIVED,  true",
            "ARCHIVED,  DRAFT,     true",

            // Archived content cannot go straight back live. Restoring it to a
            // draft first forces someone to look at it before travelers do —
            // content is usually archived because it was wrong.
            "ARCHIVED,  PUBLISHED, false",

            // A no-op transition is a client bug worth reporting, not a silent
            // success: it usually means the UI and the server disagree about what
            // state the row is in.
            "DRAFT,     DRAFT,     false",
            "PUBLISHED, PUBLISHED, false",
            "ARCHIVED,  ARCHIVED,  false",
    })
    void transitionMatrix(ContentStatus from, ContentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(ContentStatus.class)
    @DisplayName("a null target is never a legal transition")
    void nullTargetIsRejected(ContentStatus from) {
        assertThat(from.canTransitionTo(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ContentStatus.class)
    @DisplayName("PUBLISHED is the only publicly visible state")
    void onlyPublishedIsVisible(ContentStatus status) {
        // If this ever becomes true for a second value, every public endpoint in
        // the service starts leaking at once.
        assertThat(status.isPubliclyVisible()).isEqualTo(status == ContentStatus.PUBLISHED);
    }
}
