package com.explorelk.auth.ratelimit;

import com.explorelk.auth.common.LogSafe;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Locks an account after repeated failures, and unlocks it when it succeeds.
 *
 * <p><strong>The counter lives in Postgres, not Redis</strong> — a deliberate
 * departure from §8 of the design, which lists {@code lock:login:{email}} among
 * the Redis keys. Three reasons:
 *
 * <ul>
 *   <li>The columns already exist. {@code users.failed_login_attempts} and
 *       {@code users.locked_until} were created in {@code V1__init.sql} and
 *       {@link User#isLocked()} is already checked on every login and refresh.
 *       Keeping the count somewhere else would leave two answers to the same
 *       question, with the schema's answer permanently stale.</li>
 *   <li>The rate limiter fails open by design, and it must — see
 *       {@link RateLimitService}. If the lockout also lived in Redis, one
 *       unreachable cache would remove <em>both</em> defences at once and leave
 *       the password endpoint completely unprotected.</li>
 *   <li>A lockout is a security decision about an account, not a throwaway
 *       counter. Flushing Redis should not silently unlock every account an
 *       attacker was working on.</li>
 * </ul>
 *
 * <p>What stays in Redis is the per-IP request limit, which genuinely is a
 * throwaway counter and genuinely should evaporate when the cache does.
 *
 * <p>Failures are counted only for addresses that <em>have</em> an account.
 * Counting unknown ones would need a row to write to, and inventing one is how a
 * login endpoint becomes a way to fill the users table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final RateLimitProperties properties;

    /**
     * Records one failed attempt and locks the account if it has had enough.
     *
     * <p><strong>{@link Propagation#REQUIRES_NEW} is load-bearing.</strong> The
     * caller is about to throw {@code INVALID_CREDENTIALS}, which rolls its
     * transaction back — and would roll this increment back with it, so the
     * counter would sit at zero forever and the lock would never trigger. A
     * separate transaction commits the count before the failure is reported.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(User user) {
        if (!properties.enabled()) {
            return;
        }

        User fresh = userRepository.findById(user.getId()).orElse(null);
        if (fresh == null) {
            return;
        }

        int attempts = fresh.getFailedLoginAttempts() + 1;
        fresh.setFailedLoginAttempts(attempts);

        if (attempts >= properties.maxFailedLogins()) {
            fresh.setLockedUntil(Instant.now().plus(properties.lockDuration()));
            // Reset the counter with the lock. Otherwise the account is locked
            // again by the very first failure after it expires, which is a
            // permanent lockout dressed up as a temporary one.
            fresh.setFailedLoginAttempts(0);

            log.warn("Account locked after {} failed attempts: {} (until {})",
                    attempts, LogSafe.email(fresh.getEmail()), fresh.getLockedUntil());
        }

        userRepository.save(fresh);
    }

    /**
     * A successful login clears the history.
     *
     * <p>Counting <em>consecutive</em> failures rather than lifetime ones is the
     * point: someone who mistypes their password twice a week for a year is not
     * an attacker, and locking them out on the tenth occasion would be absurd.
     */
    @Transactional
    public void recordSuccess(User user) {
        if (user.getFailedLoginAttempts() == 0 && user.getLockedUntil() == null) {
            return;
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }
}
