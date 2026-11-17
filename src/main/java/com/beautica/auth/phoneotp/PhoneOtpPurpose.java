package com.beautica.auth.phoneotp;

/**
 * B514 (D1, V244 {@code ck_phone_otps_purpose}) — the flow a phone OTP belongs to. A code verifies only for its own
 * purpose, so a guest-booking code can never complete a registration and vice versa. One table, one service
 * ({@link PhoneOtpService}); B516 uses {@link #PHONE_ADD}, {@link #PHONE_CHANGE} and {@link #PASSWORD_RESET}.
 */
public enum PhoneOtpPurpose {
    GUEST_BOOKING,
    REGISTRATION,
    PHONE_ADD,
    PHONE_CHANGE,
    PASSWORD_RESET
}
