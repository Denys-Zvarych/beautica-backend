package com.beautica.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Login body (AUTH-08, AUTH-D09). B514: {@code identifier} is a phone or an email — input containing «@» is an
 * email, anything else is normalised to {@code +380…} and matched against VERIFIED phones only. {@code email} stays
 * accepted for builds older than M515; when both are present {@code identifier} wins.
 */
public record LoginRequest(
        @Schema(description = "Pre-B514 field; use identifier.", nullable = true)
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(max = 128, message = "Password must not exceed 128 characters")
        String password,

        @Schema(description = "Phone (+380…, 0…) or email. Required unless the legacy email field is sent.",
                nullable = true, maxLength = 255)
        @Size(max = 255, message = "Identifier must not exceed 255 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Identifier must not contain control characters")
        String identifier
) {

    /** Pre-B514 shape: email + password. */
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }

    /** The identifier to look up: {@code identifier} when given, else the legacy {@code email}. */
    public String loginIdentifier() {
        return identifier != null && !identifier.isBlank() ? identifier : email;
    }

    /** Bean Validation hook: one of {@code identifier} / {@code email} must be present. */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "Phone or email is required")
    public boolean isIdentifierPresent() {
        String value = loginIdentifier();
        return value != null && !value.isBlank();
    }

    /**
     * Phase 442 audit — the password (and the email / phone, which are PII) must never reach a log line: the default
     * record {@code toString} would print every component, including {@code password}.
     */
    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
