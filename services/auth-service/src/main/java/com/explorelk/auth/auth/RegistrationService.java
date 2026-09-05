package com.explorelk.auth.auth;

import com.explorelk.auth.auth.dto.MessageResponse;
import com.explorelk.auth.auth.dto.RegisterRequest;
import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.common.LogSafe;
import com.explorelk.auth.common.exception.AppException;
import com.explorelk.auth.outbox.AuthEventType;
import com.explorelk.auth.outbox.OutboxWriter;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserStatus;
import com.explorelk.auth.verification.VerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Public registration.
 *
 * <p><b>This endpoint deliberately tells the caller nothing.</b> Whether the address
 * was free or already taken, the response is the same 202 with the same wording, and
 * the person who owns the address is emailed either way — a verification link, or a
 * notice that somebody tried to sign up as them. A 201/409 split would let anyone
 * feed in a list of addresses and learn which ones have accounts here.
 *
 * <p>That is a real trade against usability: a user who typos their own address gets
 * no immediate "you already have an account", only a quiet inbox. The signup screen
 * should say "check your email" and offer a password-reset link nearby.
 *
 * <p>Step 3 returned 201/409 because there was no mail to fall back on. Step 7 has it,
 * so the enumeration-resistant behaviour from {@code docs/auth-service.md} §9 applies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationService {

    /** Identical for both outcomes — the whole point. */
    private static final String ACKNOWLEDGEMENT =
            "If that email address can be registered, we have sent a verification link to it.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationService verificationService;
    private final ApplicationEventPublisher events;
    private final OutboxWriter outboxWriter;

    @Transactional
    public MessageResponse register(RegisterRequest request) {

        // Only TRAVELER and PROVIDER may be self-assigned. ADMIN comes from a
        // SUPER_ADMIN, SUPER_ADMIN from the startup bootstrap — never a request body.
        // This one does return an error: it reveals nothing about who exists.
        if (!request.role().isPubliclyRegisterable()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Attempt to self-register with role " + request.role());
        }

        String email = request.email().trim().toLowerCase(Locale.ROOT);

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User owner = existing.get();
            log.info("Registration attempt for existing account {}", owner.getId());

            // Hash anyway. Skipping it would make this path measurably faster than a
            // real signup, and the timing difference is itself the answer.
            passwordEncoder.encode(request.password());

            events.publishEvent(new DuplicateRegistrationAttempt(owner.getId()));
            return MessageResponse.of(ACKNOWLEDGEMENT);
        }

        User user = userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .phone(blankToNull(request.phone()))
                .role(request.role())
                // Cannot log in until the address is confirmed.
                .status(UserStatus.PENDING_VERIFICATION)
                // A provider may register freely but may not sell until an admin approves.
                .providerApproved(false)
                .mustChangePassword(false)
                .failedLoginAttempts(0)
                .build());

        String rawToken = verificationService.issueVerificationToken(user);

        // In the same transaction as the INSERT above, which is the entire point:
        // a crash between the two cannot produce a registered user nobody was
        // ever told about. The token travels because the Notification Service
        // cannot build the verification link without it.
        outboxWriter.write(AuthEventType.USER_REGISTERED, user,
                java.util.Map.of("verificationToken", rawToken));

        // A provider signs up like anyone else but cannot sell until an admin
        // approves them, so somebody has to be told there is a queue.
        if (user.getRole() == com.explorelk.auth.user.UserRole.PROVIDER) {
            outboxWriter.write(AuthEventType.PROVIDER_REGISTERED, user);
        }

        // Sent after this transaction commits — see RegistrationEmailListener.
        events.publishEvent(new UserRegistered(user.getId(), rawToken));

        log.info("Registered user id={} role={} ({})",
                user.getId(), user.getRole(), LogSafe.email(email));

        return MessageResponse.of(ACKNOWLEDGEMENT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * A new account was created and needs its verification link.
     *
     * <p>Carries the raw token because this is the last moment it exists — the
     * database holds only its hash.
     */
    public record UserRegistered(java.util.UUID userId, String rawVerificationToken) {
    }

    /** Somebody tried to register an address that already has an account. */
    public record DuplicateRegistrationAttempt(java.util.UUID existingUserId) {
    }
}
