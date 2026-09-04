package com.explorelk.auth.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Revokes a token family in its own transaction.
 *
 * <p><b>Why this exists.</b> Reuse detection has to do two things at once: revoke the
 * whole family, and reject the request. But rejecting means throwing, and a throw out
 * of a {@code @Transactional} method rolls the transaction back — taking the revocation
 * with it. The result is the worst kind of bug: the caller gets a convincing
 * {@code 401 TOKEN_REUSED} while every token in the family stays alive, so the attacker
 * simply carries on with the next one.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this one on its
 * own, so the revocation survives the exception that follows it.
 *
 * <p>It lives in a separate bean because Spring's transaction support works through a
 * proxy: calling a {@code @Transactional} method on {@code this} bypasses the proxy
 * entirely and the new propagation would be silently ignored.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenFamilyRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Commits immediately, independently of the caller.
     *
     * @return how many live tokens were killed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamilyNow(UUID familyId) {
        int revoked = refreshTokenRepository.revokeFamily(familyId, Instant.now());
        log.warn("Revoked {} refresh token(s) in family {}", revoked, familyId);
        return revoked;
    }
}
