package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
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
@RequestMapping("/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final InviteService inviteService;

    public AuthController(AuthService authService, InviteService inviteService) {
        this.authService = authService;
        this.inviteService = inviteService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/register/independent-master")
    public ResponseEntity<ApiResponse<AuthResponse>> registerIndependentMaster(
            @Valid @RequestBody RegisterIndependentMasterRequest request
    ) {
        AuthResponse response = authService.registerIndependentMaster(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
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
        if (authentication instanceof UsernamePasswordAuthenticationToken token) {
            return (UUID) token.getDetails();
        }
        throw new com.beautica.common.exception.ForbiddenException("Not authenticated");
    }
}
