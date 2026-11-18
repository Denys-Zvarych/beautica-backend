package com.beautica.auth.dto;

import com.beautica.common.validation.NoDigits;
import com.beautica.common.validation.PhoneRegistrationPayload;
import com.beautica.common.validation.PhoneRegistrationShape;
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
@PhoneRegistrationShape
public record RegisterIndependentMasterRequest(
        // Required only on the legacy shape (no registrationTicket) — see @PhoneRegistrationShape.
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

        // Optional with a registrationTicket; required on the legacy shape (@PhoneRegistrationShape).
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Last name must not contain control characters")
        @NoDigits(message = "Last name must not contain a number")
        String lastName,

        // Legacy shape only; ignored with a registrationTicket (the ticket's verified phone is stored).
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
        String privacyPolicyVersion,

        // B514 (AUTH-41) — the ticket from POST /auth/register/phone/otp/verify (phone proven by SMS code). With it the
        // account is phone-only (no email, AUTH-D11) and tokens are issued at once; the class-level
        // @PhoneRegistrationShape decides which shape is complete. Base64url, ~43 chars.
        @Size(max = 128, message = "Registration ticket is invalid")
        @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "Registration ticket is invalid")
        String registrationTicket
) implements TermsConsentPayload, PhoneRegistrationPayload {

    /**
     * The pre-phase-442 shape (no consent fields) — exactly what a legacy build sends. Keeps existing call sites
     * valid; over HTTP it passes only while {@code app.auth.require-terms-consent} is {@code false}.
     */
    public RegisterIndependentMasterRequest(String email, String password, String firstName, String lastName, String phoneNumber) {
        this(email, password, firstName, lastName, phoneNumber, null, null, null);
    }

    /** The pre-B514 shape (no {@code registrationTicket}) — the legacy email-code registration. */
    public RegisterIndependentMasterRequest(String email, String password, String firstName, String lastName,
                                            String phoneNumber, Boolean termsAccepted, String termsVersion,
                                            String privacyPolicyVersion) {
        this(email, password, firstName, lastName, phoneNumber, termsAccepted, termsVersion, privacyPolicyVersion,
                null);
    }

    @Override
    public boolean legacyShapeNeedsEmailAndLastName() {
        return true;
    }

    /**
     * Phase 442 audit — the password (and the user's identity, which is PII) must never reach a log line: the
     * default record {@code toString} would print every component, including {@code password}.
     */
    @Override
    public String toString() {
        return "RegisterIndependentMasterRequest[termsAccepted=" + termsAccepted
                + ", termsVersion=" + termsVersion + ", privacyPolicyVersion=" + privacyPolicyVersion
                + ", hasRegistrationTicket=" + hasRegistrationTicket() + "]";
    }
}
