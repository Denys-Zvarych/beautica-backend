package com.beautica.auth.phoneotp;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import com.beautica.common.validation.PolicyVersionDate;
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
 *
 * <p>Phase 382 — {@code turnstileToken}: see the component's comment.
 *
 * <p>Phase 440 — {@code smsFormat}: optional, a plain {@code String} (not a Jackson enum, which would 400 on an
 * unknown value). Missing/blank/unknown means {@code DOMAIN_BOUND}; parsed by {@link OtpSmsFormat#fromRequest}
 * in the service. Last component + the 4-arg constructor keep every existing caller compiling.
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
        @PolicyVersionDate
        String privacyPolicyVersion,

        // Phase 382 — Cloudflare Turnstile widget token. Deliberately NOT @NotBlank: where Turnstile is off
        // (local, test) the field is ignored and may be absent; where it is on, TurnstileVerifier rejects a
        // missing/blank token with 400. @Size mirrors Cloudflare's 2048-char cap so an oversized body field
        // never reaches the verifier.
        @Schema(description = "Required when Turnstile is enabled (prod, staging).", nullable = true,
                maxLength = 2048)
        @Size(max = 2048)
        String turnstileToken,

        @Schema(description = "Optional OTP SMS text. PLAIN = human line only; anything else (missing, null, "
                + "unknown) = DOMAIN_BOUND (adds the origin-bound `@host #code` line).",
                allowableValues = {"DOMAIN_BOUND", "PLAIN"}, nullable = true, maxLength = 32)
        @Size(max = 32)
        String smsFormat
) {

    /** Pre-phase-440 shape: {@code smsFormat} absent ({@code DOMAIN_BOUND}). */
    public PhoneOtpSendRequest(String phone, Boolean privacyAccepted, String privacyPolicyVersion,
                               String turnstileToken) {
        this(phone, privacyAccepted, privacyPolicyVersion, turnstileToken, null);
    }

    /**
     * Phase 382 audit: the generated record {@code toString} would print the phone and the Turnstile token. Neither
     * belongs in a log line; only the consent flag and version (non-sensitive) are shown.
     */
    @Override
    public String toString() {
        return "PhoneOtpSendRequest[privacyAccepted=" + privacyAccepted + ", privacyPolicyVersion="
                + privacyPolicyVersion + "]";
    }
}
