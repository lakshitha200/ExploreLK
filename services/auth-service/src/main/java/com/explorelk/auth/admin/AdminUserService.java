package com.explorelk.auth.admin;

import com.explorelk.auth.admin.dto.AdminUserResponse;
import com.explorelk.auth.admin.dto.CreateAdminRequest;
import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.common.LogSafe;
import com.explorelk.auth.common.PageResponse;
import com.explorelk.auth.common.exception.AppException;
import com.explorelk.auth.outbox.AuthEventType;
import com.explorelk.auth.outbox.OutboxWriter;
import com.explorelk.auth.token.RefreshTokenService;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything an ADMIN or SUPER_ADMIN can do to somebody else's account.
 *
 * <p>Two rules run through all of it:
 *
 * <ol>
 *   <li><strong>A status change takes effect now, not in fifteen minutes.</strong>
 *       Suspending someone revokes every refresh token they hold, so they cannot
 *       mint a new access token. Their current one keeps working until it
 *       expires — the same accepted trade as logout, bounded by the 15-minute
 *       access token lifetime and documented in §4 of the design.</li>
 *   <li><strong>Every change emits an event in the same transaction.</strong>
 *       Booking and Trip need to know a user was suspended; telling them by
 *       writing to the outbox means the notification cannot be lost separately
 *       from the change itself.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final OutboxWriter outboxWriter;

    // ── Reads ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(UserRole role, UserStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                // Clamped. An admin list is a paginated endpoint, not a way to
                // pull the whole users table into one response.
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<User> found = userRepository.search(role, status, pageable);

        return PageResponse.from(found, AdminUserResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(UUID id) {
        return AdminUserResponse.from(require(id));
    }

    // ── Status ───────────────────────────────────────────────────────────────

    /**
     * ACTIVE, SUSPENDED or DISABLED.
     *
     * <p>{@code PENDING_VERIFICATION} is not reachable from here. It describes an
     * account that has never confirmed its address, and an admin moving somebody
     * back into it would be inventing a past that did not happen — the account
     * would then be indistinguishable from one that never verified.
     */
    @Transactional
    public AdminUserResponse changeStatus(UUID id, UserStatus target, UUID actingAdminId) {
        if (target == UserStatus.PENDING_VERIFICATION) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "PENDING_VERIFICATION is not an administrative status");
        }

        User user = require(id);

        // An admin locking themselves out of the platform is a support ticket
        // nobody can resolve without database access.
        if (user.getId().equals(actingAdminId) && target != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "An admin cannot suspend or disable themselves");
        }
        // Only a SUPER_ADMIN may touch an admin, and it reaches this method
        // through the super-admin controller. Guarded here as well so a new
        // caller cannot bypass it.
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new AppException(ErrorCode.FORBIDDEN, "A SUPER_ADMIN account cannot be changed here");
        }

        if (user.getStatus() == target) {
            // Idempotent: a retried request must not be punished, and must not
            // emit a second event for a change that did not happen.
            return AdminUserResponse.from(user);
        }

        UserStatus previous = user.getStatus();
        user.setStatus(target);

        if (target == UserStatus.SUSPENDED || target == UserStatus.DISABLED) {
            // The session ends with the status change. Without this they keep
            // refreshing for thirty days and the suspension means nothing.
            int revoked = refreshTokenService.revokeAllForUser(user.getId());
            log.info("Revoked {} refresh tokens for user {}", revoked, user.getId());

            outboxWriter.write(
                    target == UserStatus.SUSPENDED ? AuthEventType.USER_SUSPENDED : AuthEventType.USER_DISABLED,
                    user,
                    Map.of("previousStatus", previous.name()));
        }

        userRepository.save(user);
        log.info("User {} moved {} -> {} by admin {}", user.getId(), previous, target, actingAdminId);

        return AdminUserResponse.from(user);
    }

    // ── Provider approval ────────────────────────────────────────────────────

    /**
     * Approves or rejects a provider.
     *
     * <p>Only the approval emits an event. A rejection changes nothing any other
     * service was relying on — the provider could not publish before and still
     * cannot — so announcing it would be noise on a topic other services have to
     * read every message of.
     */
    @Transactional
    public AdminUserResponse setProviderApproval(UUID id, boolean approved, UUID actingAdminId) {
        User user = require(id);

        if (user.getRole() != UserRole.PROVIDER) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "User " + id + " is a " + user.getRole() + ", not a PROVIDER");
        }
        if (user.isProviderApproved() == approved) {
            return AdminUserResponse.from(user);
        }

        user.setProviderApproved(approved);
        userRepository.save(user);

        if (approved) {
            outboxWriter.write(AuthEventType.PROVIDER_APPROVED, user);
        }

        log.info("Provider {} approval set to {} by admin {}", user.getId(), approved, actingAdminId);
        return AdminUserResponse.from(user);
    }

    // ── SUPER_ADMIN only ─────────────────────────────────────────────────────

    /**
     * Creates an ADMIN account.
     *
     * <p>There is no email verification step: a SUPER_ADMIN typed the address in
     * deliberately, and an admin who cannot log in until they find an email is a
     * worse failure than one who can. The account is {@code ACTIVE} and verified
     * immediately, and {@code mustChangePassword} is set so the password the
     * super-admin chose does not stay in use.
     */
    @Transactional
    public AdminUserResponse createAdmin(CreateAdminRequest request, UUID actingSuperAdminId) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            // Unlike public registration, this one says so plainly. The caller is
            // already the most privileged account in the system, so there is no
            // enumeration to prevent — and silence here would make a super-admin
            // think an admin was created when it was not.
            throw new AppException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Admin email already in use: " + email);
        }

        User admin = userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(java.time.Instant.now())
                .providerApproved(false)
                .mustChangePassword(true)
                .failedLoginAttempts(0)
                .build());

        outboxWriter.write(AuthEventType.ADMIN_CREATED, admin,
                Map.of("createdBy", String.valueOf(actingSuperAdminId)));

        log.info("Admin {} created by super-admin {} ({})",
                admin.getId(), actingSuperAdminId, LogSafe.email(email));

        return AdminUserResponse.from(admin);
    }

    /** Enable or disable an ADMIN. Reachable only from the super-admin controller. */
    @Transactional
    public AdminUserResponse changeAdminStatus(UUID id, UserStatus target, UUID actingSuperAdminId) {
        User admin = require(id);

        if (admin.getRole() != UserRole.ADMIN) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "User " + id + " is a " + admin.getRole() + ", not an ADMIN");
        }
        if (target == UserStatus.PENDING_VERIFICATION) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "PENDING_VERIFICATION is not an administrative status");
        }

        if (admin.getStatus() == target) {
            return AdminUserResponse.from(admin);
        }

        UserStatus previous = admin.getStatus();
        admin.setStatus(target);

        if (target != UserStatus.ACTIVE) {
            refreshTokenService.revokeAllForUser(admin.getId());
            outboxWriter.write(
                    target == UserStatus.SUSPENDED ? AuthEventType.USER_SUSPENDED : AuthEventType.USER_DISABLED,
                    admin,
                    Map.of("previousStatus", previous.name()));
        }

        userRepository.save(admin);
        log.info("Admin {} moved {} -> {} by super-admin {}", admin.getId(), previous, target, actingSuperAdminId);

        return AdminUserResponse.from(admin);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private User require(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "No user " + id));
    }
}
