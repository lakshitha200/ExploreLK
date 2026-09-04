package com.explorelk.auth.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Locale;
import java.util.Set;

/**
 * Minimum length 10, at least one letter and one digit, and not an obvious password.
 *
 * <p>The upper bound of 72 is not arbitrary: BCrypt silently ignores everything past
 * 72 bytes, so a longer password would give a false sense of strength and two
 * different long passwords could hash identically. Rejecting is better than truncating.
 */
public class PasswordPolicyValidator implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_LENGTH = 10;
    private static final int MAX_LENGTH = 72;

    /**
     * A deliberately tiny sample. Swap for a real list (the SecLists top-1000, or a
     * Have I Been Pwned range query) before this service goes anywhere near production —
     * this only catches the laziest choices.
     */
    private static final Set<String> COMMON = Set.of(
            "password1", "password12", "password123", "password1234",
            "qwerty1234", "qwertyuiop1", "1234567890a", "abcd123456",
            "letmein123", "welcome123", "admin12345", "iloveyou123",
            "explorelk1", "explorelk123", "srilanka123", "changeme123"
    );

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        // A null or blank password is @NotBlank's job to report, not ours —
        // otherwise an empty field produces two errors saying different things.
        if (password == null || password.isBlank()) {
            return true;
        }

        if (password.length() < MIN_LENGTH) {
            return fail(context, "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            return fail(context, "Password must be at most " + MAX_LENGTH + " characters");
        }

        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return fail(context, "Password must contain at least one letter and one digit");
        }

        if (COMMON.contains(password.toLowerCase(Locale.ROOT))) {
            return fail(context, "Password is too common. Choose something less predictable");
        }

        return true;
    }

    /** Replaces the generic annotation message with the specific rule that failed. */
    private boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
