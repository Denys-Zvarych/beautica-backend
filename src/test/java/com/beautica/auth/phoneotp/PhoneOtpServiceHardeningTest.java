package com.beautica.auth.phoneotp;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.config.DeployEnvironment;
import com.beautica.config.DeploymentProperties;
import com.beautica.config.SmsMode;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsDeliveryAmbiguousException;
import com.beautica.notification.sms.SmsDeliveryException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phone-first hardening — {@link PhoneOtpService}: the per-phone 10-per-24 h failed-verify cap (AUTH-D26 c), the 60 s
 * per-phone+purpose cooldown (AUTH-D27 a), the forgot-password slot burn (AUTH-D27 c), the race-free redeem (B1) and
 * the {@code beautica.otp.*} funnel counters (B7). Collaborators mocked, clock pinned.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhoneOtpService — phone-first hardening (AUTH-D26, AUTH-D27, B1, B7)")
class PhoneOtpServiceHardeningTest {

    private static final String PHONE = "+380671234567";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String CODE = "482913";

    @Mock private PhoneOtpRepository phoneOtpRepository;
    @Mock private PhoneOtpAttemptRecorder attemptRecorder;
    @Mock private GuestTokenProvider guestTokenProvider;
    @Mock private OtpSmsSender otpSmsSender;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private PhoneOtpService service;

    @BeforeEach
    void setUp() {
        service = new PhoneOtpService(phoneOtpRepository, attemptRecorder, guestTokenProvider, otpSmsSender,
                Clock.fixed(NOW, ZoneOffset.UTC), new RecordingTransactionManager(),
                new DeploymentProperties(DeployEnvironment.LOCAL, new DeploymentProperties.Sms(SmsMode.TURBOSMS, null)),
                meterRegistry);
        // Audit-fix: every send reads its limits from ONE aggregate row; none recorded unless a test says otherwise.
        lenient().when(phoneOtpRepository.sendLimitStats(any(), any(), any(), any(), any(), any(),
                any())).thenReturn(SendLimitStatsFixture.NONE);
    }

