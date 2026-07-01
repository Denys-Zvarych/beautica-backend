package com.beautica.master.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.master.dto.IndependentMasterUpdateRequest;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.dto.MasterPublicProfileResponse;
import com.beautica.user.UpdateProfileRequest;
import com.beautica.user.UserProfileResponse;
import com.beautica.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Self-service profile endpoint for {@code INDEPENDENT_MASTER} users.
 *
 * <p>Currently exposes a single write path: {@code PATCH /me} — persists the
 * working address (locality FK + structured street fields) on the authenticated
 * master's {@code User} row via {@link UserService#updateProfile}. The Phase 10.6
 * locality logic (most-specific-node rule, FK validation) lives entirely in
 * {@code UserService}; this controller is a thin security + routing layer only.
 *
 * <p>Called immediately after OTP verification by the mobile client
 * ({@code master_repository.dart → updateLocality()}).
 */
@RestController
@RequestMapping("/api/v1/independent-masters")
@RequiredArgsConstructor
public class IndependentMasterController {

    private final UserService userService;

    /**
     * Persists the authenticated independent master's working address.
     *
     * <p>Delegates to {@link UserService#updateProfile} using a
     * locality-only {@link UpdateProfileRequest} (personal-name and phone
     * fields are {@code null} and are ignored by the service).
     *
     * @param request        locality fields from the mobile registration flow
     * @param authentication injected by Spring Security — carries the user UUID
     * @return the updated user profile wrapped in {@link ApiResponse}
     */
    @PatchMapping("/me")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateLocality(
            @Valid @RequestBody IndependentMasterUpdateRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);
        UserProfileResponse updated = userService.updateProfile(
                userId,
                new UpdateProfileRequest(
                        null,               // firstName — not updated here
                        null,               // lastName  — not updated here
                        null,               // phone     — not updated here
                        request.cityId(),
                        request.districtId(),
                        request.street(),
                        request.buildingNo(),
                        request.locationNote(),
                        null                // instagram — not updated here (locality-only path)
                )
        );
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    /**
     * Updates the authenticated independent master's public profile fields.
     *
     * <p>Distinct from {@code PATCH /me} (locality-only). This endpoint covers
     * the three human-readable profile fields displayed on the master detail screen:
     * phone number, bio, and Instagram handle.
     *
     * <p>Role gate: {@code INDEPENDENT_MASTER} only — same as {@code PATCH /me}.
     * No further ownership check is needed; the authenticated user ID is resolved
     * from the JWT ({@link AuthenticationUtils#userId}) and the service writes only that row.
     *
     * @param request        validated profile update body
     * @param authentication Spring Security context — carries the user UUID
     * @return updated user profile wrapped in {@link ApiResponse}
     */
    @PatchMapping("/me/profile")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<MasterPublicProfileResponse>> updateProfile(
            @Valid @RequestBody MasterProfileUpdateRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);
        MasterPublicProfileResponse updated = userService.updateMasterProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }
}
