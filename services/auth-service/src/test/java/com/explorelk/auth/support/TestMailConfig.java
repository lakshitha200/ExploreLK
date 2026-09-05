package com.explorelk.auth.support;

import com.explorelk.auth.verification.EmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Captures the mail the service would have sent, instead of sending it.
 *
 * <p>There is no MailHog in the test suite, and adding one would mean a third
 * container and an HTTP poll to read an inbox. The interesting part is not that
 * SMTP works — it is <em>what</em> was sent and to whom, and above all the token
 * inside the link, which the verification and reset tests need in order to
 * continue the flow.
 *
 * <p>Registered as {@code @Primary} so it replaces {@code SmtpEmailSender}
 * without that class needing to know a test exists.
 */
@TestConfiguration
public class TestMailConfig {

    @Bean
    @Primary
    public CapturingEmailSender capturingEmailSender() {
        return new CapturingEmailSender();
    }

    /** Records every message; tests read the last one and clear between cases. */
    public static class CapturingEmailSender implements EmailSender {

        private final List<SentEmail> sent = new ArrayList<>();

        @Override
        public void sendVerificationEmail(String to, String fullName, String token) {
            sent.add(new SentEmail(Kind.VERIFICATION, to, fullName, token));
        }

        @Override
        public void sendPasswordResetEmail(String to, String fullName, String token) {
            sent.add(new SentEmail(Kind.PASSWORD_RESET, to, fullName, token));
        }

        @Override
        public void sendDuplicateRegistrationNotice(String to, String fullName) {
            sent.add(new SentEmail(Kind.DUPLICATE_REGISTRATION, to, fullName, null));
        }

        @Override
        public void sendPasswordChangedNotice(String to, String fullName) {
            sent.add(new SentEmail(Kind.PASSWORD_CHANGED, to, fullName, null));
        }

        public List<SentEmail> all() {
            return List.copyOf(sent);
        }

        public Optional<SentEmail> lastOfKind(Kind kind) {
            return sent.stream().filter(e -> e.kind() == kind).reduce((first, second) -> second);
        }

        /** The token from the most recent message of that kind — the link's payload. */
        public String lastTokenOfKind(Kind kind) {
            return lastOfKind(kind)
                    .map(SentEmail::token)
                    .orElseThrow(() -> new AssertionError("No " + kind + " email was sent"));
        }

        public void clear() {
            sent.clear();
        }

        public enum Kind {VERIFICATION, PASSWORD_RESET, DUPLICATE_REGISTRATION, PASSWORD_CHANGED}

        public record SentEmail(Kind kind, String to, String fullName, String token) {
        }
    }
}