    private static final String NET = OtpClientNetwork.UNKNOWN; // unit tests bind no request

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private PhoneOtp liveRegistrationCode(long id) throws Exception {
        PhoneOtp otp = PhoneOtp.issue(PHONE, sha256Hex(CODE), NOW.plusSeconds(600), PhoneOtpPurpose.REGISTRATION, null);
        ReflectionTestUtils.setField(otp, "id", id);
        when(phoneOtpRepository.findTopByPhoneAndPurposeAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
                PHONE, PhoneOtpPurpose.REGISTRATION, NOW)).thenReturn(Optional.of(otp));
        return otp;
    }

    /** {@code count} wrong codes for PHONE from this test's network in the last 24 h (verify and send reads). */
    private void failedVerifiesInWindow(long count) {
        lenient().when(phoneOtpRepository.countFailuresSince(PHONE, NET,
                NOW.minus(Duration.ofHours(24)))).thenReturn(count);
        lenient().when(phoneOtpRepository.sendLimitStats(eq(PHONE), any(), eq(NET), any(), any(),
                any(), eq(NOW.minus(Duration.ofHours(24))))).thenReturn(new SendLimitStatsFixture(0, null, count));
    }

    /** Emulates the aggregate: the newest REGISTRATION code counts only when created after the cooldown start. */
    private void latestCodeCreatedAt(Instant createdAt) {
        stubLatestCode(PhoneOtpPurpose.REGISTRATION, createdAt);
    }

    private void stubLatestCode(PhoneOtpPurpose purpose, Instant createdAt) {
        when(phoneOtpRepository.sendLimitStats(eq(PHONE), eq(purpose.name()), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SendLimitStatsFixture(0,
                        createdAt.isAfter(inv.getArgument(5, Instant.class)) ? createdAt.toEpochMilli() : null, 0));
    }

    private double count(String metric, PhoneOtpPurpose purpose, String result) {
        var counter = meterRegistry.find(metric).tag("purpose", purpose.name().toLowerCase())
                .tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    // ── AUTH-D26 (c) — 10 failed verifies per phone per 24 h ─────────────────────────────

    @Test
    @DisplayName("9 failed verifies in the last 24 h — the code is still compared")
    void should_compareCode_when_ninthFailureOnRecord() throws Exception {
        failedVerifiesInWindow(9);
        PhoneOtp otp = liveRegistrationCode(5L);
        when(attemptRecorder.reserveAttempt(5L, PhoneOtp.MAX_VERIFY_ATTEMPTS)).thenReturn(true);
        when(phoneOtpRepository.markUsedIfUnused(otp.getId())).thenReturn(1);

        boolean redeemed = service.consumeCode(PHONE, CODE, PhoneOtpPurpose.REGISTRATION);

        assertThat(redeemed).as("the 10th verify still runs: 9 failures are under the cap").isTrue();
    }

    @Test
    @DisplayName("10 failed verifies in the last 24 h — verify refused without touching the code row")
    void should_refuseWithoutTouchingRow_when_tenFailuresInLast24h() {
        failedVerifiesInWindow(10);

        boolean redeemed = service.consumeCode(PHONE, CODE, PhoneOtpPurpose.REGISTRATION);

        assertThat(redeemed).as("the 11th verify fails even with the right code").isFalse();
        verify(phoneOtpRepository, never())
                .findTopByPhoneAndPurposeAndUsedFalseAndExpiresAtAfterOrderByIdDesc(any(), any(), any());
        verifyNoInteractions(attemptRecorder);
        assertThat(count(PhoneOtpService.VERIFY_METRIC, PhoneOtpPurpose.REGISTRATION, "daily_cap")).isEqualTo(1);
    }

    @Test
    @DisplayName("10 failed verifies in the last 24 h — a user-bound verify is refused too")
    void should_refuseUserBoundVerify_when_tenFailuresInLast24h() {
        failedVerifiesInWindow(10);
        java.util.UUID owner = java.util.UUID.randomUUID();

        boolean redeemed = service.consumeCode(PHONE, CODE, PhoneOtpPurpose.PHONE_ADD, owner);

        assertThat(redeemed).isFalse();
        verifyNoInteractions(attemptRecorder);
    }

    @Test
    @DisplayName("10 failed verifies in the last 24 h — a new send is 429 and no SMS goes out")
    void should_return429AndSendNothing_when_tenFailuresInLast24h() {
        failedVerifiesInWindow(10);

        assertThatThrownBy(() -> service.sendCode(PHONE, null, PhoneOtpPurpose.REGISTRATION))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(otpSmsSender, never()).send(anyString(), anyString());
        verify(phoneOtpRepository, never()).save(any());
        assertThat(count(PhoneOtpService.SEND_METRIC, PhoneOtpPurpose.REGISTRATION, "rate_limited")).isEqualTo(1);
    }

    // ── AUTH-D27 (a) — 60 s cooldown per phone + purpose ─────────────────────────────────

    @Test
    @DisplayName("same purpose re-requested after 59 s — 429 with Retry-After (60 s cooldown)")
    void should_return429WithRetryAfter_when_sameCodeRequested59sLater() {
        latestCodeCreatedAt(NOW.minusSeconds(59));

        assertThatThrownBy(() -> service.sendCode(PHONE, null, PhoneOtpPurpose.REGISTRATION))
                .isInstanceOfSatisfying(ResendThrottledException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1));

        verify(otpSmsSender, never()).send(anyString(), anyString());
        assertThat(count(PhoneOtpService.SEND_METRIC, PhoneOtpPurpose.REGISTRATION, "cooldown")).isEqualTo(1);
    }

    @Test
    @DisplayName("same purpose re-requested after 61 s — the code is sent")
    void should_sendCode_when_sameCodeRequested61sLater() {
        latestCodeCreatedAt(NOW.minusSeconds(61));

        service.sendCode(PHONE, null, PhoneOtpPurpose.REGISTRATION);

        verify(otpSmsSender).send(eq(PHONE), anyString());
        assertThat(count(PhoneOtpService.SEND_METRIC, PhoneOtpPurpose.REGISTRATION, "ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("code sent this instant — Retry-After asks for the whole 60 s cooldown")
    void should_askForWholeCooldown_when_codeJustSent() {
        latestCodeCreatedAt(NOW.minusMillis(200));

        assertThatThrownBy(() -> service.sendCode(PHONE, null, PhoneOtpPurpose.REGISTRATION))
                .isInstanceOfSatisfying(ResendThrottledException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60));
    }

    @Test
    @DisplayName("cooldown lookup is scoped to the same purpose only")
    void should_lookUpCooldownForSamePurposeOnly_when_sending() {
        service.sendCode(PHONE, null, PhoneOtpPurpose.GUEST_BOOKING);

        verify(phoneOtpRepository).sendLimitStats(PHONE, PhoneOtpPurpose.GUEST_BOOKING.name(), NET,
                NOW.minus(Duration.ofMinutes(15)), NOW.minus(Duration.ofMinutes(15)), NOW.minusSeconds(60),
                NOW.minus(Duration.ofHours(24)));
    }

    // ── AUTH-D26 (b) — reset TTL 10 min ────────────────────────────────────────────────

    @Test
    @DisplayName("a password-reset code expires exactly 10 minutes after issue")
    void should_expireAtExactly10Minutes_when_passwordResetCodeIssued() {
        service.sendCode(PHONE, null, PhoneOtpPurpose.PASSWORD_RESET);

        ArgumentCaptor<PhoneOtp> saved = ArgumentCaptor.forClass(PhoneOtp.class);
        verify(phoneOtpRepository).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    // ── AUTH-D27 (c) — forgot-password slot burn ───────────────────────────────────────

    @Test
    @DisplayName("forgot-password slot burn stores a used row under the phone lock when a slot is free")
    void should_storeBurnedRowUnderPhoneLock_when_slotFree() {
        service.burnSlot(PHONE, PhoneOtpPurpose.PASSWORD_RESET);

        ArgumentCaptor<PhoneOtp> saved = ArgumentCaptor.forClass(PhoneOtp.class);
        InOrder order = inOrder(phoneOtpRepository);
        order.verify(phoneOtpRepository).lockPhoneForSend(PHONE);
        order.verify(phoneOtpRepository).sendLimitStats(eq(PHONE), any(), any(), any(), any(), any(), any());
        order.verify(phoneOtpRepository).save(saved.capture());
        assertThat(saved.getValue().isUsed()).as("a burned row can never be redeemed").isTrue();
        assertThat(saved.getValue().getPurpose()).isEqualTo(PhoneOtpPurpose.PASSWORD_RESET);
        assertThat(saved.getValue().getCodeHash()).hasSize(64);
        verifyNoInteractions(otpSmsSender);
    }

    @Test
    @DisplayName("slot burn stores nothing and stays silent once the window cap is reached")
    void should_storeNothingSilently_when_windowCapReached() {
        when(phoneOtpRepository.sendLimitStats(eq(PHONE), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SendLimitStatsFixture(3, null, 0));

        assertThatCode(() -> service.burnSlot(PHONE, PhoneOtpPurpose.PASSWORD_RESET)).doesNotThrowAnyException();

        verify(phoneOtpRepository, never()).save(any());
    }

    @Test
    @DisplayName("slot burn stores nothing and stays silent while the cooldown runs")
    void should_storeNothingSilently_when_cooldownRunning() {
        stubLatestCode(PhoneOtpPurpose.PASSWORD_RESET, NOW.minusSeconds(10));

        assertThatCode(() -> service.burnSlot(PHONE, PhoneOtpPurpose.PASSWORD_RESET)).doesNotThrowAnyException();

        verify(phoneOtpRepository, never()).save(any());
    }

    @Test
    @DisplayName("a failing slot-burn write never surfaces to the forgot-password caller")
    void should_swallowFailure_when_burnWriteFails() {
        when(phoneOtpRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.burnSlot(PHONE, PhoneOtpPurpose.PASSWORD_RESET)).doesNotThrowAnyException();
    }

    // ── B1 — race-free redeem ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("no compare slot can be reserved — verify fails without comparing the code")
    void should_failWithoutComparing_when_noCompareCanBeReserved() throws Exception {
        PhoneOtp otp = liveRegistrationCode(6L);
        when(attemptRecorder.reserveAttempt(6L, PhoneOtp.MAX_VERIFY_ATTEMPTS)).thenReturn(false);

        boolean redeemed = service.consumeCode(PHONE, CODE, PhoneOtpPurpose.REGISTRATION);

        assertThat(redeemed).isFalse();
        verify(attemptRecorder, never()).recordMismatch(any(), anyInt(), any());
        verify(phoneOtpRepository, never()).markUsedIfUnused(otp.getId());
        assertThat(count(PhoneOtpService.VERIFY_METRIC, PhoneOtpPurpose.REGISTRATION, "exhausted")).isEqualTo(1);
    }

    @Test
    @DisplayName("a concurrent verify already claimed the code — this verify fails")
    void should_fail_when_concurrentVerifyAlreadyClaimedTheCode() throws Exception {
        liveRegistrationCode(7L);
        when(attemptRecorder.reserveAttempt(7L, PhoneOtp.MAX_VERIFY_ATTEMPTS)).thenReturn(true);
        when(phoneOtpRepository.markUsedIfUnused(7L)).thenReturn(0);

        boolean redeemed = service.consumeCode(PHONE, CODE, PhoneOtpPurpose.REGISTRATION);

        assertThat(redeemed).as("the losing correct verify of a race gets nothing").isFalse();
    }

    @Test
    @DisplayName("wrong code — the mismatch is recorded and counted as invalid")
    void should_recordMismatchAndCountInvalid_when_codeWrong() throws Exception {
        liveRegistrationCode(8L);
        when(attemptRecorder.reserveAttempt(8L, PhoneOtp.MAX_VERIFY_ATTEMPTS)).thenReturn(true);

        boolean redeemed = service.consumeCode(PHONE, "000000", PhoneOtpPurpose.REGISTRATION);

        assertThat(redeemed).isFalse();
        verify(attemptRecorder).recordMismatch(8L, PhoneOtp.MAX_VERIFY_ATTEMPTS, NET);
        assertThat(count(PhoneOtpService.VERIFY_METRIC, PhoneOtpPurpose.REGISTRATION, "invalid")).isEqualTo(1);
    }

    // ── B7 — send metrics on provider failure ──────────────────────────────────────────

    @Test
    @DisplayName("SMS provider refuses — counted as provider_error")
    void should_countProviderError_when_providerRefuses() {
        doThrow(new SmsDeliveryException("refused", null, false)).when(otpSmsSender).send(anyString(), anyString());

        assertThatThrownBy(() -> service.sendCode(PHONE, null, PhoneOtpPurpose.REGISTRATION))
                .isInstanceOf(BusinessException.class);

        assertThat(count(PhoneOtpService.SEND_METRIC, PhoneOtpPurpose.REGISTRATION, "provider_error")).isEqualTo(1);
        assertThat(count(PhoneOtpService.SEND_METRIC, PhoneOtpPurpose.REGISTRATION, "ok")).isZero();
    }

    @Test
    @DisplayName("SMS provider outcome unknown — counted as ambiguous")
    void should_countAmbiguous_when_providerOutcomeUnknown() {
        doThrow(new SmsDeliveryAmbiguousException("timeout", null)).when(otpSmsSender).send(anyString(), anyString());

        assertThatThrownBy(() -> service.sendCode(PHONE, null, PhoneOtpPurpose.REGISTRATION))
                .isInstanceOf(BusinessException.class);

        assertThat(count(PhoneOtpService.SEND_METRIC, PhoneOtpPurpose.REGISTRATION, "ambiguous")).isEqualTo(1);
    }

    // ── Audit-fix (user 2026-11-19) — the daily cap is per (phone, client network) ──

    @Test
    @DisplayName("the daily failure cap counts only the caller network's failures")
    void should_countFailuresOfCallersNetworkOnly_when_verifying() {
        service.consumeCode(PHONE, CODE, PhoneOtpPurpose.REGISTRATION);

        verify(phoneOtpRepository).countFailuresSince(PHONE, NET, NOW.minus(Duration.ofHours(24)));
    }

    // ── Audit-fix cycle 2 — a failed mismatch write is retried once and counted ──

    @Test
    @DisplayName("mismatch write fails once on a pool error — retried once and counted as retried")
    void should_retryOnceAndCount_when_mismatchWriteFailsFirst() {
        JdbcTemplate jdbc = mock(
                JdbcTemplate.class);
        when(jdbc.update(eq(PhoneOtpAttemptRecorder.MISMATCH_SQL), eq(PhoneOtp.MAX_VERIFY_ATTEMPTS), eq(9L),
                eq(NET)))
                .thenThrow(new CannotGetJdbcConnectionException("pool busy"))
                .thenReturn(1);
        PhoneOtpAttemptRecorder recorder = new PhoneOtpAttemptRecorder(jdbc, meterRegistry);

        recorder.recordMismatch(9L, PhoneOtp.MAX_VERIFY_ATTEMPTS, NET);

        verify(jdbc, times(2)).update(PhoneOtpAttemptRecorder.MISMATCH_SQL,
                PhoneOtp.MAX_VERIFY_ATTEMPTS, 9L, NET);
        assertThat(recordFailed("true")).isEqualTo(1);
        assertThat(recordFailed("false")).isZero();
    }

    private double recordFailed(String retried) {
        var counter = meterRegistry.find(PhoneOtpService.VERIFY_METRIC).tag("result", "record_failed")
                .tag("retried", retried).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("mismatch write retried after a pool failure — both attempts fail, so it is counted once retried and once dropped")
    void should_countBothAndDrop_when_retryAlsoFails() {
        JdbcTemplate jdbc = mock(
                JdbcTemplate.class);
        when(jdbc.update(eq(PhoneOtpAttemptRecorder.MISMATCH_SQL), eq(PhoneOtp.MAX_VERIFY_ATTEMPTS), eq(9L),
                eq(NET)))
                .thenThrow(new CannotGetJdbcConnectionException("pool busy"))
                .thenThrow(new CannotGetJdbcConnectionException("pool still busy"));
        PhoneOtpAttemptRecorder recorder = new PhoneOtpAttemptRecorder(jdbc, meterRegistry);

        assertThatCode(() -> recorder.recordMismatch(9L, PhoneOtp.MAX_VERIFY_ATTEMPTS, NET))
                .as("a dropped mismatch record must never surface to the verify caller")
                .doesNotThrowAnyException();

        verify(jdbc, times(2)).update(PhoneOtpAttemptRecorder.MISMATCH_SQL,
                PhoneOtp.MAX_VERIFY_ATTEMPTS, 9L, NET);
        assertThat(recordFailed("true")).as("first transient failure counted as retried").isEqualTo(1);
        assertThat(recordFailed("false")).as("failed retry counted as dropped").isEqualTo(1);
    }

    static Stream<DataAccessException> nonRetriableMismatchFailures() {
        return Stream.of(
                new QueryTimeoutException("statement timeout"),
                new DataIntegrityViolationException("fk violation"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonRetriableMismatchFailures")
    @DisplayName("mismatch write failing non-transiently (timeout / constraint) is not retried and counted once as dropped")
    void should_notRetryAndCountOnce_when_mismatchWriteFailsNonTransiently(
            DataAccessException failure) {
        JdbcTemplate jdbc = mock(
                JdbcTemplate.class);
        when(jdbc.update(eq(PhoneOtpAttemptRecorder.MISMATCH_SQL), eq(PhoneOtp.MAX_VERIFY_ATTEMPTS), eq(9L),
                eq(NET))).thenThrow(failure);
        PhoneOtpAttemptRecorder recorder = new PhoneOtpAttemptRecorder(jdbc, meterRegistry);

        recorder.recordMismatch(9L, PhoneOtp.MAX_VERIFY_ATTEMPTS, NET);

        verify(jdbc, times(1)).update(PhoneOtpAttemptRecorder.MISMATCH_SQL,
                PhoneOtp.MAX_VERIFY_ATTEMPTS, 9L, NET);
        assertThat(recordFailed("false")).isEqualTo(1);
        assertThat(recordFailed("true")).isZero();
    }
}
