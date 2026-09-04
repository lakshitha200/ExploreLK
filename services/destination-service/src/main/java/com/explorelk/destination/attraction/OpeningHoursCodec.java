package com.explorelk.destination.attraction;

import com.explorelk.destination.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates opening hours and turns them into the JSONB string the entity holds.
 *
 * <pre>
 * { "mon": ["06:00", "18:00"], "sat": ["06:00", "20:00"] }
 * </pre>
 *
 * <p>Postgres will check that the column contains <em>valid JSON</em>, and
 * nothing else — {@code {"funday": ["25:99", "banana"]}} is valid JSON. So the
 * shape is checked here, before the value is ever serialized, and a bad one
 * comes back as a field error rather than as data that renders wrong on a phone
 * six months later.
 *
 * <p>Doing this in code rather than with container-element constraints on the
 * DTO is deliberate: the rules are cross-field (a day needs exactly two times,
 * in order), the day names need normalizing before they are checked, and the
 * resulting messages say which day is wrong.
 */
@Component
@RequiredArgsConstructor
public class OpeningHoursCodec {

    /** Lowercase three-letter day keys, in week order. */
    private static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final Set<String> VALID_DAYS = Set.copyOf(DAYS);

    /** 24-hour {@code HH:mm}. {@code 24:00} is rejected; midnight is {@code 00:00}. */
    private static final Pattern TIME = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");

    private final ObjectMapper objectMapper;

    /**
     * @param field the request field to blame in an error
     * @return the JSON to store, or null for "no hours recorded" — which is not
     *         the same as {@code always_open}, and the two are shown differently
     */
    public String toJson(Map<String, List<String>> hours, String field) {
        if (hours == null || hours.isEmpty()) {
            return null;
        }

        // Rebuilt in week order rather than in whatever order the request arrived,
        // so two identical schedules serialize to identical JSON. That matters the
        // moment anything compares or caches these values.
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        Map<String, List<String>> byDay = new LinkedHashMap<>();

        hours.forEach((day, times) -> {
            String key = day == null ? "" : day.trim().toLowerCase(Locale.ROOT);
            if (!VALID_DAYS.contains(key)) {
                throw new ValidationException(field,
                        "has an unknown day '" + day + "'; expected one of " + String.join(", ", DAYS));
            }
            byDay.put(key, requireOpenAndClose(key, times, field));
        });

        DAYS.stream().filter(byDay::containsKey).forEach(day -> normalized.put(day, byDay.get(day)));

        return objectMapper.writeValueAsString(normalized);
    }

    private List<String> requireOpenAndClose(String day, List<String> times, String field) {
        if (times == null || times.size() != 2) {
            throw new ValidationException(field,
                    "needs exactly an opening and a closing time for " + day);
        }
        for (String time : times) {
            if (time == null || !TIME.matcher(time).matches()) {
                throw new ValidationException(field,
                        "has an invalid time '" + time + "' for " + day + "; expected HH:mm");
            }
        }
        // Equal times would mean a zero-length day, which is a typo for "closed" —
        // and "closed" is expressed by leaving the day out entirely. Times that run
        // backwards are allowed on purpose: a night market really does open at
        // 20:00 and close at 02:00.
        if (times.get(0).equals(times.get(1))) {
            throw new ValidationException(field,
                    "opens and closes at the same time on " + day + "; omit the day instead");
        }
        return List.copyOf(times);
    }
}
