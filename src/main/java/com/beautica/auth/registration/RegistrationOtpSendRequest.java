package com.beautica.auth.registration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * B514 — body of {@code POST /api/v1/auth/register/phone/otp/send}.
 *
 * <p>{@code phone} is any human spelling of a Ukrainian number ({@code +380…}, {@code 0…}, with spaces / dashes /
 * brackets); the service normalises it and answers {@code 400 PHONE_NOT_UA_MOBILE} for anything that is not a UA
 * mobile. The boundary only caps the size and the character set (§A, phone row).
 * {@code smsFormat} as in the guest OTP (AUTH-30): missing/unknown = DOMAIN_BOUND, never a 400.
 */
public record RegistrationOtpSendRequest(
        @NotBlank(message = "Phone is required")
        @Size(max = 32, message = "Phone must not exceed 32 characters")
        @Pattern(regexp = "^[+\\d\\s\\-().]*$", message = "Phone contains invalid characters")
        String phone,

        @Schema(description = "Optional OTP SMS text. PLAIN = human line only; anything else (missing, null, "
                + "unknown) = DOMAIN_BOUND.", allowableValues = {"DOMAIN_BOUND", "PLAIN"}, nullable = true,
                maxLength = 32)
        @Size(max = 32)
        String smsFormat
) {

    /** The phone is PII — never in a log line. */
    @Override
    public String toString() {
        return "RegistrationOtpSendRequest[smsFormat=" + smsFormat + "]";
    }
}
