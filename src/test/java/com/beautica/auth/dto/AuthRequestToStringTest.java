package com.beautica.auth.dto;

import com.beautica.auth.Role;
import com.beautica.auth.phoneotp.GuestTokenResponse;
import com.beautica.auth.phoneotp.PhoneOtpVerifyRequest;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 442 audit — auth request records must never print the password, the invite token or the user's
 * identity through {@code toString()}; and terms consent is insert-only on {@link User}.
 */
@DisplayName("Auth DTO toString masking + insert-only terms consent")
class AuthRequestToStringTest {

    private static final String PASSWORD = "S3cret-Passw0rd-Value!";
    private static final String TOKEN = "invite-token-abcdef0123456789";
    private static final String EMAIL = "secret.user@example.com";
    private static final String PHONE = "+380501234567";
    private static final String REFRESH_TOKEN = "refresh-token-0123456789abcdef";
    private static final String ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.access-token-payload.sig";
    private static final String RESET_TICKET = "reset-ticket-AbCdEf0123456789_-";
    private static final String OTP_CODE = "493817";
    private static final String TERMS_VERSION = "2026-10-20";
    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    @DisplayName("RegisterRequest.toString hides password, email, phone and names")
    void should_maskPasswordAndPii_when_registerRequestToString() {
        var request = new RegisterRequest(EMAIL, PASSWORD, SelfRegistrationRole.CLIENT, "Olena", "Koval", PHONE,
                "Studio", true, TERMS_VERSION, TERMS_VERSION);

        var text = request.toString();

        assertThat(text).startsWith("RegisterRequest[")
                .doesNotContain(PASSWORD, EMAIL, PHONE, "Olena", "Koval");
    }

    @Test
    @DisplayName("RegisterIndependentMasterRequest.toString hides password and PII")
    void should_maskPasswordAndPii_when_registerIndependentMasterRequestToString() {
        var request = new RegisterIndependentMasterRequest(EMAIL, PASSWORD, "Olena", "Koval", PHONE,
                true, TERMS_VERSION, TERMS_VERSION);

        var text = request.toString();

        assertThat(text).startsWith("RegisterIndependentMasterRequest[")
                .doesNotContain(PASSWORD, EMAIL, PHONE, "Olena", "Koval");
    }

    @Test
    @DisplayName("InviteAcceptRequest.toString hides password, invite token and PII")
    void should_maskPasswordTokenAndPii_when_inviteAcceptRequestToString() {
        var request = new InviteAcceptRequest(TOKEN, PASSWORD, "Olena", "Koval", PHONE,
                true, TERMS_VERSION, TERMS_VERSION);

        var text = request.toString();

        assertThat(text).startsWith("InviteAcceptRequest[")
                .doesNotContain(PASSWORD, TOKEN, PHONE, "Olena", "Koval");
    }

    @Test
    @DisplayName("LoginRequest.toString hides password and email")
    void should_maskPasswordAndEmail_when_loginRequestToString() {
        var request = new LoginRequest(EMAIL, PASSWORD);

        var text = request.toString();

        assertThat(text).startsWith("LoginRequest[")
                .doesNotContain(PASSWORD, EMAIL);
    }

    @Test
    @DisplayName("ResetPasswordRequest.toString hides ticket and password")
    void should_maskPasswordAndTicket_when_resetPasswordRequestToString() {
        var request = new ResetPasswordRequest(RESET_TICKET, PASSWORD);

        var text = request.toString();

        assertThat(text).startsWith("ResetPasswordRequest[")
                .doesNotContain(RESET_TICKET, PASSWORD);
    }

    @Test
    @DisplayName("RefreshRequest.toString hides the refresh token")
    void should_maskRefreshToken_when_refreshRequestToString() {
        var request = new RefreshRequest(REFRESH_TOKEN);

        var text = request.toString();

        assertThat(text).startsWith("RefreshRequest[")
                .doesNotContain(REFRESH_TOKEN);
    }

    @Test
    @DisplayName("AuthResponse.toString hides both tokens and the email")
    void should_maskTokensAndEmail_when_authResponseToString() {
        var userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        var response = AuthResponse.of(ACCESS_TOKEN, REFRESH_TOKEN, userId, EMAIL, Role.CLIENT);

        var text = response.toString();

        assertThat(text).startsWith("AuthResponse[")
                .doesNotContain(ACCESS_TOKEN, REFRESH_TOKEN, EMAIL)
                .contains("role=CLIENT", "tokenType=Bearer");
    }

    @Test
    @DisplayName("VerifyPasswordResetOtpResponse.toString hides the reset ticket")
    void should_maskResetTicket_when_verifyPasswordResetOtpResponseToString() {
        var response = new VerifyPasswordResetOtpResponse(RESET_TICKET);

        var text = response.toString();

        assertThat(text).startsWith("VerifyPasswordResetOtpResponse[")
                .doesNotContain(RESET_TICKET);
    }

    @Test
    @DisplayName("VerifyPasswordResetOtpRequest.toString hides OTP and email")
    void should_maskCodeAndEmail_when_verifyPasswordResetOtpRequestToString() {
        var request = new VerifyPasswordResetOtpRequest(EMAIL, OTP_CODE);

        var text = request.toString();

        assertThat(text).startsWith("VerifyPasswordResetOtpRequest[")
                .doesNotContain(OTP_CODE, EMAIL);
    }

    @Test
    @DisplayName("VerifyEmailRequest.toString hides OTP and email")
    void should_maskCodeAndEmail_when_verifyEmailRequestToString() {
        var request = new VerifyEmailRequest(EMAIL, OTP_CODE);

        var text = request.toString();

        assertThat(text).startsWith("VerifyEmailRequest[")
                .doesNotContain(OTP_CODE, EMAIL);
    }

    @Test
    @DisplayName("PhoneOtpVerifyRequest.toString hides OTP and phone")
    void should_maskCodeAndPhone_when_phoneOtpVerifyRequestToString() {
        var request = new PhoneOtpVerifyRequest(PHONE, OTP_CODE);

        var text = request.toString();

        assertThat(text).startsWith("PhoneOtpVerifyRequest[")
                .doesNotContain(OTP_CODE, PHONE);
    }

    @Test
    @DisplayName("GuestTokenResponse.toString hides the guest token")
    void should_maskGuestToken_when_guestTokenResponseToString() {
        var response = new GuestTokenResponse(ACCESS_TOKEN);

        var text = response.toString();

        assertThat(text).startsWith("GuestTokenResponse[")
                .doesNotContain(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("User.recordTermsConsent is insert-only — a second call throws, first triple kept")
    void should_throwIllegalState_when_termsConsentRecordedTwice() {
        var user = new User(EMAIL, "hash", Role.CLIENT, "Olena", "Koval", PHONE);
        user.recordTermsConsent(ACCEPTED_AT, TERMS_VERSION, TERMS_VERSION);

        assertThatThrownBy(() -> user.recordTermsConsent(ACCEPTED_AT.plusSeconds(60), "2026-11-01", "2026-11-01"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Terms consent already recorded");

        assertThat(user.getTermsAcceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(user.getTermsVersion()).isEqualTo(TERMS_VERSION);
        assertThat(user.getPrivacyPolicyVersion()).isEqualTo(TERMS_VERSION);
    }
}
