package com.beautica.auth;

import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.EmailNotificationService;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * Concurrency regression (QA HIGH): two threads submit the SAME valid raw token to
 * {@code POST /api/v1/auth/reset-password} simultaneously. The {@code PESSIMISTIC_WRITE}
 * row lock in {@code findByTokenForUpdate} must serialise the two attempts so the
 * single-use guarantee holds — exactly ONE succeeds (200) and the other gets the
 * generic 400, with the token marked used exactly once.
 *
 * <p>No {@code @Transactional} on the test — each HTTP call is its own transaction
 * that commits independently. Mirrors {@link VerifyEmailConcurrencyTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("reset-password — concurrency: parallel same-token submissions honour single-use")
class PasswordResetConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetConcurrencyTest.class);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmailNotificationService emailNotificationService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM password_reset_tokens");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("should_succeedExactlyOnce_when_twoParallelSubmissionsOfSameToken")
    void should_succeedExactlyOnce_when_twoParallelSubmissionsOfSameToken() throws Exception {
        String email = "concurrent.reset@beautica.test";
        log.debug("Arrange: register+verify email={} and issue a reset token", email);
        registerAndVerify(email);

        // Capture the raw reset token from the (mocked) email send.
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email), String.class);
        verify(emailNotificationService).sendPasswordResetEmail(anyString(), urlCaptor.capture());
        String rawToken = extractRawTokenFromUrl(urlCaptor.getValue());

        // Two threads, released simultaneously, both submit the SAME valid token.
        int threads = 2;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threads);
        var successCount = new AtomicInteger(0);
        var badRequestCount = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        startLatch.await();
                        ResponseEntity<String> r = restTemplate.postForEntity(
                                "/api/v1/auth/reset-password",
                                new ResetPasswordRequest(rawToken, "NewPassword99!"),
                                String.class);
                        if (r.getStatusCode() == HttpStatus.OK) {
                            successCount.incrementAndGet();
                        } else if (r.getStatusCode() == HttpStatus.BAD_REQUEST) {
                            badRequestCount.incrementAndGet();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }, pool);
            }

            startLatch.countDown(); // release both threads at once
            assertThat(doneLatch.await(20, TimeUnit.SECONDS))
                    .as("both reset requests must complete within the timeout")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successCount.get())
                .as("the pessimistic row lock must let exactly ONE submission succeed (single-use)")
                .isEqualTo(1);
        assertThat(badRequestCount.get())
                .as("the losing submission must receive the generic 400")
                .isEqualTo(1);

        // The token must be marked used exactly once — never two live consumptions.
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE is_used = true",
                Integer.class);
        assertThat(usedCount)
                .as("exactly one token row must be marked used after the race")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Registers a CLIENT user and marks their email verified directly in the DB. */
    private void registerAndVerify(String email) {
        restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, "Password1!",
                        SelfRegistrationRole.CLIENT, "Anna", "Test", null, null),
                String.class);
        jdbcTemplate.update("UPDATE users SET email_verified = true WHERE email = ?", email);
    }

    private static String extractRawTokenFromUrl(String resetUrl) {
        int idx = resetUrl.indexOf("?token=");
        if (idx < 0) {
            throw new AssertionError("Reset URL missing ?token= param: " + resetUrl);
        }
        String encoded = resetUrl.substring(idx + 7);
        return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }
}
