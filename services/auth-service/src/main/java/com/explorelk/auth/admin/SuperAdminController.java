package com.explorelk.auth.admin;

import com.explorelk.auth.admin.dto.AdminUserResponse;
import com.explorelk.auth.admin.dto.CreateAdminRequest;
import com.explorelk.auth.admin.dto.UpdateUserStatusRequest;
import com.explorelk.auth.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code /api/v1/super-admin} — the one role an ADMIN cannot reach.
 *
 * <p>Creating administrators is separated from the rest of the admin surface for
 * one reason: an ADMIN who can create ADMINs is an ADMIN who can quietly grant
 * themselves a second account nobody is watching. Privilege escalation stops
 * being possible only when the ability to grant privilege sits above the
 * privilege being granted.
 *
 * <p>There is exactly one SUPER_ADMIN, and it comes from
 * {@link com.explorelk.auth.config.SuperAdminBootstrap} rather than from any
 * endpoint here — including this one.
 */
@RestController
@RequestMapping("/api/v1/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminController {

    private final AdminUserService adminUserService;

    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserResponse createAdmin(@Valid @RequestBody CreateAdminRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {

        return adminUserService.createAdmin(request, CurrentUser.id(jwt));
    }

    /** Enable or disable an administrator. Disabling ends their sessions immediately. */
    @PatchMapping("/admins/{id}/status")
    public AdminUserResponse changeAdminStatus(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateUserStatusRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {

        return adminUserService.changeAdminStatus(id, request.status(), CurrentUser.id(jwt));
    }
}
