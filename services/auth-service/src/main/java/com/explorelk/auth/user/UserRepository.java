package com.explorelk.auth.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * The admin list, with both filters optional.
     *
     * <p>Written as one query with {@code :param IS NULL OR} rather than four
     * derived methods and a chain of ifs choosing between them. The generated
     * plan is the same, and the alternative grows by two methods every time a
     * filter is added.
     *
     * <p>Covered by {@code ix_users_role_status} when both filters are supplied,
     * which is the case an admin screen actually uses.
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (:status IS NULL OR u.status = :status)
            """)
    Page<User> search(@Param("role") UserRole role,
                      @Param("status") UserStatus status,
                      Pageable pageable);
}
