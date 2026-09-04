package com.explorelk.auth.auth;

import com.explorelk.auth.auth.dto.ChangePasswordRequest;
import com.explorelk.auth.auth.dto.EmailOnlyRequest;
import com.explorelk.auth.auth.dto.LoginRequest;
import com.explorelk.auth.auth.dto.MessageResponse;
import com.explorelk.auth.auth.dto.RefreshRequest;
import com.explorelk.auth.auth.dto.RegisterRequest;
import com.explorelk.auth.auth.dto.ResetPasswordRequest;
import com.explorelk.auth.auth.dto.TokenResponse;
import com.explorelk.auth.auth.dto.VerifyEmailRequest;
import com.explorelk.auth.security.CurrentUser;
import com.explorelk.auth.verification.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints.
 *
 * <p>Several of these answer 202 unconditionally. That is not laziness — see
 * {@link RegistrationService} and {@link VerificationService} for why telling a caller
 * whether an address exists is a vulnerability rather than a courtesy.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final VerificationService verificationService;

    // ── Registration and sign-in ─────────────────────────────────────────────

    /**
     * Registers a TRAVELER or PROVIDER.
     *
     * <p>Always 202, with the same body whether or not the address was taken. No id and
     * no {@code Location} header, because both would answer the question the status
     * code refuses to.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }

    /** Returns an access token and a refresh token. */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest servletRequest) {
        return ResponseEntity.ok(loginService.login(request, userAgent(servletRequest), clientIp(servletRequest)));
    }

    /**
     * Exchanges a refresh token for a new pair, consuming the old one.
     *
     * <p>Presenting a token that was already consumed revokes the entire family — see
     * {@code RefreshTokenService.rotate}.
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                 HttpServletRequest servletRequest) {
        return ResponseEntity.ok(loginService.refresh(
                request.refreshToken(), userAgent(servletRequest), clientIp(servletRequest)));
    }

    /**
     * Ends the current session.
     *
     * <p>Revokes the refresh token and denylists this access token's {@code jti}, so the
     * token already in the client's hands stops working immediately rather than at
     * {@code exp}.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt,
                       @RequestBody(required = false) RefreshRequest request) {
        loginService.logout(
                request == null ? null : request.refreshToken(),
                CurrentUser.jti(jwt),
                CurrentUser.expiresAt(jwt));
    }

    // ── Email verification ───────────────────────────────────────────────────

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        verificationService.verifyEmail(request.token());
        return MessageResponse.of("Email verified. You can now sign in.");
    }

    /** Always 202 — a silent no-op for unknown or already-verified addresses. */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendVerification(@Valid @RequestBody EmailOnlyRequest request) {
        verificationService.resendVerification(request.email());
        return MessageResponse.of("If that address needs verifying, we have sent a new link.");
    }

    // ── Password reset ───────────────────────────────────────────────────────

    /** Always 202, whether or not the address has an account. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forgotPassword(@Valid @RequestBody EmailOnlyRequest request) {
        verificationService.forgotPassword(request.email());
        return MessageResponse.of("If that address has an account, we have sent a reset link.");
    }

    /** Consumes the reset token and signs every device out. */
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        verificationService.resetPassword(request.token(), request.newPassword());
        return MessageResponse.of("Password updated. Sign in with your new password.");
    }

    /** Requires the current password even though the caller is already authenticated. */
    @PostMapping("/change-password")
    public MessageResponse changePassword(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody ChangePasswordRequest request) {
        verificationService.changePassword(
                CurrentUser.id(jwt), request.currentPassword(), request.newPassword());
        return MessageResponse.of("Password updated. Sign in again on your devices.");
    }

    // ── Request metadata ─────────────────────────────────────────────────────

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }

    /**
     * Best-effort client address, for the "your sessions" screen.
     *
     * <p>{@code X-Forwarded-For} is client-controlled and trivially spoofed, so this is
     * for display only. Nothing security-critical may key off it — which is why rate
     * limiting in Step 9 needs a trusted-proxy configuration rather than this value.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
