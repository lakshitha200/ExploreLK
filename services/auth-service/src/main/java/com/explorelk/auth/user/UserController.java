package com.explorelk.auth.user;

import com.explorelk.auth.security.CurrentUser;
import com.explorelk.auth.user.dto.UpdateProfileRequest;
import com.explorelk.auth.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated user's own account.
 *
 * <p>Both endpoints act on whoever the token says you are — there is no id in the
 * path, so there is no object to tamper with.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(userService.getProfile(CurrentUser.id(jwt)));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateMe(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(CurrentUser.id(jwt), request));
    }
}
