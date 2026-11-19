package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone-first hardening audit-fix 1 — the attempt bookkeeping no longer takes a second connection of the MAIN pool:
 * 12 parallel wrong verifies against the production main-pool size (10) all finish quickly with the generic 401 (no
 * {@code CannotCreateTransactionException} from a pool stall), and the per-code cap still holds. The reservation and
 * mismatch run as autocommit statements on the dedicated 2-connection {@code otpAttemptDataSource}.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=10")
@Import(TestSecurityConfig.class)
@DisplayName("Phone OTP verify — no main-pool stall under parallel verifies (audit-fix 1)")
class PhoneOtpAttemptPoolIT extends AbstractIntegrationTest {

    private static final String PHONE = "+380671140001";
    private static final String CORRECT_CODE = "482913";
    private static final String WRONG_CODE = "000000";
    private static final int PARALLEL = 12;
    private static final Duration BUDGET = Duration.ofSeconds(2);

    @Autowired
    private PhoneOtpService phoneOtpService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private OtpSmsSender otpSmsSender;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("should_finishAllWithin2sAndHoldCap_when_12ParallelWrongVerifiesOn10ConnectionPool")
    void should_finishAllWithin2sAndHoldCap_when_12ParallelWrongVerifiesOn10ConnectionPool() throws Exception {
        jdbc.update("INSERT INTO phone_otps (phone, code_hash, expires_at, used) "
                + "VALUES (?, ?, now() + interval '10 minutes', false)", PHONE, sha256Hex(CORRECT_CODE));
        phoneOtpService.consumeCode("+380671140002", WRONG_CODE, PhoneOtpPurpose.GUEST_BOOKING); // warm-up
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < PARALLEL; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        phoneOtpService.verifyOtp(PHONE, WRONG_CODE);
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            long began = System.nanoTime();
            start.countDown();
            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - began);

            assertThat(elapsed).as("12 parallel verifies on a 10-connection pool").isLessThan(BUDGET);
            assertThat(results).as("every verify is the generic 401, none a pool error")
                    .allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(BusinessException.class,
                            b -> assertThat(b.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED)));
        } finally {
            pool.shutdownNow();
        }

        Integer attempts = jdbc.queryForObject("SELECT attempts FROM phone_otps WHERE phone = ?", Integer.class, PHONE);
        Boolean used = jdbc.queryForObject("SELECT used FROM phone_otps WHERE phone = ?", Boolean.class, PHONE);
        assertThat(attempts).as("reserved compares never exceed the cap").isEqualTo(PhoneOtp.MAX_VERIFY_ATTEMPTS);
        assertThat(used).as("the code is burnt at the cap").isTrue();
    }

    @Test
    @DisplayName("should_registerHikariMeters_when_otpAttemptPoolBuilt")
    void should_registerHikariMeters_when_otpAttemptPoolBuilt() {
        var connections = meterRegistry.find("hikaricp.connections").tag("pool", "otp-attempt-pool").gauge();
        var maxConnections = meterRegistry.find("hikaricp.connections.max").tag("pool", "otp-attempt-pool").gauge();

        assertThat(connections).as("hikaricp.connections{pool=otp-attempt-pool}").isNotNull();
        assertThat(maxConnections).isNotNull();
        assertThat(maxConnections.value()).isEqualTo(2);
    }
}
