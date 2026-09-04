package com.explorelk.destination.common;

import com.explorelk.destination.common.exception.ValidationException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a display name into the URL segment the public API resolves on:
 * {@code "Nuwara Eliya"} becomes {@code nuwara-eliya}.
 *
 * <p><strong>Slugs are never accepted from a client.</strong> A raw slug is a
 * primary key in disguise — one that appears in URLs, is chosen by whoever calls
 * the API, and can be made to collide, to contain path separators, or to look
 * like a UUID. Generating it here means the shape below is the only shape that
 * can ever exist, and it matches the {@code ck_destinations_slug} CHECK
 * constraint exactly.
 *
 * <p>The output always satisfies {@code ^[a-z0-9]+(-[a-z0-9]+)*$}, or the call
 * fails loudly rather than letting the database reject it later with a message
 * no admin can act on.
 */
public final class SlugGenerator {

    /** Matches the VARCHAR(80) columns the slug is stored in. */
    public static final int MAX_LENGTH = 80;

    /** Combining marks left behind after NFD normalization — the accents themselves. */
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /** Any run of characters a slug may not contain collapses to a single hyphen. */
    private static final Pattern SEPARATORS = Pattern.compile("[^a-z0-9]+");

    private SlugGenerator() {
    }

    /**
     * @param field the request field to blame if {@code text} cannot produce a
     *              slug, so the client gets a field error rather than a 500
     * @throws ValidationException if nothing usable survives normalization —
     *         a name of only punctuation or only non-Latin script
     */
    public static String from(String text, String field) {
        String slug = slugify(text);
        if (slug.isEmpty()) {
            throw new ValidationException(field, "cannot be turned into a URL slug");
        }
        return slug;
    }

    /**
     * The normalization itself. Decompose to NFD so that {@code é} becomes
     * {@code e} plus a combining accent, drop the accents, lowercase, then
     * collapse everything that is not a letter or digit into single hyphens.
     *
     * <p>Truncation happens before the trailing hyphen is trimmed, so cutting a
     * long name mid-word can never leave {@code some-long-name-} behind.
     */
    private static String slugify(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        String ascii = DIACRITICS.matcher(decomposed).replaceAll("");
        String hyphenated = SEPARATORS.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");

        String truncated = hyphenated.length() > MAX_LENGTH
                ? hyphenated.substring(0, MAX_LENGTH)
                : hyphenated;

        return trimHyphens(truncated);
    }

    private static String trimHyphens(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
