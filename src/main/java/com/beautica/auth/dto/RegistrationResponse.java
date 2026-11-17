package com.beautica.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of register / register-independent-master / resend-verification.
 *
 * <p>Legacy email shape: {@code {message, email}}, no tokens (verify the emailed code next). B514 phone shape
 * (registrationTicket): {@code {message, email: null, auth: {...tokens...}}} — the phone was already proven, so
 * the session starts at once (AUTH-02). {@code auth} is omitted from the JSON when absent, so the legacy body is
 * byte-identical to before.
 */
public record RegistrationResponse(
        String message,
        String email,
        @Schema(description = "Present only for phone registration (registrationTicket): the new session.",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        AuthResponse auth
) {

    public RegistrationResponse(String message, String email) {
        this(message, email, null);
    }

    public static RegistrationResponse of(String email) {
        return new RegistrationResponse(
                "Registration successful. Check your email for the verification code.",
                email);
    }

    /** B514 — phone registration: no email, tokens issued at once. */
    public static RegistrationResponse signedIn(AuthResponse auth) {
        return new RegistrationResponse("Registration successful.", null, auth);
    }

    /** {@code auth} carries tokens — never print them. */
    @Override
    public String toString() {
        return "RegistrationResponse[signedIn=" + (auth != null) + "]";
    }
}
