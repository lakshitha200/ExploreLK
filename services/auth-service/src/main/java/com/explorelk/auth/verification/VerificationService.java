package com.explorelk.auth.verification;

import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.common.LogSafe;
import com.explorelk.auth.common.exception.AppException;
import com.explorelk.auth.token.RefreshTokenService;
import com.explorelk.auth.token.TokenHasher;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Email verification and password reset.
 *
 * <p><b>The rule that shapes every method here: never confirm whether an address has
 * an account.</b> {@code forgot-password} and {@code resend-verification} return the
 * same 202 whether or not the user exists, and do the same visible amount of work. An
 * endpoint that answers "no such user" is a free membership oracle — point it at a
 * list of addresses and it tells you which ones to attack.
 *
 * <p>Tokens are random, emailed once, and stored only as SHA-256 hashes. Consuming one
 * stamps {@code usedAt}, so a link works exactly once even if it is still within its
 * expiry window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationService {

    /** Long enough to survive a slow inbox. */
    private static final Duration VERIFICATION_TTL = Duration.ofHours(24);

    /** Short on purpose — this token can take over an account. */
    private static final Duration RESET_TTL = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

    // ── Email verification ───────────────────────────────────────────────────

    /**
     * Issues a verification link. Called after registration and by resend.
     *
     * <p>Returns the raw token so the caller can email it; the caller is the only
     * place it ever exists in plaintext.
     */
    @Transactional
    public String issueVerificationToken(User user) {
        verificationTokens.invalidateOutstanding(user.getId(), Instant.now());

        String raw = TokenHasher.newToken();
        verificationTokens.save(EmailVerificationToken.builder()
                .user(user)
                .tokenHash(TokenHasher.hash(raw))
                .expiresAt(Instant.now().plus(VERIFICATION_TTL))
                .build());

        return raw;
    }

    /** Consumes a verification token and activates the account. */
    @Transactional
    public void verifyEmail(String rawToken) {
        EmailVerificationToken token = verificationTokens.findByTokenHash(TokenHasher.hash(rawToken))
                .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALID, "Unknown verification token"));

        if (token.isUsed()) {
            throw new AppException(ErrorCode.TOKEN_REUSED, "Verification token already used");
        }
        if (token.isExpired()) {
            throw new AppException(ErrorCode.TOKEN_EXPIRED, "Verification token expired");
        }

        User user = token.getUser();

        // Only PENDING_VERIFICATION becomes ACTIVE. A suspended account that happens
        // to verify its address must stay suspended.
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        token.setUsedAt(Instant.now());
        verificationTokens.save(token);

        log.info("Email verified for user {}", user.getId());
    }

    /**
     * Resends a verification link.
     *
     * <p>Silent for unknown addresses and for accounts that are already verified —
     * both would otherwise reveal account state to anyone who asks.
     */
    @Transactional
    public void resendVerification(String rawEmail) {
        String email = normalise(rawEmail);
        Optional<User> found = userRepository.findByEmail(email);

        if (found.isEmpty()) {
            log.info("Resend requested for unknown address {}", LogSafe.email(email));
            return;
        }

        User user = found.get();
        if (user.isEmailVerified()) {
            log.info("Resend requested for already-verified user {}", user.getId());
            return;
        }

        emailSender.sendVerificationEmail(user.getEmail(), user.getFullName(), issueVerificationToken(user));
    }

    // ── Password reset ───────────────────────────────────────────────────────

    /**
     * Starts a reset. Silent when the address is unknown.
     *
     * <p>The caller returns 202 regardless, so an attacker learns nothing about which
     * addresses exist.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalise(rawEmail);
        Optional<User> found = userRepository.findByEmail(email);

        if (found.isEmpty()) {
            log.info("Password reset requested for unknown address {}", LogSafe.email(email));
            return;
        }

        User user = found.get();

        // A disabled account must not be recoverable by its former owner.
        if (user.getStatus() == UserStatus.DISABLED) {
            log.info("Password reset requested for disabled user {}", user.getId());
            return;
        }

        resetTokens.invalidateOutstanding(user.getId(), Instant.now());

        String raw = TokenHasher.newToken();
        resetTokens.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(TokenHasher.hash(raw))
                .expiresAt(Instant.now().plus(RESET_TTL))
                .build());

        emailSender.sendPasswordResetEmail(user.getEmail(), user.getFullName(), raw);
        log.info("Password reset link issued for user {}", user.getId());
    }

    /**
     * Consumes a reset token and sets a new password.
     *
     * <p>Every refresh token is revoked afterwards. If the reset happened because an
     * account was compromised, leaving the attacker's existing sessions alive would
     * make the whole exercise pointless.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = resetTokens.findByTokenHash(TokenHasher.hash(rawToken))
                .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALID, "Unknown reset token"));

        if (token.isUsed()) {
            throw new AppException(ErrorCode.TOKEN_REUSED, "Reset token already used");
        }
        if (token.isExpired()) {
            throw new AppException(ErrorCode.TOKEN_EXPIRED, "Reset token expired");
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        token.setUsedAt(Instant.now());
        resetTokens.save(token);

        refreshTokenService.revokeAllForUser(user.getId());

        emailSender.sendPasswordChangedNotice(user.getEmail(), user.getFullName());
        log.info("Password reset completed for user {}", user.getId());
    }

    /**
     * Changes the password of a signed-in user.
     *
     * <p>The current password is required even though the caller already holds a valid
     * token — it is what stops someone who borrowed an unlocked laptop from locking the
     * real owner out.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "No user " + userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            log.info("Password change refused for user {}: current password wrong", userId);
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Current password incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Signs out every device, including this one — the client must log in again.
        refreshTokenService.revokeAllForUser(userId);

        emailSender.sendPasswordChangedNotice(user.getEmail(), user.getFullName());
        log.info("Password changed for user {}", userId);
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
