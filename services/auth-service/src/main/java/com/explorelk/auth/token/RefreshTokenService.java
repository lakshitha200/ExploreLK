package com.explorelk.auth.token;

import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.common.exception.AppException;
import com.explorelk.auth.config.JwtProperties;
import com.explorelk.auth.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>A refresh token is a random string, not a JWT — it is checked against the
 * database on every use, which is exactly what lets it be revoked.
 *
 * <p><b>Rotation with reuse detection.</b> Every refresh consumes the old token and
 * issues a new one in the same family:
 *
 * <pre>
 *   A --replacedBy--> B --replacedBy--> C      one familyId throughout
 * </pre>
 *
 * If a token that was already consumed shows up again, two parties hold the same
 * token — the only innocent explanation is a client bug, and the guilty one is theft.
 * Either way the entire family is revoked. That is the point of the design: without
 * it, a stolen token can be rotated forever and the theft never surfaces. With it,
 * the next move by <em>either</em> party ends both sessions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenFamilyRevoker familyRevoker;
    private final JwtProperties jwtProperties;

    /**
     * Starts a new family. Called at login — a fresh sign-in is a new chain, unrelated
     * to any session the user already has on another device.
     */
    @Transactional
    public IssuedRefreshToken issueNew(User user, String userAgent, String ip) {
        return persist(user, UUID.randomUUID(), userAgent, ip);
    }

    /**
     * Consumes a refresh token and issues its successor.
     *
     * @throws AppException {@code TOKEN_INVALID} if unknown or expired,
     *                      {@code TOKEN_REUSED} if it had already been consumed
     */
    @Transactional
    public RotationResult rotate(String rawToken, String userAgent, String ip) {
        RefreshToken current = refreshTokenRepository.findByTokenHash(TokenHasher.hash(rawToken))
                .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALID, "Refresh token not recognised"));

        if (current.isRevoked()) {
            // Someone is replaying a consumed token. Burn the chain.
            //
            // This MUST commit in its own transaction: the throw below would otherwise
            // roll it straight back, leaving a convincing 401 and a fully live family.
            UUID familyId = current.getFamilyId();
            int revoked = familyRevoker.revokeFamilyNow(familyId);

            log.warn("Refresh token reuse detected for user {} — {} token(s) revoked in family {}",
                    current.getUser().getId(), revoked, familyId);

            throw new AppException(ErrorCode.TOKEN_REUSED,
                    "Refresh token reuse detected; family " + familyId + " revoked");
        }

        if (current.isExpired()) {
            throw new AppException(ErrorCode.TOKEN_EXPIRED, "Refresh token expired");
        }

        User user = current.getUser();

        // Stay in the same family so a later replay still traces back to this chain.
        IssuedRefreshToken next = persist(user, current.getFamilyId(), userAgent, ip);

        current.setRevokedAt(Instant.now());
        current.setReplacedBy(next.id());
        refreshTokenRepository.save(current);

        return new RotationResult(user, next);
    }

    /** Revokes a single token. Logout — the rest of the user's devices are untouched. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.hash(rawToken))
                .filter(token -> !token.isRevoked())
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                    log.info("Refresh token revoked for user {}", token.getUser().getId());
                });
        // Deliberately silent when the token is unknown: telling a caller whether a
        // token existed is a probing oracle, and logout should be idempotent anyway.
    }

    /** Revokes every session. Password change/reset, suspension, disabling. */
    @Transactional
    public int revokeAllForUser(UUID userId) {
        int count = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.info("Revoked {} refresh token(s) for user {}", count, userId);
        return count;
    }

    private IssuedRefreshToken persist(User user, UUID familyId, String userAgent, String ip) {
        String raw = TokenHasher.newToken();

        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .tokenHash(TokenHasher.hash(raw))
                .familyId(familyId)
                .expiresAt(Instant.now().plus(jwtProperties.refreshTokenTtl()))
                .userAgent(truncate(userAgent, 255))
                .ip(truncate(ip, 45))
                .build();

        RefreshToken saved = refreshTokenRepository.save(entity);
        return new IssuedRefreshToken(saved.getId(), raw, saved.getExpiresAt());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * @param id        row id, used as {@code replacedBy} on the token it supersedes
     * @param rawToken  the only time this value exists in plaintext — return it and forget it
     * @param expiresAt when it stops working
     */
    public record IssuedRefreshToken(UUID id, String rawToken, Instant expiresAt) {
    }

    /** The rotated-in token plus the user it belongs to, so the caller avoids a re-read. */
    public record RotationResult(User user, IssuedRefreshToken refreshToken) {
    }
}
