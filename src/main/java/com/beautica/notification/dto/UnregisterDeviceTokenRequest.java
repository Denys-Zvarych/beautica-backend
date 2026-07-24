package com.beautica.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code DELETE /api/v1/devices/token}.
 */
public record UnregisterDeviceTokenRequest(

        @NotBlank(message = "Device token is required")
        @Size(max = 500, message = "Device token must be at most 500 characters")
        @Pattern(
                regexp = "^[A-Za-z0-9\\-_:]+$",
                message = "Device token must contain only alphanumeric characters, hyphens, underscores, or colons"
        )
        String token
) {}
