package com.beautica.auth.phoneotp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/book/otp/send}.
 *
 * <p>{@code phone} must be a Ukrainian E.164 number. The {@code @Pattern} is the
 * format backstop at the boundary so a malformed value is a clean 400, not a
 * service-layer {@code ValidationException} after the controller is entered (§A).
 */
public record PhoneOtpSendRequest(
        @NotBlank
        @Pattern(regexp = "\\+380[0-9]{9}", message = "Phone must be in +380XXXXXXXXX format")
        String phone
) {}
