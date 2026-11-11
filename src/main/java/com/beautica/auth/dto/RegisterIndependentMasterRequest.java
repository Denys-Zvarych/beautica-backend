package com.beautica.auth.dto;

import com.beautica.common.validation.NoDigits;
import com.beautica.common.validation.PolicyVersionDate;
import com.beautica.common.validation.TermsConsentComplete;
import com.beautica.common.validation.TermsConsentPayload;
import jakarta.validation.constraints.AssertTrue;
import com.beautica.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@TermsConsentComplete
public record RegisterIndependentMasterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        // Shared @StrongPassword policy — same implementation as RegisterRequest /
        // ResetPasswordRequest so no self-registration path diverges on strength.
        @NotBlank(message = "Password is required")
        @StrongPassword
        String password,

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "First name must not contain control characters")
        @NoDigits(message = "First name must not contain a number")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Last name must not contain control characters")
        @NoDigits(message = "Last name must not contain a number")
        String lastName,

        @NotBlank(message = "Phone number is required")
        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        @Pattern(regexp = "^\\+?[0-9][0-9\\s\\-()]{6,19}$", message = "Phone number contains invalid characters")
        String phoneNumber,

        // Phase 442 — terms + privacy consent (Google Play UGC). Optional per component; the class-level
        // @TermsConsentComplete decides completeness and the app.auth.require-terms-consent window. The server
        // stamps terms_accepted_at from its own clock. Annotations copied from GuestBookingRequest.
        @AssertTrue(message = "Terms must be accepted")
        Boolean termsAccepted,

        @Size(max = 32)
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}(\\.\\d{1,3})?$",
                message = "Terms version must look like 2026-10-20")
        @PolicyVersionDate(message = "Terms version must be a real date that is not in the future")
        String termsVersion,

        @Size(max = 32)
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}(\\.\\d{1,3})?$",
                message = "Privacy policy version must look like 2026-10-20")
        @PolicyVersionDate
        String privacyPolicyVersion
) implements TermsConsentPayload {

    /**
     * The pre-phase-442 shape (no consent fields) — exactly what a legacy build sends. Keeps existing call sites
     * valid; over HTTP it passes only while {@code app.auth.require-terms-consent} is {@code false}.
     */
    public RegisterIndependentMasterRequest(String email, String password, String firstName, String lastName, String phoneNumber) {
        this(email, password, firstName, lastName, phoneNumber, null, null, null);
    }

    /**
     * Phase 442 audit — the password (and the user's identity, which is PII) must never reach a log line: the
     * default record {@code toString} would print every component, including {@code password}.
     */
    @Override
    public String toString() {
        return "RegisterIndependentMasterRequest[termsAccepted=" + termsAccepted
                + ", termsVersion=" + termsVersion + ", privacyPolicyVersion=" + privacyPolicyVersion + "]";
    }
}
