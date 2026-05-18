package com.beautica.auth;

import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.EmailNotificationService;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency regression (QA MEDIUM): two threads submit a wrong code for the
 * SAME account simultaneously. The {@code PESSIMISTIC_WRITE} row lock in
 * {@code findByEmailForUpdate} must serialise the attempt-increment so the
 * counter lands on exactly 2 — no lost update / double-spend.
 *
 * <p>No {@code @Transactional} on the test — each HTTP call is its own
 * transaction that must commit independently (the {@code noRollbackFor}
 * invariant means the increment commits even on {@code INVALID_CODE}).
 * Follows the {@code BookingConcurrencyTest} pattern.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("verify-email — concurrency: parallel wrong-code submissions do not double-spend the attempt counter")
class VerifyEmailConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(VerifyEmailConcurrencyTest.class);

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
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("should_incrementAttemptsExactlyTwice_when_twoParallelWrongCodeSubmissions")
    void should_incrementAttemptsExactlyTwice_when_twoParallelWrongCodeSubmissions() throws Exception {
        var email = "concurrent.verify@beautica.test";
        log.debug("Arrange: register email={} so a verification code exists", email);
        restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, "password123",
                        SelfRegistrationRole.CLIENT, "Anna", "Test", null, null),
                String.class);

        // Two threads, released simultaneously, both submit a wrong code.
        int threads = 2;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        startLatch.await();
                        ResponseEntity<String> r = restTemplate.postForEntity(
                                "/api/v1/auth/verify-email",
                                new VerifyEmailRequest(email, "999999"),
                                String.class);
                        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }, pool);
            }

            startLatch.countDown(); // release both threads at once
            assertThat(doneLatch.await(20, TimeUnit.SECONDS))
                    .as("both verify requests must complete within the timeout")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        Short attempts = jdbcTemplate.queryForObject(
                "SELECT verification_attempts FROM users WHERE email = ?",
                Short.class, email);

        assertThat(attempts)
                .as("the pessimistic row lock must serialise the increment — "
                        + "exactly 2, never 1 (lost update)")
                .isEqualTo((short) 2);
    }
}
