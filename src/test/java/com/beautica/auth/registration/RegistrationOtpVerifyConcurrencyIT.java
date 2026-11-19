package com.beautica.auth.registration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.PhoneOtp;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone-first hardening B1 (S1 race) — the registration verify redeems a code race-free without {@code FOR UPDATE}:
 * a compare is reserved by a committed conditional UPDATE ({@code attempts < cap}), and a match claims the row with
 * {@code used = false} in the WHERE clause. Parallel correct verifies therefore mint exactly one ticket, and a burst of
 * wrong + correct verifies at the cap never runs more compares than the cap.
 *
 * <p>Calls the {@code @Transactional} service directly from N threads (no per-IP HTTP bucket in the way); no
 * transaction on the test itself. Same context as {@code PhoneOtpVerifyConcurrencyTest} (pool 40: each verify holds
 * its own connection plus one REQUIRES_NEW connection).
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
@Import(TestSecurityConfig.class)
@DisplayName("Registration OTP verify — race-free redeem under concurrency (B1)")
class RegistrationOtpVerifyConcurrencyIT extends AbstractIntegrationTest {

    private static final String CORRECT_CODE = "482913";
    private static final String WRONG_CODE = "000000";
    private static final int PARALLEL = 8;

    @Autowired
    private RegistrationPhoneService registrationPhoneService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private OtpSmsSender otpSmsSender;

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void insertLiveCode(String phone, int attempts) throws Exception {
        jdbc.update("INSERT INTO phone_otps(phone, code_hash, expires_at, used, purpose, attempts) "
                        + "VALUES (?, ?, now() + interval '10 minutes', false, 'REGISTRATION', ?)",
                phone, sha256Hex(CORRECT_CODE), attempts);
    }

    /** Runs every task at once (start latch) and returns their outcomes: the ticket, or the thrown exception. */
    private List<Object> runTogether(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("should_mintExactlyOneTicket_when_parallelCorrectVerifies")
    void should_mintExactlyOneTicket_when_parallelCorrectVerifies() throws Exception {
        String phone = "+380671110001";
        insertLiveCode(phone, 0);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            tasks.add(() -> registrationPhoneService.verifyCode(phone, CORRECT_CODE));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn(String.class::isInstance).as("tickets minted").hasSize(1);
        assertThat(outcomes).filteredOn(VerificationException.class::isInstance)
                .as("every loser gets the generic INVALID_CODE").hasSize(PARALLEL - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM phone_registration_tickets WHERE phone = ?",
                Integer.class, phone)).isOne();
        assertThat(jdbc.queryForObject("SELECT used FROM phone_otps WHERE phone = ?", Boolean.class, phone)).isTrue();
    }

    @Test
    @DisplayName("should_neverCompareMoreThanCap_when_parallelWrongAndCorrectAtCap")
    void should_neverCompareMoreThanCap_when_parallelWrongAndCorrectAtCap() throws Exception {
        String phone = "+380671110002";
        int cap = PhoneOtp.MAX_VERIFY_ATTEMPTS;
        insertLiveCode(phone, cap - 1); // one compare left
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < PARALLEL - 1; i++) {
            tasks.add(() -> registrationPhoneService.verifyCode(phone, WRONG_CODE));
        }
        tasks.add(() -> registrationPhoneService.verifyCode(phone, CORRECT_CODE));

        List<Object> outcomes = runTogether(tasks);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempts, failed_attempts, used FROM phone_otps WHERE phone = ?", phone);
        assertThat((Integer) row.get("attempts")).as("compares reserved never exceed the cap").isEqualTo(cap);
        assertThat((Integer) row.get("failed_attempts")).as("at most the one remaining compare can fail")
                .isLessThanOrEqualTo(1);
        assertThat(row.get("used")).as("the last compare spent the code either way").isEqualTo(true);
        assertThat(outcomes).filteredOn(String.class::isInstance).as("at most one ticket").hasSizeLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM phone_registration_tickets WHERE phone = ?",
                Integer.class, phone)).isLessThanOrEqualTo(1);
    }
}
