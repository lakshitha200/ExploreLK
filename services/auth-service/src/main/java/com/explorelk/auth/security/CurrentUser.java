package com.explorelk.auth.security;

import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.common.exception.AppException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

/**
 * Reads identity out of the verified JWT.
 *
 * <p>By the time a {@link Jwt} reaches a controller, Spring Security has already
 * checked the signature, the expiry and the issuer, and the denylist validator has
 * had its say. So these values are trustworthy in a way a request body never is —
 * which is exactly why the user id for "update my profile" comes from here and not
 * from the payload.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The {@code sub} claim: the authenticated user's id. */
    public static UUID id(Jwt jwt) {
        if (jwt == null) {
            throw new AppException(ErrorCode.TOKEN_INVALID, "No authenticated principal");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            // Signed by us but the subject is not a UUID — a bug on the issuing side.
            throw new AppException(ErrorCode.TOKEN_INVALID, "Token subject is not a user id");
        }
    }

    /** The {@code jti} claim, needed to denylist this token at logout. */
    public static String jti(Jwt jwt) {
        return jwt == null ? null : jwt.getId();
    }

    /** The {@code exp} claim, which sets how long the denylist entry must live. */
    public static Instant expiresAt(Jwt jwt) {
        return jwt == null ? null : jwt.getExpiresAt();
    }
}
