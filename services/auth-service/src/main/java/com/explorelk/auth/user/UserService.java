package com.explorelk.auth.user;

import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.common.exception.AppException;
import com.explorelk.auth.user.dto.UpdateProfileRequest;
import com.explorelk.auth.user.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        return UserProfileResponse.from(requireUser(userId));
    }

    /**
     * Applies a partial update. Null fields are left as they are.
     *
     * <p>The id comes from the verified JWT subject, never from the request body, so a
     * user cannot edit somebody else's profile by guessing an id.
     */
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = requireUser(userId);

        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone().isBlank() ? null : request.phone().trim());
        }

        log.info("Profile updated for user {}", userId);
        return UserProfileResponse.from(userRepository.save(user));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                // Reachable if an account is deleted while a token is still live.
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "No user " + userId));
    }
}
