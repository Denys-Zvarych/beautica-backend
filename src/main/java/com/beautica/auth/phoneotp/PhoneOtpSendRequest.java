package com.beautica.auth.phoneotp;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/book/otp/send}.
 *
 * <p>{@code phone} must be a Ukrainian E.164 number. The {@code @Pattern} is the
 * format backstop at the boundary so a malformed value is a clean 400, not a
 * service-layer {@code ValidationException} after the controller is entered (§A).
 *
 * <p>Phase 381 — {@code privacyAccepted} / {@code privacyPolicyVersion}: the phone is processed (stored in
 * {@code phone_otps}, sent to the SMS provider) before any booking exists, so consent is required here
 * too. This call only VALIDATES it; nothing is persisted (the booking POST stores the proof).
 */
public record PhoneOtpSendRequest(
        @NotBlank
        @Pattern(regexp = "\\+380[0-9]{9}", message = "Phone must be in +380XXXXXXXXX format")
        String phone,

        @NotNull(message = "Privacy policy must be accepted")
        @AssertTrue(message = "Privacy policy must be accepted")
        Boolean privacyAccepted,

        @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}(\\.\\d{1,3})?$",
                message = "Privacy policy version must look like 2026-10-20")
        String privacyPolicyVersion
) {}
