package com.beautica.auth.registration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** B514 — body of {@code POST /api/v1/auth/register/phone/otp/verify}. Same phone rules as the send request. */
public record RegistrationOtpVerifyRequest(
        @NotBlank(message = "Phone is required")
        @Size(max = 32, message = "Phone must not exceed 32 characters")
        @Pattern(regexp = "^[+\\d\\s\\-().]*$", message = "Phone contains invalid characters")
        String phone,

        @NotBlank(message = "Code is required")
        @Pattern(regexp = "[0-9]{6}", message = "Code must be 6 digits")
        String code
) {

    /** The phone (PII) and the code must never reach a log line. */
    @Override
    public String toString() {
        return "RegistrationOtpVerifyRequest[]";
    }
}
