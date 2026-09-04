package com.explorelk.auth.verification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * Invalidates any outstanding link before a new one is issued.
     *
     * <p>Without this, every "resend" leaves another working link in another inbox,
     * and the oldest one stays valid for its full 24 hours.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE EmailVerificationToken t
               SET t.usedAt = :now
             WHERE t.user.id = :userId
               AND t.usedAt IS NULL
            """)
    int invalidateOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);
}
