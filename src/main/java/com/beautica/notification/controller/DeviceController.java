package com.beautica.notification.controller;

import com.beautica.common.security.AuthenticationUtils;
import com.beautica.notification.dto.RegisterDeviceTokenRequest;
import com.beautica.notification.dto.UnregisterDeviceTokenRequest;
import com.beautica.notification.repository.DeviceTokenRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceTokenRepository deviceTokenRepository;

    @PostMapping("/token")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> registerToken(
            @Valid @RequestBody RegisterDeviceTokenRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);

        // D10 (phase 339): ONE atomic upsert by token. A token already bound to ANOTHER user (shared
        // device switching accounts), deactivated, or registered under a different platform is
        // rebound to the caller, re-activated and its platform refreshed — never duplicated, and no
        // reassign/exists/save window for a concurrent registration to slip through.
        deviceTokenRepository.upsertToken(request.token(), userId, request.platform());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/token")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unregisterToken(
            @Valid @RequestBody UnregisterDeviceTokenRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);
        deviceTokenRepository.deleteByUserIdAndToken(userId, request.token());
        return ResponseEntity.noContent().build();
    }
}
