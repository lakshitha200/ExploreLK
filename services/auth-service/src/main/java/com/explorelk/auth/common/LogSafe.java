package com.explorelk.auth.common;

/**
 * Masking helpers for anything that reaches a log line.
 *
 * <p>Logs get shipped, indexed and read by people who have no business seeing a
 * user's address. Nothing here is reversible.
 */
public final class LogSafe {

    private LogSafe() {
    }

    /**
     * {@code jayasuriya@explorelk.lk} becomes {@code j*********@explorelk.lk}.
     *
     * <p>The domain is kept because it is useful when debugging and is not personal;
     * the local part is what identifies someone.
     */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "(malformed)";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 1) + domain;
    }
}
