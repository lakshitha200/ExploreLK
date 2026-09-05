package com.explorelk.auth.admin.dto;

import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A user as an administrator sees them.
 *
 * <p>Wider than {@code UserProfileResponse} — it carries status, approval and
 * the lockout state, which an admin needs and a user does not — but still built
 * field by field from the entity. {@code passwordHash} exists on {@link User}
 * and must never be one HTTP response away from a JSON serializer that decided
 * to include every getter.
 */
public record AdminUserResponse(
        UUID id,
        String email,
        String fullName,
        String phone,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        Instant emailVerifiedAt,
        boolean providerApproved,
        boolean mustChangePassword,
        boolean locked,
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                user.isProviderApproved(),
                user.isMustChangePassword(),
                user.isLocked(),
                user.getLockedUntil(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
