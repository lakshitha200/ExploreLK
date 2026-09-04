package com.explorelk.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Emails are normalised to lowercase before they are stored, so every lookup here
 * takes an already-lowercased value. The database still enforces the real rule via
 * the {@code ux_users_email_lower} functional index — Postgres string comparison is
 * case-sensitive, so a plain unique constraint would not be enough.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /** @param email must already be lowercase */
    boolean existsByEmail(String email);

    /** @param email must already be lowercase */
    Optional<User> findByEmail(String email);

    boolean existsByRole(UserRole role);
}
