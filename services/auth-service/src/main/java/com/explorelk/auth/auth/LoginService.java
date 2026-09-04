package com.explorelk.auth.auth;

import com.explorelk.auth.auth.dto.LoginRequest;
import com.explorelk.auth.auth.dto.TokenResponse;
import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.common.LogSafe;
import com.explorelk.auth.common.exception.AppException;
import com.explorelk.auth.token.JwtService;
import com.explorelk.auth.token.RefreshTokenService;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRepository;
import com.explorelk.auth.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Login, refresh and logout.
 *
 * <p>Two rules shape the whole class:
 *
 * <ol>
 *   <li><b>A failed login says one thing.</b> Unknown address and wrong password both
 *       return {@code INVALID_CREDENTIALS}, and both take the same time — see
 *       {@link #verifyPassword}.</li>
 *   <li><b>Account state is re-checked on refresh, not only at login.</b> Otherwise
 *       suspending someone leaves them a working session for the refresh token's full
 *       lifetime, which defeats the point of suspending them.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginService {

    /**
     * A real BCrypt hash of a value nobody knows, used to burn the same CPU time when
     * the email does not exist. Without it, "no such user" returns in ~1ms and a real
     * password check takes ~250ms — and that difference alone enumerates the user table.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.4wpQeVWTHNVj1FLUPFEuFOxAiVLQU/y";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public TokenResponse login(LoginRequest request, String userAgent, String ip) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        Optional<User> found = userRepository.findByEmail(email);

        // Hash before deciding anything, so both branches cost the same.
        boolean passwordMatches = verifyPassword(request.password(), found.orElse(null));

        if (found.isEmpty() || !passwordMatches) {
            log.info("Failed login for {}", LogSafe.email(email));
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Bad credentials for " + LogSafe.email(email));
        }

        User user = found.get();
        assertMayAuthenticate(user);

        return issueTokens(user, userAgent, ip);
    }

    /**
     * Rotates a refresh token and mints a matching access token.
     *
     * <p>Reuse detection and family revocation live in
     * {@link RefreshTokenService#rotate}; this adds the account-state re-check.
     */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken, String userAgent, String ip) {
        var rotation = refreshTokenService.rotate(rawRefreshToken, userAgent, ip);

        User user = rotation.user();
        assertMayAuthenticate(user);

        JwtService.AccessToken access = jwtService.issueAccessToken(user);

        return TokenResponse.of(
                access.value(), access.expiresAt(),
                rotation.refreshToken().rawToken(), rotation.refreshToken().expiresAt(),
                user.getId(), user.getRole());
    }

    /**
     * Ends one session.
     *
     * <p>Both halves matter. Revoking the refresh token stops new access tokens being
     * minted; denylisting the access token's {@code jti} stops the one already in the
     * client's hands from working for the rest of its 15 minutes. Doing only the first
     * is the common mistake — the user stays logged in until the access token expires.
     */
    @Transactional
    public void logout(String rawRefreshToken, String accessTokenJti, java.time.Instant accessTokenExpiry) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revoke(rawRefreshToken);
        }
        if (accessTokenJti != null) {
            jwtService.denylist(accessTokenJti, accessTokenExpiry);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Constant-ish time regardless of whether the user exists. */
    private boolean verifyPassword(String rawPassword, User user) {
        String hash = (user != null) ? user.getPasswordHash() : DUMMY_HASH;
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        // A match against the dummy hash is meaningless — nobody knows its input.
        return user != null && matches;
    }

    /**
     * Applies the account lifecycle.
     *
     * <p>Called from login <em>and</em> refresh. Order matters: a suspended account
     * should hear "suspended", not "verify your email".
     */
    private void assertMayAuthenticate(User user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new AppException(ErrorCode.ACCOUNT_SUSPENDED, "Suspended user " + user.getId());
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Disabled user " + user.getId());
        }
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION || !user.isEmailVerified()) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED, "Unverified user " + user.getId());
        }
        if (user.isLocked()) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED, "Locked user " + user.getId());
        }
    }

    private TokenResponse issueTokens(User user, String userAgent, String ip) {
        JwtService.AccessToken access = jwtService.issueAccessToken(user);
        var refresh = refreshTokenService.issueNew(user, userAgent, ip);

        log.info("Login succeeded for user {} role {}", user.getId(), user.getRole());

        return TokenResponse.of(
                access.value(), access.expiresAt(),
                refresh.rawToken(), refresh.expiresAt(),
                user.getId(), user.getRole());
    }
}
