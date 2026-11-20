package com.beautica.user.phone;

/** B516 — the signed-in phone OTP routes, shared by the ITs outside this package. */
public final class UserPhoneOtpITPaths {

    public static final String SEND = UserPhoneOtpIT.PHONE_SEND_URL;
    public static final String VERIFY = UserPhoneOtpIT.PHONE_VERIFY_URL;

    private UserPhoneOtpITPaths() {
    }
}
