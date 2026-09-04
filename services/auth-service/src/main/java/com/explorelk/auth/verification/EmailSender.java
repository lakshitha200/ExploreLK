package com.explorelk.auth.verification;

/**
 * Sends account emails.
 *
 * <p>An interface rather than a direct {@code JavaMailSender} call because this is
 * temporary. Step 7 sends SMTP straight from this service so the flow can be finished
 * and tested; Step 8 replaces the implementation with one that writes a
 * {@code USER_REGISTERED} row to the outbox and lets the Notification Service do the
 * sending. Callers do not change either way.
 */
public interface EmailSender {

    /** Verification link for a newly registered account. */
    void sendVerificationEmail(String to, String fullName, String rawToken);

    /** Password reset link. */
    void sendPasswordResetEmail(String to, String fullName, String rawToken);

    /**
     * Sent to the existing owner when somebody tries to register their address again.
     *
     * <p>This is what makes enumeration-resistant registration honest: the request
     * gets the same bland 202 either way, and the person who actually owns the
     * address is the one who finds out.
     */
    void sendDuplicateRegistrationNotice(String to, String fullName);

    /** Confirmation that a password actually changed. */
    void sendPasswordChangedNotice(String to, String fullName);
}
