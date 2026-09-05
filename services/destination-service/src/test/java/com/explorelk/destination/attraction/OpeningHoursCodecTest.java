package com.explorelk.destination.attraction;

import com.explorelk.destination.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres validates that JSONB is valid JSON and nothing more, so everything
 * meaningful about opening hours is checked here or nowhere.
 * {@code {"funday": ["25:99", "banana"]}} is perfectly valid JSON.
 */
class OpeningHoursCodecTest {

    private OpeningHoursCodec codec;

    @BeforeEach
    void setUp() {
        codec = new OpeningHoursCodec(JsonMapper.builder().build());
    }

    @Test
    @DisplayName("serializes a schedule in week order, whatever order it arrived in")
    void normalizesToWeekOrder() {
        Map<String, List<String>> hours = new LinkedHashMap<>();
        hours.put("sun", List.of("06:00", "18:00"));
        hours.put("mon", List.of("05:30", "18:30"));
        hours.put("wed", List.of("05:30", "18:30"));

        // Two identical schedules must serialize identically, or anything that
        // compares or caches these values sees spurious differences.
        assertThat(codec.toJson(hours, "openingHours")).isEqualTo(
                "{\"mon\":[\"05:30\",\"18:30\"],\"wed\":[\"05:30\",\"18:30\"],\"sun\":[\"06:00\",\"18:00\"]}");
    }

    @Test
    @DisplayName("accepts day names in any case, with surrounding space")
    void normalizesDayKeys() {
        assertThat(codec.toJson(Map.of(" MON ", List.of("09:00", "17:00")), "openingHours"))
                .isEqualTo("{\"mon\":[\"09:00\",\"17:00\"]}");
    }

    @Test
    @DisplayName("no hours recorded is null, which is not the same as always open")
    void emptyIsNull() {
        // "we have not recorded the hours" and "there is no gate" are different
        // facts, and the UI shows them differently. always_open is its own column.
        assertThat(codec.toJson(null, "openingHours")).isNull();
        assertThat(codec.toJson(Map.of(), "openingHours")).isNull();
    }

    @Test
    @DisplayName("allows a schedule that runs past midnight")
    void allowsOvernightHours() {
        // A night market really does open at 20:00 and close at 02:00. Rejecting
        // backwards times would be a rule invented by someone who never went out.
        assertThat(codec.toJson(Map.of("fri", List.of("20:00", "02:00")), "openingHours"))
                .isEqualTo("{\"fri\":[\"20:00\",\"02:00\"]}");
    }

    @Test
    @DisplayName("rejects an unknown day and names it")
    void rejectsUnknownDay() {
        assertThatThrownBy(() -> codec.toJson(Map.of("funday", List.of("06:00", "18:00")), "openingHours"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("funday");
    }

    @Test
    @DisplayName("rejects a time that is not HH:mm")
    void rejectsBadTime() {
        assertThatThrownBy(() -> codec.toJson(Map.of("mon", List.of("25:99", "banana")), "openingHours"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mon");
    }

    @Test
    @DisplayName("rejects 24:00 — midnight is 00:00")
    void rejectsTwentyFour() {
        assertThatThrownBy(() -> codec.toJson(Map.of("mon", List.of("06:00", "24:00")), "openingHours"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a day needs exactly an opening and a closing time")
    void rejectsWrongArity() {
        assertThatThrownBy(() -> codec.toJson(Map.of("mon", List.of("06:00")), "openingHours"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> codec.toJson(
                Map.of("mon", List.of("06:00", "12:00", "18:00")), "openingHours"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("equal open and close is a typo for closed, and closed is an absent day")
    void rejectsZeroLengthDay() {
        assertThatThrownBy(() -> codec.toJson(Map.of("mon", List.of("09:00", "09:00")), "openingHours"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mon");
    }
}
