package com.explorelk.auth.admin;

import com.explorelk.auth.admin.dto.AdminUserResponse;
import com.explorelk.auth.admin.dto.ProviderApprovalRequest;
import com.explorelk.auth.admin.dto.UpdateUserStatusRequest;
import com.explorelk.auth.common.PageResponse;
import com.explorelk.auth.security.CurrentUser;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code /api/v1/admin} — ADMIN and SUPER_ADMIN.
 *
 * <p><strong>Belt and braces on authorization, deliberately.</strong> The filter
 * chain already refuses anything under this path without one of the two roles;
 * {@code @PreAuthorize} says it again on the class. Either alone would work
 * today, and both together mean neither a path typo in {@code SecurityConfig}
 * nor a forgotten annotation on a new method silently opens an admin endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * @param role   optional filter
     * @param status optional filter
     */
    @GetMapping("/users")
    public PageResponse<AdminUserResponse> list(
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return adminUserService.list(role, status, page, size);
    }

    @GetMapping("/users/{id}")
    public AdminUserResponse get(@PathVariable UUID id) {
        return adminUserService.get(id);
    }

    /** ACTIVE, SUSPENDED or DISABLED. Suspending ends the user's sessions immediately. */
    @PatchMapping("/users/{id}/status")
    public AdminUserResponse changeStatus(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateUserStatusRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {

        // The acting admin's own id, taken from the token rather than the body:
        // a caller must not be able to name somebody else as the one who did it.
        return adminUserService.changeStatus(id, request.status(), CurrentUser.id(jwt));
    }

    @PatchMapping("/providers/{id}/approval")
    public AdminUserResponse setProviderApproval(@PathVariable UUID id,
                                                 @Valid @RequestBody ProviderApprovalRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {

        return adminUserService.setProviderApproval(id, request.approved(), CurrentUser.id(jwt));
    }
}
