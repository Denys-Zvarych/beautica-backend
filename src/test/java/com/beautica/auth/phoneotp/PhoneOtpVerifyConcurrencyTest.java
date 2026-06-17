package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency regression (security LOW — Phase 13.2 hardening): the per-OTP verify
 * attempt counter must be atomic. {@link PhoneOtpService#verifyOtp} on a wrong code
 * increments {@code attempts} via a single conditional UPDATE
 * ({@link PhoneOtpRepository#recordFailedAttempt(Long, int)}) — never a read-modify-write
 * of a managed entity. This test fires many parallel wrong-code verifies against ONE live
 * OTP row and asserts:
 * <ul>
 *   <li>the persisted {@code attempts} never exceeds the cap
 *       ({@link PhoneOtp#MAX_VERIFY_ATTEMPTS}), so concurrent verifies cannot each read
 *       {@code attempts < cap} and overshoot under last-write-wins; and</li>
 *   <li>the OTP ends up invalidated ({@code used = true}) — a subsequent <i>correct</i>
 *       code is rejected with the same generic 401, never minting a guest token.</li>
 * </ul>
 *
 * <p>Each thread calls the {@code @Transactional} {@code verifyOtp} directly (not over
 * HTTP) so the per-IP Bucket4j verify bucket does not throttle the parallel burst — the
 * counter atomicity is a service/DB-layer property. No {@code @Transactional} on the test
 * itself: every {@code verifyOtp} commits independently. Mirrors
 * {@code PasswordResetConcurrencyTest}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PhoneOtpService — verify attempt counter is atomic under concurrency")
class PhoneOtpVerifyConcurrencyTest extends AbstractIntegrationTest {

    private static final String PHONE = "+380671234567";
    private static final String CORRECT_CODE = "482913";
    private static final String WRONG_CODE = "000000";
    private static final int PARALLEL_VERIFIES = 16;

    @Autowired
    private PhoneOtpService phoneOtpService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private SmsService smsService;

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    @Test
    @DisplayName("should_notExceedAttemptCap_underConcurrentVerify")
    void should_notExceedAttemptCap_underConcurrentVerify() throws Exception {
        jdbc.update(
                "INSERT INTO phone_otps(phone, code_hash, expires_at, used) "
                        + "VALUES (?, ?, now() + interval '10 minutes', false)",
                PHONE, sha256Hex(CORRECT_CODE));

        // Sanity: a single sequential wrong verify must durably bump the counter (the
        // increment commits in a REQUIRES_NEW tx that survives the generic-401 rollback),
        // so the parallel burst below exercises the real wrong-code branch.
        assertThatThrownBy(() -> phoneOtpService.verifyOtp(PHONE, WRONG_CODE))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM phone_otps WHERE phone = ?", Integer.class, PHONE))
                .as("a single wrong verify must bump the atomic attempt counter to 1")
                .isEqualTo(1);

        // Fire many wrong-code verifies simultaneously against the one live OTP row.
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(PARALLEL_VERIFIES);
        var unauthorizedCount = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_VERIFIES);
        try {
            for (int i = 0; i < PARALLEL_VERIFIES; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        startLatch.await();
                        phoneOtpService.verifyOtp(PHONE, WRONG_CODE);
                    } catch (BusinessException e) {
                        // Generic 401 for every wrong code — the expected outcome.
                        unauthorizedCount.incrementAndGet();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }, pool);
            }

            startLatch.countDown(); // release all threads at once
            assertThat(doneLatch.await(20, TimeUnit.SECONDS))
                    .as("all parallel verify attempts must complete within the timeout")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unauthorizedCount.get())
                .as("every wrong-code verify must return the generic 401")
                .isEqualTo(PARALLEL_VERIFIES);

        // The atomic conditional UPDATE must cap the counter: even though more wrong
        // verifies raced than the cap, the persisted attempts can never exceed it.
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM phone_otps WHERE phone = ?", Integer.class, PHONE);
        assertThat(attempts)
                .as("the persisted attempt counter must never exceed the per-OTP cap under concurrency")
                .isLessThanOrEqualTo(PhoneOtp.MAX_VERIFY_ATTEMPTS);

        Boolean used = jdbc.queryForObject(
                "SELECT used FROM phone_otps WHERE phone = ?", Boolean.class, PHONE);
        assertThat(used)
                .as("after exceeding the cap the OTP must be invalidated (attempts=%d)", attempts)
                .isTrue();

        // The CRITICAL contract: once invalidated, even the CORRECT code is rejected and
        // no guest token is ever minted.
        assertThatThrownBy(() -> phoneOtpService.verifyOtp(PHONE, CORRECT_CODE))
                .as("a correct code submitted after the cap must still be rejected (brute-force window collapsed)")
                .isInstanceOf(BusinessException.class);
    }
}
