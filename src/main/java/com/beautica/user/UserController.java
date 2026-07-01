package com.beautica.user;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMe(Authentication authentication) {
        UUID userId = AuthenticationUtils.userId(authentication);
        UserProfileResponse profile = userService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMe(
            @Valid @RequestBody UpdateProfileRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);
        UserProfileResponse updated = userService.updateProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }
}
