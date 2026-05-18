package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.EmailNotVerifiedResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.VerificationErrorResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * End-to-end integration test for the full email verification lifecycle.
 *
 * <p>Uses a real Spring context with Testcontainers PostgreSQL. The
 * {@link EmailNotificationService} is mocked in {@link AbstractIntegrationTest},
 * and the {@code emailExecutor} bean is replaced with a synchronous
 * {@link org.springframework.core.task.TaskExecutor} by {@link com.beautica.config.TestAsyncConfig}
 * (imported via {@link AbstractIntegrationTest}) so that {@link ArgumentCaptor} can capture
 * the raw OTP reliably before the verify call — no races, no {@code Thread.sleep}.
 *
 * <p>Time manipulation (expired code, resend cooldown bypass) is performed via
 * direct SQL updates through {@link org.springframework.jdbc.core.JdbcTemplate}
 * — no {@link java.time.Clock} mocking is required.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Email verification lifecycle — integration")
class EmailVerificationIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationIT.class);

    // ── Infrastructure ────────────────────────────────────────────────────────

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AuthService authService;


    // ── Helper: register and capture raw OTP ─────────────────────────────────

    /**
     * Registers a new CLIENT account for the given e-mail, asserts HTTP 200,
     * captures the raw OTP passed to {@code sendVerificationEmail}, resets the
     * mock so subsequent {@code verify()} calls in the same test start fresh,
     * and returns the captured code.
     *
     * <p>The mock is always reset after capture so that tests that call this
     * helper more than once (e.g. resend tests) do not see stale interactions.
     */
    private String registerAndCaptureCode(String email) throws Exception {
        var request = new RegisterRequest(
                email, "password123",
                SelfRegistrationRole.CLIENT, "Anna", "Test", null, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);
        assertThat(response.getStatusCode())
                .as("register must return 200 for email=%s", email)
                .isEqualTo(HttpStatus.OK);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService).sendVerificationEmail(eq(email), captor.capture());
        Mockito.reset(emailNotificationService);

        return captor.getValue();
    }

    // ── Test 1 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return RegistrationResponse when client registers")
    void should_returnRegistrationResponse_when_clientRegisters() throws Exception {
        var email = "reg.lifecycle@beautica.test";
        var request = new RegisterRequest(
                email, "password123",
                SelfRegistrationRole.CLIENT, "Anna", "Test", null, null);
        log.debug("Arrange: CLIENT register request for email={}", email);

        log.debug("Act: POST /api/v1/auth/register");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        log.debug("Assert: 200, success=true, data.email matches, no tokens in body");
        assertThat(response.getStatusCode())
                .as("register must return 200 for email=%s", email)
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<RegistrationResponse>>() {});
        assertThat(apiResponse.success())
                .as("success must be true")
                .isTrue();
        assertThat(apiResponse.data().email())
                .as("returned email must match the registered address")
                .isEqualTo(email);
        assertThat(apiResponse.data().message())
                .as("message must not be blank")
                .isNotBlank();

        // No tokens in body — raw string check to catch accidental serialisation
        assertThat(response.getBody())
                .as("accessToken must not be present in registration response")
                .doesNotContain("accessToken");
        assertThat(response.getBody())
                .as("refreshToken must not be present in registration response")
                .doesNotContain("refreshToken");

        // DB state
        var user = transactionTemplate.execute(s ->
                userRepository.findByEmail(email).orElseThrow());
        assertThat(user.isEmailVerified())
                .as("email must not be verified immediately after registration")
                .isFalse();
        assertThat(user.getVerificationCodeHash())
                .as("verification_code_hash must be stored after registration")
                .isNotNull();
        assertThat(user.getVerificationCodeExpiresAt())
                .as("verification_code_expires_at must be in the future")
                .isAfter(Instant.now());
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should send verification email with 6-digit OTP when client registers")
    void should_sendVerificationEmail_when_clientRegisters() throws Exception {
        var email = "send.otp@beautica.test";
        log.debug("Arrange: no setup needed — registration triggers the email send");

        log.debug("Act: POST /api/v1/auth/register for email={}", email);
        var request = new RegisterRequest(
                email, "password123",
                SelfRegistrationRole.CLIENT, "Anna", "Test", null, null);
        restTemplate.postForEntity("/api/v1/auth/register", request, String.class);

        log.debug("Assert: sendVerificationEmail called once with a 6-digit code");
        var captor = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService, times(1))
                .sendVerificationEmail(eq(email), captor.capture());

        assertThat(captor.getValue())
                .as("captured OTP must be exactly 6 decimal digits")
                .matches("[0-9]{6}");
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should verify email and return tokens when valid code is provided")
    void should_verifyEmailAndReturnTokens_when_validCodeProvided() throws Exception {
        var email = "verify.valid@beautica.test";
        log.debug("Arrange: register and capture OTP for email={}", email);
        String code = registerAndCaptureCode(email);

        log.debug("Act: POST /api/v1/auth/verify-email with the captured code");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-email",
                new VerifyEmailRequest(email, code),
                String.class);

        log.debug("Assert: 200, tokens returned, DB state reflects verified account");
        assertThat(response.getStatusCode())
                .as("verify-email with valid code must return 200")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().accessToken())
                .as("accessToken must not be blank on successful verification")
                .isNotBlank();
        assertThat(apiResponse.data().refreshToken())
                .as("refreshToken must not be blank on successful verification")
                .isNotBlank();
        assertThat(apiResponse.data().tokenType())
                .as("tokenType must be Bearer")
                .isEqualTo("Bearer");
        assertThat(apiResponse.data().email())
                .as("email in AuthResponse must match the registered address")
                .isEqualTo(email);

        var user = transactionTemplate.execute(s ->
                userRepository.findByEmail(email).orElseThrow());
        assertThat(user.isEmailVerified())
                .as("email_verified must be true after successful verification")
                .isTrue();
        assertThat(user.getVerificationCodeHash())
                .as("verification_code_hash must be cleared after verification")
                .isNull();
        assertThat(user.getVerificationCodeExpiresAt())
                .as("verification_code_expires_at must be cleared after verification")
                .isNull();
        assertThat(user.getVerificationAttempts())
                .as("verification_attempts must be reset to 0 after verification")
                .isZero();
    }

    // ── Test 4 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 400 CODE_EXPIRED when code has expired")
    void should_return400CodeExpired_when_codeExpired() throws Exception {
        var email = "expired.code@beautica.test";
        log.debug("Arrange: register email={} then expire the code via SQL", email);
        registerAndCaptureCode(email);

        // Push expiry into the past so AuthService.verifyEmail() sees it as expired.
        jdbcTemplate.update(
                "UPDATE users SET verification_code_expires_at = ? WHERE email = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), email);

        log.debug("Act: POST /api/v1/auth/verify-email with any 6-digit code");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-email",
                new VerifyEmailRequest(email, "000000"),
                String.class);

        log.debug("Assert: 400, data.code=CODE_EXPIRED");
        assertThat(response.getStatusCode())
                .as("expired code must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.data().code())
                .as("error code must be CODE_EXPIRED")
                .isEqualTo("CODE_EXPIRED");

        // After F1 (noRollbackFor = VerificationException.class), the transaction commits
        // on CODE_EXPIRED, so the hash-clearing save() inside the expiry branch is persisted.
        var userAfterExpiry = transactionTemplate.execute(s ->
                userRepository.findByEmail(email).orElseThrow());
        assertThat(userAfterExpiry.getVerificationCodeHash())
                .as("verification_code_hash must be cleared after CODE_EXPIRED")
                .isNull();
        assertThat(userAfterExpiry.getVerificationCodeExpiresAt())
                .as("verification_code_expires_at must be cleared after CODE_EXPIRED")
                .isNull();
    }

    // ── Test 5 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 400 INVALID_CODE and increment attempt counter when wrong code is submitted")
    void should_return400InvalidCode_when_codeInvalid() throws Exception {
        var email = "invalid.code@beautica.test";
        log.debug("Arrange: register email={} and capture valid OTP", email);
        String correctCode = registerAndCaptureCode(email);

        // Use a different code — pick one that is guaranteed not to equal the real OTP
        String wrongCode = correctCode.equals("000000") ? "111111" : "000000";

        log.debug("Act: POST /api/v1/auth/verify-email with wrong code={}", wrongCode);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-email",
                new VerifyEmailRequest(email, wrongCode),
                String.class);

        log.debug("Assert: 400, data.code=INVALID_CODE, hash retained");
        assertThat(response.getStatusCode())
                .as("wrong code must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.data().code())
                .as("error code must be INVALID_CODE")
                .isEqualTo("INVALID_CODE");

        var user = transactionTemplate.execute(s ->
                userRepository.findByEmail(email).orElseThrow());
        assertThat(user.getVerificationCodeHash())
                .as("verification_code_hash must NOT be cleared after a wrong-code attempt")
                .isNotNull();
        // After F1 (noRollbackFor = VerificationException.class), the transaction commits on
        // INVALID_CODE, so the explicit save() in the attempt-increment path is persisted.
        assertThat(user.getVerificationAttempts())
                .as("attempt counter must be persisted after wrong-code attempt")
                .isEqualTo((short) 1);
    }

    // ── Test 6 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 403 EMAIL_NOT_VERIFIED when unverified user attempts to log in")
    void should_return403EmailNotVerified_when_unverifiedUserLogsIn() throws Exception {
        var email = "unverified.login@beautica.test";
        log.debug("Arrange: register email={} without verifying", email);
        var request = new RegisterRequest(
                email, "password123",
                SelfRegistrationRole.CLIENT, "Anna", "Test", null, null);
        restTemplate.postForEntity("/api/v1/auth/register", request, String.class);

        log.debug("Act: POST /api/v1/auth/login with correct password but unverified email");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "password123"),
                String.class);

        log.debug("Assert: 403, data.code=EMAIL_NOT_VERIFIED, data.email matches");
        assertThat(response.getStatusCode())
                .as("login for unverified account must return 403")
                .isEqualTo(HttpStatus.FORBIDDEN);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<EmailNotVerifiedResponse>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.data().code())
                .as("error code must be EMAIL_NOT_VERIFIED")
                .isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(apiResponse.data().email())
                .as("email in body must match the login attempt address")
                .isEqualTo(email);
    }

    // ── Test 7 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 200 with tokens when verified user logs in")
    void should_return200_when_verifiedUserLogsIn() throws Exception {
        var email = "verified.login@beautica.test";
        log.debug("Arrange: register, verify, then login for email={}", email);
        String code = registerAndCaptureCode(email);

        restTemplate.postForEntity(
                "/api/v1/auth/verify-email",
                new VerifyEmailRequest(email, code),
                String.class);

        log.debug("Act: POST /api/v1/auth/login after verification");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "password123"),
                String.class);

        log.debug("Assert: 200, accessToken not blank, email matches");
        assertThat(response.getStatusCode())
                .as("login for verified account must return 200")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().accessToken())
                .as("accessToken must not be blank on login")
                .isNotBlank();
        assertThat(apiResponse.data().email())
                .as("email in AuthResponse must match the login email")
                .isEqualTo(email);
    }

    // ── Test 8 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should resend a new verification code and reset attempt counter when cooldown has passed")
    void should_resendVerificationCode_when_requested() throws Exception {
        var email = "resend.ok@beautica.test";
        log.debug("Arrange: register email={}, advance DB time to bypass 60s cooldown", email);
        String originalCode = registerAndCaptureCode(email);

        // Derive: issuedAt = expiresAt - 900s, nextAllowed = issuedAt + 60s.
        // Setting expiresAt = now + 839s  →  issuedAt = now - 61s  →  nextAllowed = now - 1s  (past)
        jdbcTemplate.update(
                "UPDATE users SET verification_code_expires_at = ? WHERE email = ?",
                Timestamp.from(Instant.now().plusSeconds(839L)), email);

        log.debug("Act: POST /api/v1/auth/resend-verification for email={}", email);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/resend-verification",
                new ResendVerificationRequest(email),
                String.class);

        log.debug("Assert: 200, new OTP sent, hash changed, attempts reset");
        assertThat(response.getStatusCode())
                .as("resend after cooldown must return 200")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<RegistrationResponse>>() {});
        assertThat(apiResponse.success()).isTrue();

        var captor2 = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService, times(1))
                .sendVerificationEmail(eq(email), captor2.capture());
        String newCode = captor2.getValue();

        assertThat(newCode)
                .as("resent OTP must be a 6-digit string")
                .matches("[0-9]{6}");
        assertThat(newCode)
                .as("resent OTP must differ from the original OTP")
                .isNotEqualTo(originalCode);

        var user = transactionTemplate.execute(s ->
                userRepository.findByEmail(email).orElseThrow());
        assertThat(user.getVerificationAttempts())
                .as("verification_attempts must be reset to 0 on resend")
                .isZero();
    }

    // ── Test 9 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw ResendThrottledException with positive retryAfter when resend is called within 60 seconds of OTP issuance")
    void should_return429_when_resendCalledWithin60Seconds() throws Exception {
        var email = "resend.throttle@beautica.test";
        log.debug("Arrange: register email={} — OTP just issued, cooldown active", email);
        // Register so the user row exists with verificationCodeExpiresAt = now + 15min.
        // issuedAt = now, nextAllowed = now + 60s — the per-account cooldown is immediately active.
        registerAndCaptureCode(email);

        log.debug("Act: call authService.resendVerification() directly within 60s of OTP issuance");
        // The HTTP integration test for 429 with Retry-After header is covered by
        // AuthControllerResendVerificationTest (WebMvcTest, service stubbed to throw ResendThrottledException).
        // Here we verify the SERVICE correctly throws with a positive retryAfter when the cooldown is active,
        // which is the observable contract. The exception is then translated to 429 by GlobalExceptionHandler.
        com.beautica.common.exception.ResendThrottledException thrown = null;
        try {
            authService.resendVerification(new ResendVerificationRequest(email));
        } catch (com.beautica.common.exception.ResendThrottledException ex) {
            thrown = ex;
        }

        log.debug("Assert: ResendThrottledException thrown with retryAfterSeconds >= 1");
        assertThat(thrown)
                .as("ResendThrottledException must be thrown when cooldown is active")
                .isNotNull();
        assertThat(thrown.getRetryAfterSeconds())
                .as("retryAfterSeconds must be a positive integer indicating the remaining cooldown")
                .isGreaterThanOrEqualTo(1L);
    }

    // ── Test 10 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 400 INVALID_CODE when same code is submitted a second time (anti-enumeration)")
    void should_return400InvalidCode_when_verifyingTwice() throws Exception {
        var email = "double.verify@beautica.test";
        log.debug("Arrange: register email={}, capture OTP, verify once", email);
        String code = registerAndCaptureCode(email);

        ResponseEntity<String> firstVerify = restTemplate.postForEntity(
                "/api/v1/auth/verify-email",
                new VerifyEmailRequest(email, code),
                String.class);
        assertThat(firstVerify.getStatusCode())
                .as("first verify must succeed with 200")
                .isEqualTo(HttpStatus.OK);

        log.debug("Act: POST /api/v1/auth/verify-email with the SAME code a second time");
        ResponseEntity<String> secondVerify = restTemplate.postForEntity(
                "/api/v1/auth/verify-email",
                new VerifyEmailRequest(email, code),
                String.class);

        log.debug("Assert: 400, data.code=INVALID_CODE (anti-enumeration — not ALREADY_VERIFIED)");
        assertThat(secondVerify.getStatusCode())
                .as("second verify for already-verified account must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var apiResponse = objectMapper.readValue(
                secondVerify.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.data().code())
                .as("error code must be INVALID_CODE, not ALREADY_VERIFIED (anti-enumeration per Phase 1.8)")
                .isEqualTo("INVALID_CODE");
    }

    // ── Test 11 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 400 INVALID_CODE when verify-email is called for an unknown email")
    void should_return400InvalidCode_when_emailUnknownOnVerify() throws Exception {
        var email = "unknown.verify@beautica.test";
        log.debug("Arrange: no user registered for email={}", email);

        log.debug("Act: POST /api/v1/auth/verify-email with unregistered email");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-email",
                new VerifyEmailRequest(email, "123456"),
                String.class);

        log.debug("Assert: 400, data.code=INVALID_CODE (anti-enumeration)");
        assertThat(response.getStatusCode())
                .as("verify-email for unknown email must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<VerificationErrorResponse>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.data().code())
                .as("error code must be INVALID_CODE for unknown email (anti-enumeration)")
                .isEqualTo("INVALID_CODE");
    }

    // ── Test 12 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 200 without sending an email when resend-verification is called for an unknown email")
    void should_returnGenericResponse_when_resendForUnknownEmail() throws Exception {
        var email = "unknown.resend@beautica.test";
        log.debug("Arrange: no user registered for email={}", email);

        log.debug("Act: POST /api/v1/auth/resend-verification with unregistered email");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/resend-verification",
                new ResendVerificationRequest(email),
                String.class);

        log.debug("Assert: 200, success=true, no email sent");
        assertThat(response.getStatusCode())
                .as("resend for unknown email must return 200 (anti-enumeration)")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<RegistrationResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().email())
                .as("returned email must match the requested address")
                .isEqualTo(email);

        verify(emailNotificationService, never())
                .sendVerificationEmail(any(), any());
    }
}
