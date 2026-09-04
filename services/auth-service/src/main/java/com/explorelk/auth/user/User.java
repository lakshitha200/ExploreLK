package com.explorelk.auth.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A platform account — traveler, provider, admin or super-admin.
 *
 * <p>Note there is no {@code toString} that could leak {@code passwordHash};
 * Lombok's {@code @Getter}/{@code @Setter} deliberately stop short of it.
 */
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Always stored lowercase. Uniqueness is enforced by the functional index
     * {@code ux_users_email_lower}, not by a plain UNIQUE constraint — Postgres
     * comparisons are case-sensitive.
     */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** BCrypt. Never logged, never serialised, never returned by an API. */
    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "phone", length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 24)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Only meaningful for {@link UserRole#PROVIDER}. Set by an admin. */
    @Column(name = "provider_approved", nullable = false)
    @Builder.Default
    private boolean providerApproved = false;

    /** Forces a password change on next login — used by the SUPER_ADMIN bootstrap. */
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Behaviour ────────────────────────────────────────────────────────────

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /** True while a brute-force lockout is still in effect. */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    /** ACTIVE, verified, and not currently locked out. */
    public boolean canAuthenticate() {
        return status.canAuthenticate() && isEmailVerified() && !isLocked();
    }
}
