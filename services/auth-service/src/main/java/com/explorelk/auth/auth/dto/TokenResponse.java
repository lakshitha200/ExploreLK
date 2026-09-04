package com.explorelk.auth.auth.dto;

import com.explorelk.auth.user.UserRole;

import java.time.Instant;
import java.util.UUID;

/**
 * What login and refresh return.
 *
 * <p>{@code refreshToken} appears in plaintext here and nowhere else — the database
 * holds only its hash. A client that loses it must log in again.
 *
 * @param tokenType   always {@code Bearer}; sent so clients do not hardcode it
 * @param expiresIn   access token lifetime in seconds, so a client can refresh ahead
 *                    of expiry rather than waiting for a 401
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        UUID userId,
        UserRole role
) {

    public static TokenResponse of(String accessToken,
                                   Instant accessExpiry,
                                   String refreshToken,
                                   Instant refreshExpiry,
                                   UUID userId,
                                   UserRole role) {
        long expiresIn = Math.max(0, Instant.now().until(accessExpiry, java.time.temporal.ChronoUnit.SECONDS));
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn,
                accessExpiry, refreshExpiry, userId, role);
    }
}
