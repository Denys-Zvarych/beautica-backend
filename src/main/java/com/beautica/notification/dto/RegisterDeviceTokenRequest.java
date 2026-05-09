package com.beautica.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/devices/token}.
 *
 * <p>{@code platform} is bound as a {@link String} (not the {@code Platform} enum)
 * so an invalid value yields a clean Bean Validation 400 instead of leaking the
 * valid enum constants through Jackson's deserialization error message.
 */
public record RegisterDeviceTokenRequest(

        @NotBlank
        @Size(max = 500)
        String token,

        @NotBlank
        @Pattern(regexp = "^(ANDROID|IOS)$")
        String platform
) {}
