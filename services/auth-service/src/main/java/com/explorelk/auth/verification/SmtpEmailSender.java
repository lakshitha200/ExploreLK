package com.explorelk.auth.verification;

import com.explorelk.auth.common.LogSafe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * SMTP implementation. Locally this talks to MailHog on port 1025.
 *
 * <p><b>Async on purpose.</b> Registration must not wait on a mail server, and must
 * not fail because one is down — the account has already been committed by the time
 * this runs. A failure here is logged and the user can ask for another link.
 *
 * <p>Because it is async, this runs <em>after</em> the caller's transaction commits,
 * so it can never send a link for a row that later rolled back.
 */
@Component
@Slf4j
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final String appBaseUrl;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @org.springframework.beans.factory.annotation.Value("${explorelk.mail.from}") String from,
                           @org.springframework.beans.factory.annotation.Value("${explorelk.mail.app-base-url}") String appBaseUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.appBaseUrl = appBaseUrl;
    }

    @Async
    @Override
    public void sendVerificationEmail(String to, String fullName, String rawToken) {
        String link = appBaseUrl + "/verify-email?token=" + rawToken;
        send(to, "Verify your ExploreLK account", """
                Hello %s,

                Welcome to ExploreLK. Confirm your email address to activate your account:

                %s

                This link expires in 24 hours. If you did not create an account, ignore this email.

                — ExploreLK
                """.formatted(fullName, link));
    }

    @Async
    @Override
    public void sendPasswordResetEmail(String to, String fullName, String rawToken) {
        String link = appBaseUrl + "/reset-password?token=" + rawToken;
        send(to, "Reset your ExploreLK password", """
                Hello %s,

                Someone asked to reset the password on this account. If it was you:

                %s

                This link expires in 15 minutes and can be used once.
                If it was not you, no action is needed — your password has not changed.

                — ExploreLK
                """.formatted(fullName, link));
    }

    @Async
    @Override
    public void sendDuplicateRegistrationNotice(String to, String fullName) {
        send(to, "Someone tried to register with your email", """
                Hello %s,

                Someone just tried to create an ExploreLK account using this email address.
                You already have one, so nothing was created and nothing has changed.

                If that was you, simply sign in. If it was not, you can ignore this —
                but consider changing your password if you reuse it elsewhere.

                — ExploreLK
                """.formatted(fullName));
    }

    @Async
    @Override
    public void sendPasswordChangedNotice(String to, String fullName) {
        send(to, "Your ExploreLK password was changed", """
                Hello %s,

                Your password was just changed and every device has been signed out.

                If this was not you, reset your password immediately.

                — ExploreLK
                """.formatted(fullName));
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);

            // Subject and masked address only. The body holds a live token.
            log.info("Sent '{}' to {}", subject, LogSafe.email(to));
        } catch (Exception e) {
            // Never rethrow: the caller's work is already committed, and an SMTP
            // outage must not turn a successful registration into a 500.
            log.error("Failed to send '{}' to {}", subject, LogSafe.email(to), e);
        }
    }
}
