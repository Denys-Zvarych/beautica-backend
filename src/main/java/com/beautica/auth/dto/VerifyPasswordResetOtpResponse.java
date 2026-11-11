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

    /**
     * Phase 442 audit — the raw reset ticket must never reach a log line: the default record {@code toString}
     * would print every component.
     */
    @Override
    public String toString() {
        return "VerifyPasswordResetOtpResponse[]";
    }
}
