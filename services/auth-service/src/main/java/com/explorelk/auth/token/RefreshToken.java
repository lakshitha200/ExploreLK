package com.explorelk.auth.token;

import com.explorelk.auth.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token — an opaque random string, not a JWT.
 *
 * <p>Only the SHA-256 hash is stored, for the same reason passwords are hashed:
 * a database leak must not hand out usable sessions.
 *
 * <p>{@code familyId} ties a rotation chain together:
 *
 * <pre>
 *   A --replacedBy--> B --replacedBy--> C     (all share one familyId)
 * </pre>
 *
 * If an already-revoked token is presented again, the token was stolen — the
 * whole family is revoked, logging out both the attacker and the victim.
 */
@Entity
@Table(name = "refresh_tokens")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex of the raw token. Unique. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Shared by every token in one rotation chain. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Id of the token that replaced this one during rotation. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip", length = 45)
    private String ip;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Behaviour ────────────────────────────────────────────────────────────

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    /** Usable only if it has been neither revoked nor allowed to expire. */
    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }
}
