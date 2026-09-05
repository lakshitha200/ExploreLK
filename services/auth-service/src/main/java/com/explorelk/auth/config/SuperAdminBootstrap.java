package com.explorelk.auth.config;

import com.explorelk.auth.common.LogSafe;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Creates the first SUPER_ADMIN on an empty database.
 *
 * <p>Someone has to be able to create the first administrator, and every way of
 * doing it is uncomfortable. A public endpoint guarded by a flag is a race
 * against whoever finds it first; a SQL script in the repository is a password
 * in git forever. A runner reading the environment is the least bad: the secret
 * lives wherever the deployment keeps its secrets, and the window in which it
 * matters is one boot of one empty database.
 *
 * <p>Three rules make it safe to leave enabled permanently:
 *
 * <ul>
 *   <li><strong>Idempotent.</strong> It runs only when zero SUPER_ADMINs exist,
 *       so a restart, a second instance or a rolling deploy cannot produce a
 *       second one — and cannot reset the first one's password back to whatever
 *       is still sitting in the environment.</li>
 *   <li><strong>Fails fast outside dev.</strong> A missing password in
 *       production stops the application rather than inventing a default, which
 *       is how services end up in the wild with a known super-admin password.</li>
 *   <li><strong>Forces a password change.</strong> The bootstrap password came
 *       from an environment variable that is visible to anyone who can read the
 *       deployment config, so it is a way in, not a credential to keep.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuperAdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Value("${explorelk.super-admin.email:}")
    private String email;

    @Value("${explorelk.super-admin.password:}")
    private String password;

    @Value("${explorelk.super-admin.full-name:ExploreLK Super Admin}")
    private String fullName;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(UserRole.SUPER_ADMIN)) {
            log.debug("SUPER_ADMIN already exists; bootstrap skipped");
            return;
        }

        if (email.isBlank() || password.isBlank()) {
            if (isDevelopment()) {
                // Nothing is at risk on a developer's machine, and refusing to
                // start would make a fresh clone fail for a reason that has
                // nothing to do with what they are working on.
                log.warn("No SUPER_ADMIN configured. Set SUPER_ADMIN_EMAIL and SUPER_ADMIN_PASSWORD "
                        + "to create one — the admin endpoints are unreachable until you do.");
                return;
            }
            throw new IllegalStateException(
                    "No SUPER_ADMIN exists and SUPER_ADMIN_EMAIL / SUPER_ADMIN_PASSWORD are not set. "
                            + "Refusing to start: the alternative is a platform nobody can administer, "
                            + "or one with a default password.");
        }

        User superAdmin = userRepository.save(User.builder()
                .email(email.trim().toLowerCase(Locale.ROOT))
                .passwordHash(passwordEncoder.encode(password))
                .fullName(fullName.trim())
                .role(UserRole.SUPER_ADMIN)
                // Active immediately. There is no inbox to check on a fresh
                // deployment and no second account that could verify this one.
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .providerApproved(false)
                .mustChangePassword(true)
                .failedLoginAttempts(0)
                .build());

        // The address, never the password — not even at debug level. Logs are
        // shipped, indexed and read by more people than the deployment config.
        log.info("Bootstrapped SUPER_ADMIN {} ({}). It must change its password on first use.",
                superAdmin.getId(), LogSafe.email(superAdmin.getEmail()));
    }

    private boolean isDevelopment() {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.equalsIgnoreCase("dev") || profile.equalsIgnoreCase("test")) {
                return true;
            }
        }
        // No explicit profile means the default, which application.yml sets to dev.
        return environment.getActiveProfiles().length == 0;
    }
}
