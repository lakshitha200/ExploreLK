package com.explorelk.auth.auth;

import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.verification.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends registration email once the database work has actually committed.
 *
 * <p>{@code AFTER_COMMIT} is the point. Sending inside the transaction risks emailing
 * a verification link for a row that then rolls back — the user clicks a link for an
 * account that never existed. Waiting for the commit makes that impossible.
 *
 * <p>This is the same problem the Transactional Outbox solves in Step 8, at a smaller
 * scale. The difference is what happens when the process dies between commit and send:
 * here the email is simply lost and the user must ask for another link; with the outbox
 * the event is already durable and goes out on restart. That is why Step 8 exists.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RegistrationEmailListener {

    private final UserRepository userRepository;
    private final EmailSender emailSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onUserRegistered(RegistrationService.UserRegistered event) {
        userRepository.findById(event.userId()).ifPresent(user ->
                emailSender.sendVerificationEmail(
                        user.getEmail(), user.getFullName(), event.rawVerificationToken()));
    }

    /**
     * Tells the real owner that somebody tried to sign up as them.
     *
     * <p>Without this the enumeration-resistant 202 would simply swallow the attempt.
     * The person who can act on it is the account holder, so they are who hears about it.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onDuplicateRegistration(RegistrationService.DuplicateRegistrationAttempt event) {
        userRepository.findById(event.existingUserId())
                .filter(User::isEmailVerified) // never mail an address nobody has confirmed
                .ifPresent(user ->
                        emailSender.sendDuplicateRegistrationNotice(user.getEmail(), user.getFullName()));
    }
}
