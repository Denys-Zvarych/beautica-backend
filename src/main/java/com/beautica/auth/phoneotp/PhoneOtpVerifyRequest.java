package com.beautica.auth.phoneotp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/book/otp/verify}.
 *
 * <p>{@code code} is constrained to exactly 6 digits via {@code @Pattern} (rather than
 * {@code @Size} alone) so non-numeric input is rejected at the boundary with a 400. The
 * width matches the platform's email-OTP entropy standard (1,000,000 code space). A
 * wrong-but-well-formed code still returns the generic 401 from the service so the
 * response is no oracle for code validity (§A).
 */
public record PhoneOtpVerifyRequest(
        @NotBlank
        @Pattern(regexp = "\\+380[0-9]{9}", message = "Phone must be in +380XXXXXXXXX format")
        String phone,

        @NotBlank
        @Size(min = 6, max = 6, message = "Code must be 6 digits")
        @Pattern(regexp = "[0-9]{6}", message = "Code must be 6 digits")
        String code
) {}
