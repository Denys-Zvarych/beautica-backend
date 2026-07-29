package com.beautica.user;

import com.beautica.auth.PasswordResetService;
import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;

    public UserController(UserService userService, PasswordResetService passwordResetService) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMe(Authentication authentication) {
        UUID userId = AuthenticationUtils.userId(authentication);
        UserProfileResponse profile = userService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    /**
     * The caller's own aggregate client rating (Phase 27.6). Primarily called by CLIENT
     * principals — the "Мій рейтинг" read surface from the two-sided ratings pivot — but is not
     * role-restricted: any authenticated user has been the client of at least a hypothetical
     * booking, and there is nothing sensitive in a {@code null}/zero rating for a non-CLIENT role.
     */
    @GetMapping("/me/rating")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserRatingResponse>> getMyRating(Authentication authentication) {
        UUID userId = AuthenticationUtils.userId(authentication);
        return ResponseEntity.ok(ApiResponse.ok(userService.getMyRating(userId)));
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

    /**
     * Requests a 6-digit password-reset OTP for the AUTHENTICATED caller (the
     * "change password from settings" entry point, Phase A3). No request body — the target
     * account is the caller's own JWT-derived {@code userId}, never a client-supplied email,
     * so no anti-enumeration is needed here (unlike {@code POST /auth/forgot-password}).
     *
     * <p>Deliberately NOT added to {@code SecurityConfig}'s {@code permitAll} list — it falls
     * through to {@code anyRequest().authenticated()}, so an unauthenticated caller gets 401
     * before this method is ever invoked.
     *
     * <p>Delegates to the same {@link PasswordResetService#requestReset} OTP-mint core via
     * {@link PasswordResetService#requestResetForUserId} — zero duplication between the two
     * entry points. Rate-limited per IP via the {@code changePasswordOtpBuckets} bucket
     * (3 requests/hour by default), mirroring {@code forgotPasswordBuckets}.
     */
    @PostMapping("/me/change-password/request-otp")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> requestChangePasswordOtp(Authentication authentication) {
        UUID userId = AuthenticationUtils.userId(authentication);
        passwordResetService.requestResetForUserId(userId);
        return ResponseEntity.ok(ApiResponse.ok(null, "A reset code has been sent to your email."));
    }
}
