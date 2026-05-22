package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.user.PasswordResetToken;
import com.beautica.user.PasswordResetTokenRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for the password-reset endpoints.
 *
 * <p>Uses the shared singleton Testcontainers Postgres from {@link AbstractIntegrationTest}.
 * {@code EmailNotificationService} is a {@code @MockBean} there — no SMTP connection needed;
 * we verify calls on the mock to confirm emails are sent or suppressed.
 *
 * <p>All actions go through the real HTTP stack to exercise the full Spring Security +
 * transaction + rate-limit filter chain, not just the service in isolation.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Password-reset endpoints — integration")
class PasswordResetControllerIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetControllerIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TokenGenerator tokenGenerator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Resets mock invocation counts before every test.
     *
     * <p>{@code @MockBean} creates a shared Mockito mock for the entire Spring context.
     * Without clearing, {@code verify(emailNotificationService)} in test N+1 sees the
     * cumulative invocations from tests 1..N and fails with "wanted 1, was 2" etc.
     * {@link Mockito#clearInvocations} resets only the invocation log — stubbing is kept.
     */
    @BeforeEach
    void resetMockInvocations() {
        Mockito.clearInvocations(emailNotificationService);
    }

    // =========================================================================
    // POST /api/v1/auth/forgot-password
    // =========================================================================

    @Test
    @DisplayName("should return 200 and send email when email belongs to active+verified user")
    void should_return200AndSendEmail_when_emailBelongsToVerifiedActiveUser() throws Exception {
        String email = "reset.valid@beautica.com";
        log.debug("Arrange: register and verify email={}", email);
        registerAndVerify(email, "Password1!");

        log.debug("Act: POST /auth/forgot-password for valid user");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.message()).isNotBlank();

        // A token row must have been persisted.
        assertThat(passwordResetTokenRepository.findAll()).isNotEmpty();
        // Email must have been dispatched.
        verify(emailNotificationService).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("should return 200 with identical body and send no email when email is unknown (enumeration protection)")
    void should_return200AndSendNothing_when_emailUnknown() throws Exception {
        log.debug("Arrange: no user in DB");

        ResponseEntity<String> unknownResp = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest("unknown-nobody@beautica.com"),
                String.class);
        ResponseEntity<String> alsoUnknownResp = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest("also-unknown@beautica.com"),
                String.class);

        assertThat(unknownResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Bodies must be identical — no distinguishing signal.
        assertThat(unknownResp.getBody()).isEqualTo(alsoUnknownResp.getBody());

        assertThat(passwordResetTokenRepository.findAll()).isEmpty();
        verify(emailNotificationService, never()).sendPasswordResetEmail(anyString(), anyString());
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

    @Test
    @DisplayName("should return 400 when email field is malformed")
    void should_return400_when_emailIsMalformed() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest("not-an-email"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // POST /api/v1/auth/reset-password
    // =========================================================================

    @Test
    @DisplayName("should return 200 and change password when token is valid")
    void should_return200AndChangePassword_when_tokenIsValid() throws Exception {
        String email = "reset.confirm@beautica.com";
        String oldPassword = "OldPassword1!";
        String newPassword = "NewPassword99!";
        log.debug("Arrange: register, verify, request reset for email={}", email);
        registerAndVerify(email, oldPassword);

        // Capture the reset URL from the (mocked) email send call.
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email), String.class);
        verify(emailNotificationService).sendPasswordResetEmail(anyString(), urlCaptor.capture());
        String rawToken = extractRawTokenFromUrl(urlCaptor.getValue());

        log.debug("Act: POST /auth/reset-password with captured raw token");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawToken, newPassword),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data()).isNull();

        // Old password must no longer authenticate.
        ResponseEntity<String> loginOld = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, oldPassword), String.class);
        assertThat(loginOld.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // New password must authenticate.
        ResponseEntity<String> loginNew = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, newPassword), String.class);
        assertThat(loginNew.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("should return 400 on second submission of same token (single-use)")
    void should_return400_when_tokenSubmittedTwice() throws Exception {
        String email = "reset.singleuse@beautica.com";
        registerAndVerify(email, "Password1!");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email), String.class);
        verify(emailNotificationService).sendPasswordResetEmail(anyString(), urlCaptor.capture());
        String rawToken = extractRawTokenFromUrl(urlCaptor.getValue());

        // First use succeeds.
        restTemplate.postForEntity("/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawToken, "NewPassword99!"), String.class);

        // Second use must be rejected.
        ResponseEntity<String> secondResp = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawToken, "AnotherPassword1!"),
                String.class);

        assertThat(secondResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(secondResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.message()).isEqualTo("Invalid or expired reset token");
    }

    @Test
    @DisplayName("should return 400 with generic message for a random unknown token")
    void should_return400WithGenericMessage_when_tokenIsUnknown() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest("completely-random-nonexistent-token", "ValidPassword1!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Invalid or expired reset token");
    }

    @Test
    @DisplayName("should return 400 with generic message for an expired token (full HTTP stack)")
    void should_return400WithGenericMessage_when_tokenExpired() throws Exception {
        String email = "reset.expired@beautica.com";
        log.debug("Arrange: register+verify email={} then persist a token row already expired", email);
        registerAndVerify(email, "Password1!");

        // Persist a reset-token row whose expires_at is in the past. The DB stores the
        // SHA-256 hash; the raw token is what the client submits over HTTP.
        String rawToken = "expired-raw-token-" + java.util.UUID.randomUUID();
        String hashedToken = tokenGenerator.hash(rawToken);
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findByEmail(email).orElseThrow();
            passwordResetTokenRepository.save(new PasswordResetToken(
                    hashedToken, user.getId(), Instant.now().minus(2, ChronoUnit.HOURS)));
        });

        log.debug("Act: POST /auth/reset-password with the expired raw token");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawToken, "NewPassword99!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        // Same generic message as used/unknown — the expired branch must not be distinguishable.
        assertThat(body.message()).isEqualTo("Invalid or expired reset token");
    }

    @Test
    @DisplayName("should return 400 when new password is too short (bean validation)")
    void should_return400_when_passwordTooShort() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest("some-valid-length-token", "short"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should return 400 when token field is blank (bean validation)")
    void should_return400_when_tokenIsBlank() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest("", "ValidPassword1!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should revoke all refresh tokens after successful reset (global logout)")
    void should_revokeAllRefreshTokens_when_resetSucceeds() throws Exception {
        String email = "reset.sessions@beautica.com";
        registerAndVerify(email, "Password1!");

        // Log in once to create a refresh token.
        restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "Password1!"), String.class);

        // Issue and complete a reset.
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email), String.class);
        verify(emailNotificationService).sendPasswordResetEmail(anyString(), urlCaptor.capture());
        String rawToken = extractRawTokenFromUrl(urlCaptor.getValue());

        restTemplate.postForEntity("/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawToken, "NewPassword99!"), String.class);

        // No refresh tokens must remain for this user.
        var user = userRepository.findByEmail(email).orElseThrow();
        long remaining = transactionTemplate.execute(status ->
                refreshTokenRepository.countByUserId(user.getId()));
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("should not return auth tokens in reset-password response (no auto-login)")
    void should_returnNoAuthTokens_when_resetSucceeds() throws Exception {
        String email = "reset.nologin@beautica.com";
        registerAndVerify(email, "Password1!");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email), String.class);
        verify(emailNotificationService).sendPasswordResetEmail(anyString(), urlCaptor.capture());
        String rawToken = extractRawTokenFromUrl(urlCaptor.getValue());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawToken, "NewPassword99!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        // data must be null — no access/refresh tokens embedded.
        assertThat(body.data()).isNull();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Registers a CLIENT user and immediately marks their email verified in the DB. */
    private void registerAndVerify(String email, String password) {
        restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, "Test", "User", null, null),
                String.class);
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findByEmail(email).ifPresent(u -> {
                    u.setEmailVerified(true);
                    userRepository.save(u);
                }));
    }

    /**
     * Extracts the raw reset token from a reset URL of the form
     * {@code https://host/reset-password?token=<rawToken>}.
     */
    private static String extractRawTokenFromUrl(String resetUrl) {
        int idx = resetUrl.indexOf("?token=");
        if (idx < 0) {
            throw new AssertionError("Reset URL missing ?token= param: " + resetUrl);
        }
        String encoded = resetUrl.substring(idx + 7);
        return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }
}
