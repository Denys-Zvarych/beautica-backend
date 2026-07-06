package com.beautica.auth.dto;

/**
 * Response body for {@code POST /api/v1/auth/verify-password-reset-otp}.
 *
 * @param resetTicket the raw, single-use ticket the client must submit as
 *                     {@code ResetPasswordRequest.resetTicket} to complete the reset. Only its
 *                     SHA-256 hash is persisted server-side ({@code PasswordResetTicket}); this
 *                     is the one and only time the raw value is ever transmitted.
 */
public record VerifyPasswordResetOtpResponse(String resetTicket) {
}
