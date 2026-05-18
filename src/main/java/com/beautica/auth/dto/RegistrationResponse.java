package com.beautica.auth.dto;

public record RegistrationResponse(String message, String email) {

    public static RegistrationResponse of(String email) {
        return new RegistrationResponse(
                "Registration successful. Check your email for the verification code.",
                email);
    }
}
