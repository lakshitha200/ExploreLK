package com.explorelk.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup is always by hash — the raw token is never stored. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Kills an entire rotation chain.
     *
     * <p>Run when a revoked token is presented again: that means the token was
     * captured, so every descendant is suspect. Revoking one by one would leave the
     * attacker holding whichever link we had not reached yet.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :now
             WHERE rt.familyId = :familyId
               AND rt.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Kills every session a user holds.
     *
     * <p>Used on password change, password reset, suspension and disabling — the
     * cases where "log them out everywhere" is the whole point of the action.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :now
             WHERE rt.user.id = :userId
               AND rt.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
