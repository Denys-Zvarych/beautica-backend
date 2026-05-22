package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final InviteService inviteService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService,
                          InviteService inviteService,
                          PasswordResetService passwordResetService) {
        this.authService = authService;
        this.inviteService = inviteService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegistrationResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        RegistrationResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/register/independent-master")
    public ResponseEntity<ApiResponse<RegistrationResponse>> registerIndependentMaster(
            @Valid @RequestBody RegisterIndependentMasterRequest request
    ) {
        RegistrationResponse response = authService.registerIndependentMaster(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshRequest request
    ) {
        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request) {
        AuthResponse response = authService.verifyEmail(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<RegistrationResponse>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.resendVerification(request)));
    }

    /**
     * Initiates a password reset for the given email address.
     *
     * <p>Always returns a generic 200 regardless of whether the email is known, verified, or
     * active — enumeration protection is the primary goal. The only observable difference
     * between a real user and a phantom address is whether a reset email arrives in the inbox.
     *
     * <p>Rate-limited per IP via the {@code forgotPasswordBuckets} bucket (3 requests/hour
     * by default). A 429 with {@code Retry-After: 3600} is returned when exhausted.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request);
        return ResponseEntity.ok(ApiResponse.ok(null,
                "If an account exists for that email, a reset link has been sent."));
    }

    /**
     * Completes a password reset using the raw token from the emailed link.
     *
     * <p>On success the user's password is updated and all existing sessions are terminated.
     * No auth tokens are returned — the client must route to the login screen.
     *
     * <p>Invalid, used, and expired tokens all produce the same generic 400 (no oracle).
     * Bean-validation failures (blank token, short password) return the standard 400
     * validation envelope from {@code GlobalExceptionHandler}.
     *
     * <p>Rate-limited via the shared {@code forgotPasswordBuckets} bucket. 429 with
     * {@code Retry-After: 3600} when exhausted.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(null, "Password has been reset. Please sign in."));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        authService.logout(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invite")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ResponseEntity<ApiResponse<InviteResponse>> sendInvite(
            @Valid @RequestBody InviteRequest request,
            Authentication authentication
    ) {
        UUID callerId = extractUserId(authentication);
        InviteResponse response = inviteService.sendInvite(request, callerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/invite/validate")
    public ResponseEntity<ApiResponse<InvitePreviewResponse>> validateInvite(
            @RequestParam @NotBlank @Size(max = 200) String token
    ) {
        InvitePreviewResponse response = inviteService.previewInvite(token);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/invite/accept")
    public ResponseEntity<ApiResponse<AuthResponse>> acceptInvite(
            @Valid @RequestBody InviteAcceptRequest request
    ) {
        AuthResponse response = inviteService.acceptInvite(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        throw new com.beautica.common.exception.ForbiddenException("Not authenticated");
    }
}
