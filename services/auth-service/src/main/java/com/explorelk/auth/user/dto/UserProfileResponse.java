package com.explorelk.auth.user.dto;

import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The authenticated user's own profile.
 *
 * <p>Hand-mapped rather than serialising the entity, so {@code passwordHash} cannot
 * escape through a forgotten annotation.
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String fullName,
        String phone,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        boolean providerApproved,
        Instant createdAt
) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isProviderApproved(),
                user.getCreatedAt());
    }
}
