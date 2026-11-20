package com.beautica.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/forgot-password}.
 *
 * <p>B516 (AUTH-12, AUTH-D18): {@code identifier} is a phone (any Ukrainian spelling) or an email — the app sends
 * what the user typed in «Телефон або email». {@code email} is the pre-B516 field, still accepted from old builds.
 * Exactly the same flow either way; at least one must be present ({@link #isIdentifierPresent}). When both are sent,
 * {@code identifier} wins.
 *
 * <p>Bean Validation (§A): {@code @Email} on the legacy field is not paired with {@code @NotNull} on purpose — null is
 * legitimate now that {@code identifier} exists. {@code @Size(max = 255)} mirrors {@code users.email}; the identifier
 * pattern refuses control characters. Enumeration protection lives in the service — a valid request always gets 200.
 */
public record ForgotPasswordRequest(

        @Schema(description = "Legacy (pre-B516): the account email. Prefer identifier.", nullable = true)
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @Schema(description = "Phone (+380…, 0…) or email, as typed by the user.", nullable = true, maxLength = 255)
        @Size(max = 255, message = "Identifier must not exceed 255 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Identifier contains invalid characters")
        String identifier

) {

    /** Pre-B516 shape. */
    public ForgotPasswordRequest(String email) {
        this(email, null);
    }

    /** {@code identifier} when given, else the legacy {@code email}; trimmed; never blank once valid. */
    public String resolvedIdentifier() {
        String value = identifier != null && !identifier.isBlank() ? identifier : email;
        return value == null ? null : value.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "Phone or email is required")
    public boolean isIdentifierPresent() {
        String value = resolvedIdentifier();
        return value != null && !value.isEmpty();
    }

    /** The identifier (phone or email) is PII — never in a log line. */
    @Override
    public String toString() {
        return "ForgotPasswordRequest[]";
    }
}
