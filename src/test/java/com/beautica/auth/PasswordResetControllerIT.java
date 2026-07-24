package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.dto.VerifyPasswordResetOtpRequest;
import com.beautica.auth.dto.VerifyPasswordResetOtpResponse;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.VerificationErrorResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.user.PasswordResetTicketRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for the password-reset OTP endpoints (Phase A — replaces the old
 * email-link flow):
 * <ol>
 *   <li>{@code POST /auth/forgot-password} — mints + emails a 6-digit OTP.</li>
 *   <li>{@code POST /auth/verify-password-reset-otp} — verifies the OTP, returns a resetTicket.</li>
 *   <li>{@code POST /auth/reset-password} — consumes the resetTicket, updates the password.</li>
 * </ol>
 *
 * <p>Uses the shared singleton Testcontainers Postgres from {@link AbstractIntegrationTest}.
 * {@code EmailNotificationService} is a {@code @MockBean} there — no SMTP connection needed;
 * we verify calls on the mock to confirm emails are sent or suppressed.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Password-reset OTP endpoints — integration")
class PasswordResetControllerIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetControllerIT.class);

    /**
     * Upper bound for awaiting an asynchronously-dispatched reset OTP email.
     * {@code PasswordResetService.scheduleResetOtpEmail} hands the send to the
     * {@code emailExecutor} pool from inside an {@code afterCommit} callback, so the HTTP
     * response returns strictly <em>before</em> the mock is invoked — a bare {@code verify}
     * races the executor and reddens under a saturated batch. {@code timeout} returns as soon
     * as the invocation lands, so the happy path pays no wall-clock cost.
     */
    private static final long EMAIL_DISPATCH_TIMEOUT_MS = 5_000L;

    /**
     * Settle window for asserting that <em>no</em> email was dispatched. Deliberately
     * {@code after} rather than {@code timeout}: {@code timeout(...).never()} returns
     * immediately and would pass spuriously by checking before a regressed async send could
     * land — i.e. it fails open, which is worse than the flake it would be papering over.
     * {@code after} always waits the full duration, giving a leaked send time to surface.
     */
    private static final long NO_EMAIL_SETTLE_MS = 1_000L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTicketRepository passwordResetTicketRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetMockInvocations() {
        Mockito.clearInvocations(emailNotificationService);
    }

    // =========================================================================
    // POST /api/v1/auth/forgot-password
    // =========================================================================

    @Test
    @DisplayName("should return 200 and send OTP email when email belongs to active+verified user")
    void should_return200AndSendOtpEmail_when_emailBelongsToVerifiedActiveUser() throws Exception {
        String email = "reset.valid@beautica.com";
        registerAndVerify(email, "Password1!");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isTrue();

        var user = transactionTemplate.execute(s -> userRepository.findByEmail(email).orElseThrow());
        assertThat(user.getPasswordResetCodeHash()).isNotNull();
        verify(emailNotificationService, timeout(EMAIL_DISPATCH_TIMEOUT_MS))
                .sendPasswordResetOtpEmail(any(User.class), any(String.class));
    }

    @Test
    @DisplayName("should return 200 with identical body and send no email when email is unknown (enumeration protection)")
    void should_return200AndSendNothing_when_emailUnknown() {
        ResponseEntity<String> unknownResp = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest("unknown-nobody@beautica.com"),
                String.class);
        ResponseEntity<String> alsoUnknownResp = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest("also-unknown@beautica.com"),
                String.class);

        assertThat(unknownResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unknownResp.getBody()).isEqualTo(alsoUnknownResp.getBody());

        verify(emailNotificationService, after(NO_EMAIL_SETTLE_MS).never())
                .sendPasswordResetOtpEmail(any(), any());
    }

    @Test
    @DisplayName("should return 400 when email field is blank")
    void should_return400_when_emailIsBlank() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(""),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // POST /api/v1/auth/verify-password-reset-otp
    // =========================================================================

    @Test
    @DisplayName("should return 200 with a resetTicket when the correct OTP is submitted")
    void should_return200WithResetTicket_when_otpCorrect() throws Exception {
        String email = "reset.otp.valid@beautica.com";
        registerAndVerify(email, "Password1!");
        String rawOtp = requestResetAndCaptureOtp(email);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest(email, rawOtp),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<VerifyPasswordResetOtpResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().resetTicket()).isNotBlank();

        assertThat(passwordResetTicketRepository.findAll()).isNotEmpty();
    }

    @Test
    @DisplayName("should return 400 INVALID_CODE when a wrong OTP is submitted")
    void should_return400InvalidCode_when_otpWrong() throws Exception {
        String email = "reset.otp.wrong@beautica.com";
        registerAndVerify(email, "Password1!");
        String rawOtp = requestResetAndCaptureOtp(email);
        String wrongOtp = rawOtp.equals("000000") ? "111111" : "000000";

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest(email, wrongOtp),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(body.data().code()).isEqualTo("INVALID_CODE");
    }

    @Test
    @DisplayName("should return 400 CODE_EXPIRED when the OTP has expired")
    void should_return400CodeExpired_when_otpExpired() throws Exception {
        String email = "reset.otp.expired@beautica.com";
        registerAndVerify(email, "Password1!");
        requestResetAndCaptureOtp(email);

        jdbcTemplate.update(
                "UPDATE users SET password_reset_code_expires_at = ? WHERE email = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), email);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest(email, "000000"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(body.data().code()).isEqualTo("CODE_EXPIRED");
    }

    @Test
    @DisplayName("should return 400 INVALID_CODE for an unknown email (anti-enumeration, wire-identical to wrong-code)")
    void should_return400InvalidCode_when_emailUnknown() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest("ghost@beautica.com", "123456"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(body.data().code()).isEqualTo("INVALID_CODE");
    }

    // The three tests below close a parity gap with the mirrored email-verification flow:
    // AuthControllerVerifyEmailTest asserts the same three malformed-code shapes as fast
    // @WebMvcTest cases (5-digit / non-numeric / blank code all violate
    // VerifyPasswordResetOtpRequest's @Size(min=6,max=6) / @Pattern(^[0-9]{6}$) / @NotBlank
    // BEFORE PasswordResetOtpProcessor is ever reached), but no equivalent existed for
    // verify-password-reset-otp — every other test in this class exercises business-rule
    // rejections (wrong code, expired, lockout), not the Bean Validation boundary itself.
    // No registerAndVerify/OTP setup is needed: validation short-circuits before the DB lookup.

    @Test
    @DisplayName("should return 400 when code is only 5 digits (below the 6-digit minimum)")
    void should_return400_when_codeIsFiveDigits() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest("someone@beautica.com", "12345"),
                String.class);

        assertThat(response.getStatusCode())
                .as("5-digit code must fail @Size(min=6,max=6) before reaching the service")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should return 400 when code contains non-numeric characters")
    void should_return400_when_codeIsNonNumeric() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest("someone@beautica.com", "abcdef"),
                String.class);

        assertThat(response.getStatusCode())
                .as("alphabetic code must fail @Pattern(^[0-9]{6}$) before reaching the service")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should return 400 when code field is blank")
    void should_return400_when_codeIsBlank() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest("someone@beautica.com", ""),
                String.class);

        assertThat(response.getStatusCode())
                .as("blank code must fail @NotBlank before reaching the service")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should lock the account after cumulative failures and reject wire-identically while locked")
    void should_lockAndRejectWireIdentically_when_cumulativeFailuresReachThreshold() throws Exception {
        String email = "reset.otp.lockout@beautica.com";
        registerAndVerify(email, "Password1!");
        requestResetAndCaptureOtp(email);

        // cumulative-failure-threshold = 10 (test profile mirrors prod default).
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> fail = restTemplate.postForEntity(
                    "/api/v1/auth/verify-password-reset-otp",
                    new VerifyPasswordResetOtpRequest(email, "999999"),
                    String.class);
            assertThat(fail.getStatusCode())
                    .as("wrong-code attempt %d must be 400", i + 1)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            if (i < 4) {
                continue;
            }
            // Reset the per-code attempt window (without touching the lifetime counter) so
            // each cycle of 5 wrong guesses doesn't just hit CODE_EXPIRED from the attempt cap.
            jdbcTemplate.update("UPDATE users SET password_reset_attempts = 0 WHERE email = ?", email);
        }

        var user = transactionTemplate.execute(s -> userRepository.findByEmail(email).orElseThrow());
        assertThat(user.getPasswordResetLockedUntil())
                .as("account must be locked once cumulative failures reach the threshold")
                .isNotNull();

        ResponseEntity<String> lockedResponse = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest(email, "123456"),
                String.class);
        assertThat(lockedResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var lockedBody = objectMapper.readValue(
                lockedResponse.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(lockedBody.data().code())
                .as("locked state must surface as the generic INVALID_CODE — no new oracle")
                .isEqualTo("INVALID_CODE");
    }

    // =========================================================================
    // POST /api/v1/auth/reset-password
    // =========================================================================

    @Test
    @DisplayName("should return 200 and change password when resetTicket is valid — full forgot→verify→reset flow")
    void should_return200AndChangePassword_when_resetTicketIsValid() throws Exception {
        String email = "reset.confirm@beautica.com";
        String oldPassword = "OldPassword1!";
        String newPassword = "NewPassword99!";
        registerAndVerify(email, oldPassword);

        String rawTicket = requestVerifyAndCaptureTicket(email);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawTicket, newPassword),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data()).isNull();

        ResponseEntity<String> loginOld = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, oldPassword), String.class);
        assertThat(loginOld.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> loginNew = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, newPassword), String.class);
        assertThat(loginNew.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("should return 400 on second submission of the same resetTicket (single-use)")
    void should_return400_when_ticketSubmittedTwice() throws Exception {
        String email = "reset.singleuse@beautica.com";
        registerAndVerify(email, "Password1!");
        String rawTicket = requestVerifyAndCaptureTicket(email);

        restTemplate.postForEntity("/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawTicket, "NewPassword99!"), String.class);

        ResponseEntity<String> secondResp = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawTicket, "AnotherPassword1!"),
                String.class);

        assertThat(secondResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(secondResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.message()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("should return 400 with generic message for a random unknown resetTicket")
    void should_return400WithGenericMessage_when_ticketIsUnknown() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest("completely-random-nonexistent-ticket", "ValidPassword1!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("should return 400 when resetTicket field is blank (bean validation)")
    void should_return400_when_resetTicketIsBlank() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest("", "ValidPassword1!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should return 400 with errors.resetTicket='Reset ticket is invalid' when it has a non-base64url char")
    void should_return400WithFieldError_when_resetTicketHasDisallowedChar() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest("abc+def==ghi", "ValidPassword1!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.errors()).containsEntry("resetTicket", "Reset ticket is invalid");
    }

    @Test
    @DisplayName("should revoke all refresh tokens after successful reset (global logout)")
    void should_revokeAllRefreshTokens_when_resetSucceeds() throws Exception {
        String email = "reset.sessions@beautica.com";
        registerAndVerify(email, "Password1!");

        restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "Password1!"), String.class);

        String rawTicket = requestVerifyAndCaptureTicket(email);
        restTemplate.postForEntity("/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawTicket, "NewPassword99!"), String.class);

        var user = userRepository.findByEmail(email).orElseThrow();
        long remaining = transactionTemplate.execute(status ->
                refreshTokenRepository.countByUserId(user.getId()));
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("should return 401 for a pre-reset access token after a successful password reset")
    void should_return401_when_reusingPreResetAccessTokenAfterReset() throws Exception {
        String email = "reset.accesstoken@beautica.com";
        String oldPassword = "OldPassword1!";
        registerAndVerify(email, oldPassword);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, oldPassword), String.class);
        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String preResetAccessToken = loginBody.data().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(preResetAccessToken);

        ResponseEntity<String> preResetMe = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(preResetMe.getStatusCode()).isEqualTo(HttpStatus.OK);

        String rawTicket = requestVerifyAndCaptureTicket(email);
        ResponseEntity<String> resetResp = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawTicket, "NewPassword99!"),
                String.class);
        assertThat(resetResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> postResetMe = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(postResetMe.getStatusCode())
                .as("a pre-reset access token must be rejected after the reset")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Authenticated entry point: POST /users/me/change-password/request-otp
    // =========================================================================

    @Test
    @DisplayName("authenticated change-password-request-otp → verify-otp → reset-password forces the CALLER'S OWN session out too")
    void should_forceOwnSessionOut_when_authenticatedChangePasswordFlowCompletes() throws Exception {
        String email = "reset.selfsession@beautica.com";
        String oldPassword = "OldPassword1!";
        registerAndVerify(email, oldPassword);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, oldPassword), String.class);
        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String accessToken = loginBody.data().accessToken();
        String refreshToken = loginBody.data().refreshToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        // Authenticated entry point — no request body, identified by the caller's own JWT.
        ResponseEntity<String> otpReq = restTemplate.exchange(
                "/api/v1/users/me/change-password/request-otp",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(otpReq.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService, timeout(EMAIL_DISPATCH_TIMEOUT_MS))
                .sendPasswordResetOtpEmail(any(User.class), otpCaptor.capture());
        String rawOtp = otpCaptor.getValue();

        ResponseEntity<String> verifyResp = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest(email, rawOtp),
                String.class);
        var verifyBody = objectMapper.readValue(
                verifyResp.getBody(), new TypeReference<ApiResponse<VerifyPasswordResetOtpResponse>>() {});
        String rawTicket = verifyBody.data().resetTicket();

        ResponseEntity<String> resetResp = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawTicket, "NewPassword99!"),
                String.class);
        assertThat(resetResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The unconditional refresh-token wipe + tokensValidAfter stamp in resetPassword()
        // forces the CALLER'S OWN pre-reset session out too — no special-casing.
        ResponseEntity<String> refreshResp = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(refreshToken),
                String.class);
        assertThat(refreshResp.getStatusCode())
                .as("the same session's existing refresh token must be rejected after self-service reset")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("unauthenticated caller gets 401 on change-password-request-otp")
    void should_return401_when_changePasswordOtpRequestedWithoutJwt() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/users/me/change-password/request-otp", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Registers a CLIENT user and asserts their email is marked verified in the DB. */
    private void registerAndVerify(String email, String password) {
        ResponseEntity<String> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, "Test", "User", "+380501234567", null),
                String.class);
        assertThat(registerResponse.getStatusCode())
                .as("register must succeed before the test can verify the account")
                .isEqualTo(HttpStatus.OK);

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new AssertionError(
                            "registered user not visible after register POST committed: " + email));
            user.setEmailVerified(true);
            userRepository.save(user);
        });

        User reread = userRepository.findByEmail(email).orElseThrow();
        assertThat(reread.isActive() && reread.isEmailVerified())
                .as("user must be active and email-verified before forgot-password, else requestReset() no-ops")
                .isTrue();
    }

    /** Calls forgot-password and captures the raw OTP from the (mocked) email send. */
    private String requestResetAndCaptureOtp(String email) {
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email), String.class);
        verify(emailNotificationService, timeout(EMAIL_DISPATCH_TIMEOUT_MS))
                .sendPasswordResetOtpEmail(any(User.class), otpCaptor.capture());
        return otpCaptor.getValue();
    }

    /** Full forgot-password → verify-otp round trip, returning the minted raw resetTicket. */
    private String requestVerifyAndCaptureTicket(String email) throws Exception {
        String rawOtp = requestResetAndCaptureOtp(email);

        ResponseEntity<String> verifyResp = restTemplate.postForEntity(
                "/api/v1/auth/verify-password-reset-otp",
                new VerifyPasswordResetOtpRequest(email, rawOtp),
                String.class);
        var verifyBody = objectMapper.readValue(
                verifyResp.getBody(), new TypeReference<ApiResponse<VerifyPasswordResetOtpResponse>>() {});
        return verifyBody.data().resetTicket();
    }
}
