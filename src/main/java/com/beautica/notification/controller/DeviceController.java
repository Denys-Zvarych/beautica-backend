package com.beautica.notification.controller;

import com.beautica.common.security.AuthenticationUtils;
import com.beautica.notification.dto.RegisterDeviceTokenRequest;
import com.beautica.notification.dto.UnregisterDeviceTokenRequest;
import com.beautica.notification.entity.DeviceToken;
import com.beautica.notification.entity.Platform;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.beautica.user.UserRepository;
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
    private final UserRepository userRepository;

    @PostMapping("/token")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> registerToken(
            @Valid @RequestBody RegisterDeviceTokenRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);

        // Idempotency pre-check: avoids the rollback-only transaction state caused by
        // catching DataIntegrityViolationException after a UNIQUE-collision save().
        // Race window: two concurrent registrations of the same (userId, token) may both
        // pass this check; one save() will then surface DataIntegrityViolationException
        // to GlobalExceptionHandler. Caller may retry.
        if (deviceTokenRepository.existsByUserIdAndToken(userId, request.token())) {
            return ResponseEntity.noContent().build();
        }

        DeviceToken deviceToken = DeviceToken.builder()
                .user(userRepository.getReferenceById(userId))
                .token(request.token())
                .platform(Platform.valueOf(request.platform()))
                .isActive(true)
                .build();

        deviceTokenRepository.save(deviceToken);
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
