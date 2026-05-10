package com.beautica.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code DELETE /api/v1/devices/token}.
 */
public record UnregisterDeviceTokenRequest(

        @NotBlank
        @Size(max = 500)
        String token
) {}
