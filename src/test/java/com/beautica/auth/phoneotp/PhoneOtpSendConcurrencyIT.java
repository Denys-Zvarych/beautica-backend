package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsDeliveryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Real-Postgres coverage of {@link PhoneOtpService#sendOtp}: the per-phone rate-limit cap holds under
 * concurrent sends (advisory lock), a failed send leaves the previous code verifiable, and a
 * successful send invalidates it. Threads call the service directly so the per-IP Bucket4j layer does
 * not throttle the burst. No {@code @Transactional} on the test: every send commits independently.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
@Import(TestSecurityConfig.class)
@DisplayName("PhoneOtpService.sendOtp — concurrency and code lifecycle (real Postgres)")
class PhoneOtpSendConcurrencyIT extends AbstractIntegrationTest {

    private static final String PHONE = "+380671234567";
    private static final String OLD_CODE = "111111";
    private static final int PARALLEL_SENDS = 8;
    private static final int CAP = 3;

    @Autowired
    private PhoneOtpService phoneOtpService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PhoneOtpRepository phoneOtpRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private OtpSmsSender otpSmsSender;

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private void insertOldCode() throws Exception {
        jdbc.update("INSERT INTO phone_otps(phone, code_hash, expires_at, used, created_at) "
                        + "VALUES (?, ?, now() + interval '9 minutes', false, now() - interval '1 minute')",
                PHONE, sha256Hex(OLD_CODE));
    }

    @Test
    @DisplayName("concurrent sends for one phone stop at the cap of 3")
    void should_allowAtMostCapSends_when_concurrentSendsForSamePhone() throws Exception {
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(PARALLEL_SENDS);
        var succeeded = new AtomicInteger();
        var tooMany = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_SENDS);
        try {
            for (int i = 0; i < PARALLEL_SENDS; i++) {
                pool.execute(() -> {
                    try {
                        startLatch.await();
                        phoneOtpService.sendOtp(PHONE);
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                            tooMany.incrementAndGet();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).as("sends that passed the cap").isEqualTo(CAP);
        assertThat(tooMany.get()).isEqualTo(PARALLEL_SENDS - CAP);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_otps WHERE phone = ?", Integer.class, PHONE))
                .as("persisted rows never exceed the per-window cap").isEqualTo(CAP);
        Mockito.verify(otpSmsSender, Mockito.times(CAP)).send(eq(PHONE), anyString());
    }

    @Test
    @DisplayName("B516: concurrent signed-in sends of ONE account over different phones still stop at the account cap of 3")
    void should_allowAtMostCapSends_when_concurrentSendsOfOneAccountOverDifferentPhones() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                + "VALUES (?, ?, 'x', 'CLIENT', true, true)", owner, "acct-cap-" + owner + "@beautica.test");
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(PARALLEL_SENDS);
        var succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_SENDS);
        try {
            for (int i = 0; i < PARALLEL_SENDS; i++) {
                String phone = "+38050" + String.format("%07d", 1_000_000 + i);
                pool.execute(() -> {
                    try {
                        startLatch.await();
                        phoneOtpService.sendCode(phone, null, PhoneOtpPurpose.PHONE_ADD, owner);
                        succeeded.incrementAndGet();
                    } catch (BusinessException ignoredCapped) {
                        // 429 — counted by the assertions below via the persisted rows
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).as("sends that passed the per-account cap").isEqualTo(CAP);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_otps WHERE user_id = ?", Integer.class, owner))
                .as("persisted rows never exceed the per-account cap").isEqualTo(CAP);
    }

    @Test
    @DisplayName("the per-phone cap counts every purpose together, so a guest + registration mix still stops at 3 SMS")
    void should_throw429_when_fourthSendOfAnyPurposeMixWithinWindow() {
        String phone = "+380671234599";
        phoneOtpService.sendCode(phone, null, PhoneOtpPurpose.GUEST_BOOKING);
        phoneOtpService.sendCode(phone, null, PhoneOtpPurpose.REGISTRATION);
        phoneOtpService.sendCode(phone, null, PhoneOtpPurpose.GUEST_BOOKING);

        assertThatThrownBy(() -> phoneOtpService.sendCode(phone, null, PhoneOtpPurpose.REGISTRATION))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> phoneOtpService.sendCode(phone, null, PhoneOtpPurpose.PASSWORD_RESET))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        Mockito.verify(otpSmsSender, Mockito.times(CAP)).send(eq(phone), anyString());
    }

    @Test
    @DisplayName("a failed new send leaves the previous code verifiable")
    void should_keepPreviousCodeVerifiable_when_newSendFails() throws Exception {
        insertOldCode();
        doThrow(new SmsDeliveryException("down")).when(otpSmsSender).send(eq(PHONE), anyString());

        assertThatThrownBy(() -> phoneOtpService.sendOtp(PHONE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_otps WHERE phone = ?", Integer.class, PHONE))
                .as("the undelivered row is kept (counts toward the cap)").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM phone_otps WHERE phone = ? AND used = false", Integer.class, PHONE))
                .as("the undelivered row is burnt, only the old code stays unused").isEqualTo(1);
        assertThat(phoneOtpService.verifyOtp(PHONE, OLD_CODE))
                .as("the previous code is still redeemable").isNotBlank();
    }

    @Test
    @DisplayName("three failed sends still count: the fourth is 429")
    void should_return429OnFourthSend_when_threeSendsFailed() {
        doThrow(new SmsDeliveryException("down")).when(otpSmsSender).send(eq(PHONE), anyString());

        for (int i = 0; i < CAP; i++) {
            assertThatThrownBy(() -> phoneOtpService.sendOtp(PHONE))
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        assertThatThrownBy(() -> phoneOtpService.sendOtp(PHONE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Mockito.verify(otpSmsSender, Mockito.times(CAP)).send(eq(PHONE), anyString());
    }

    @Test
    @DisplayName("an older send's late invalidation never kills the newest code")
    void should_keepNewestCodeVerifiable_when_olderSendInvalidatesAfterNewerSend() throws Exception {
        long idA = insertCode("222222");
        long idB = insertCode("333333");

        // A's post-delivery invalidation runs AFTER B committed its row.
        new TransactionTemplate(txManager).executeWithoutResult(s -> phoneOtpRepository.markOlderAsUsed(PHONE, PhoneOtpPurpose.GUEST_BOOKING, idA));

        assertThat(jdbc.queryForObject("SELECT used FROM phone_otps WHERE id = ?", Boolean.class, idB))
                .as("the newer row is untouched by the older send").isFalse();
        assertThat(phoneOtpService.verifyOtp(PHONE, "333333")).isNotBlank();
    }

    @Test
    @DisplayName("the higher-id row wins even with an older created_at")
    void should_verifyHigherIdRow_when_higherIdHasOlderCreatedAt() throws Exception {
        // Clock skew between instances: the higher-id (newer) row carries the OLDER created_at.
        jdbc.update("INSERT INTO phone_otps(phone, code_hash, expires_at, used, created_at) "
                        + "VALUES (?, ?, now() + interval '9 minutes', false, now())",
                PHONE, sha256Hex("444444"));
        jdbc.update("INSERT INTO phone_otps(phone, code_hash, expires_at, used, created_at) "
                        + "VALUES (?, ?, now() + interval '9 minutes', false, now() - interval '5 minutes')",
                PHONE, sha256Hex("555555"));

        assertThat(phoneOtpService.verifyOtp(PHONE, "555555"))
                .as("newest is chosen by id, not created_at").isNotBlank();
    }

    private long insertCode(String code) throws Exception {
        return jdbc.queryForObject("INSERT INTO phone_otps(phone, code_hash, expires_at, used, created_at) "
                        + "VALUES (?, ?, now() + interval '9 minutes', false, now()) RETURNING id",
                Long.class, PHONE, sha256Hex(code));
    }

    @Test
    @DisplayName("a delivered new send invalidates the previous code")
    void should_invalidatePreviousCode_when_newSendSucceeds() throws Exception {
        insertOldCode();

        phoneOtpService.sendOtp(PHONE);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).send(eq(PHONE), text.capture());
        String newCode = text.getValue().replaceAll(".*?(\\d{6}).*", "$1");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM phone_otps WHERE phone = ? AND used = false", Integer.class, PHONE))
                .as("only the new code stays unused").isEqualTo(1);
        assertThatThrownBy(() -> phoneOtpService.verifyOtp(PHONE, OLD_CODE))
                .isInstanceOf(BusinessException.class);
        assertThat(phoneOtpService.verifyOtp(PHONE, newCode)).isNotBlank();
    }
}
