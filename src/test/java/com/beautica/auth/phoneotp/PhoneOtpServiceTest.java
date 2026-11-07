package com.beautica.auth.phoneotp;

import com.beautica.common.exception.BusinessException;
import com.beautica.config.DeployEnvironment;
import com.beautica.config.DeploymentProperties;
import com.beautica.config.SmsMode;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsDeliveryException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PhoneOtpService}. Collaborators are mocked. The clock is pinned
 * (§G). Key security assertions: the persisted value is a SHA-256 hash (never the
 * plaintext code), and every verify failure returns the same generic 401.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhoneOtpService — unit")
class PhoneOtpServiceTest {

    private static final String PHONE = "+380671234567";
    private static final Instant FIXED_NOW = Instant.parse("2026-06-17T10:00:00Z");
    private static final String GUEST_TOKEN = "guest.jwt.token";
    private static final String OTP_DOMAIN = "beautica.app";

    @Mock private PhoneOtpRepository phoneOtpRepository;
    @Mock private PhoneOtpAttemptRecorder attemptRecorder;
    @Mock private GuestTokenProvider guestTokenProvider;
    @Mock private OtpSmsSender otpSmsSender;

    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private PhoneOtpService service;

    @BeforeEach
    void setUp() {
        service = new PhoneOtpService(phoneOtpRepository, attemptRecorder, guestTokenProvider,
                otpSmsSender, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), txManager,
                new DeploymentProperties(DeployEnvironment.LOCAL,
                        new DeploymentProperties.Sms(SmsMode.TURBOSMS, OTP_DOMAIN)), meterRegistry);
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    /** The first 6-digit run of the captured SMS (the code is random). */
    private static String codeIn(String sms) {
        Matcher m = Pattern.compile("\\d{6}").matcher(sms);
        assertThat(m.find()).as("no 6-digit code in: %s", sms).isTrue();
        return m.group();
    }

    private String capturedSms() {
        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).send(eq(PHONE), smsText.capture());
        return smsText.getValue();
    }

    private double sentCount(OtpSmsFormat format) {
        return meterRegistry.counter(PhoneOtpService.SENT_METRIC, "format", format.metricTag()).count();
    }

    private double fallbackCount() {
        return meterRegistry.counter(PhoneOtpService.FORMAT_FALLBACK_METRIC).count();
    }

    // ── sendOtp — phase 440 smsFormat ──────────────────────────────────────────

    @Test
    @DisplayName("phase 440: sendOtp(phone, \"PLAIN\") sends the human line only and counts format=plain")
    void should_sendPlainText_when_formatPlain() {
        service.sendOtp(PHONE, "PLAIN");

        String sms = capturedSms();
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.plain(codeIn(sms)));
        assertThat(sentCount(OtpSmsFormat.PLAIN)).isEqualTo(1.0);
        assertThat(sentCount(OtpSmsFormat.DOMAIN_BOUND)).isZero();
        assertThat(fallbackCount()).isZero();
    }

    @Test
    @DisplayName("phase 440: sendOtp(phone) sends domain-bound, counts format=domain_bound, no fallback")
    void should_sendDomainBoundText_when_noFormat() {
        service.sendOtp(PHONE);

        String sms = capturedSms();
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.domainBound(codeIn(sms), OTP_DOMAIN));
        assertThat(sentCount(OtpSmsFormat.DOMAIN_BOUND)).isEqualTo(1.0);
        assertThat(sentCount(OtpSmsFormat.PLAIN)).isZero();
        assertThat(fallbackCount()).isZero();
    }

    @Test
    @DisplayName("phase 440: unknown \"FOO\" sends domain-bound and counts one fallback")
    void should_sendDomainBoundAndCountFallback_when_formatUnknown() {
        service.sendOtp(PHONE, "FOO");

        String sms = capturedSms();
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.domainBound(codeIn(sms), OTP_DOMAIN));
        assertThat(sentCount(OtpSmsFormat.DOMAIN_BOUND)).isEqualTo(1.0);
        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("phase 440: a failed provider send increments no sent counter")
    void should_notCountSent_when_senderThrows() {
        org.mockito.Mockito.doThrow(new SmsDeliveryException("down"))
                .when(otpSmsSender).send(eq(PHONE), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE, "PLAIN")).isInstanceOf(BusinessException.class);

        assertThat(sentCount(OtpSmsFormat.PLAIN)).isZero();
        assertThat(sentCount(OtpSmsFormat.DOMAIN_BOUND)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] \"{0}\"")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    @DisplayName("phase 440: a blank smsFormat sends domain-bound and is NOT counted as a fallback")
    void should_sendDomainBoundWithoutFallback_when_formatBlank(String blank) {
        service.sendOtp(PHONE, blank);

        String sms = capturedSms();
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.domainBound(codeIn(sms), OTP_DOMAIN));
        assertThat(sentCount(OtpSmsFormat.DOMAIN_BOUND)).isEqualTo(1.0);
        assertThat(fallbackCount()).as("blank = 'not sent', not an unknown value").isZero();
    }

    @Test
    @DisplayName("phase 440: mixed-case padded \" pLaIn \" is PLAIN — no fallback counted")
    void should_sendPlainWithoutFallback_when_formatMixedCaseAndPadded() {
        service.sendOtp(PHONE, " pLaIn ");

        String sms = capturedSms();
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.plain(codeIn(sms)));
        assertThat(sentCount(OtpSmsFormat.PLAIN)).isEqualTo(1.0);
        assertThat(fallbackCount()).isZero();
    }

    @Test
    @DisplayName("phase 440: an unknown format on a failed send still counts the fallback but no sent")
    void should_countFallbackButNotSent_when_formatUnknownAndSenderThrows() {
        org.mockito.Mockito.doThrow(new SmsDeliveryException("down"))
                .when(otpSmsSender).send(eq(PHONE), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE, "FOO")).isInstanceOf(BusinessException.class);

        assertThat(fallbackCount()).isEqualTo(1.0);
        assertThat(sentCount(OtpSmsFormat.DOMAIN_BOUND)).isZero();
    }

    @Test
    @DisplayName("phase 440: the send log line carries format=plain, the masked phone, never the code")
    void should_logFormatAndNeverCode_when_plainOtpSent() {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PhoneOtpService.class);
        appender.start();
        logger.addAppender(appender);
        java.util.List<String> lines;
        String code;
        try {
            service.sendOtp(PHONE, "PLAIN");

            code = codeIn(capturedSms());
            lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(lines).as("exactly today's send line plus the format field")
                .contains("OTP sent to +380***4567 format=plain");
        assertThat(lines).as("the code never reaches a log line, nor the raw phone")
                .noneMatch(line -> line.contains(code))
                .noneMatch(line -> line.contains(PHONE));
    }

    @Test
    @DisplayName("phase 440: the default send log line says format=domain_bound")
    void should_logDomainBoundFormat_when_noFormatGiven() {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PhoneOtpService.class);
        appender.start();
        logger.addAppender(appender);
        java.util.List<String> lines;
        try {
            service.sendOtp(PHONE);

            lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(lines).contains("OTP sent to +380***4567 format=domain_bound");
    }

    // ── sendOtp ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_dispatchSmsAndPersistHash_when_sendHappyPath")
    void should_dispatchSmsAndPersistHash_when_sendHappyPath() throws Exception {
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(0);

        service.sendOtp(PHONE);

        // The SMS carries the plaintext 6-digit code (Beautica: NNNNNN — ...); capture it
        // once — this single derivation is the load-bearing one used for every assertion.
        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).send(eq(PHONE), smsText.capture());
        String code = smsText.getValue().replaceAll("(?s).*?(\\d{6}).*", "$1");

        ArgumentCaptor<PhoneOtp> saved = ArgumentCaptor.forClass(PhoneOtp.class);
        verify(phoneOtpRepository).save(saved.capture());
        PhoneOtp otp = saved.getValue();

        assertThat(otp.getCodeHash())
                .as("persisted value must be the SHA-256 hex, never the plaintext code")
                .isEqualTo(sha256Hex(code))
                .doesNotContain(code)
                .hasSize(64);
        assertThat(otp.getExpiresAt()).isEqualTo(FIXED_NOW.plus(java.time.Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("sendOtp with a domain — exact template, same code in both lines, hash matches it")
    void should_sendDomainBoundTemplateWithSameCodeInBothLines_when_otpDomainConfigured() throws Exception {
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(0);

        service.sendOtp(PHONE);

        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).send(eq(PHONE), smsText.capture());
        String code = codeIn(smsText.getValue());
        assertThat(smsText.getValue())
                .as("exact Phase 389 template, same code in both lines")
                .isEqualTo(OtpSmsExpectedCopy.domainBound(code, OTP_DOMAIN));
        ArgumentCaptor<PhoneOtp> saved = ArgumentCaptor.forClass(PhoneOtp.class);
        verify(phoneOtpRepository).save(saved.capture());
        assertThat(saved.getValue().getCodeHash()).isEqualTo(sha256Hex(code));
    }

    @Test
    @DisplayName("sendOtp with a blank domain — first line only")
    void should_sendFirstLineOnly_when_otpDomainBlank() {
        PhoneOtpService noDomain = new PhoneOtpService(phoneOtpRepository, attemptRecorder,
                guestTokenProvider, otpSmsSender, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), txManager,
                new DeploymentProperties(DeployEnvironment.LOCAL,
                        new DeploymentProperties.Sms(SmsMode.TURBOSMS, "")), meterRegistry);
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(0);

        noDomain.sendOtp(PHONE);

        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).send(eq(PHONE), smsText.capture());
        assertThat(smsText.getValue()).isEqualTo(OtpSmsExpectedCopy.plain(codeIn(smsText.getValue())));
    }

    @Test
    @DisplayName("invalid domain fails service construction (boot), not each send")
    void should_failConstruction_when_otpDomainInvalid() {
        DeploymentProperties badDomain = new DeploymentProperties(DeployEnvironment.LOCAL,
                new DeploymentProperties.Sms(SmsMode.TURBOSMS, "https://beautica.app"));

        ThrowingCallable construct = () -> new PhoneOtpService(
                phoneOtpRepository, attemptRecorder, guestTokenProvider, otpSmsSender,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC), txManager, badDomain, meterRegistry);

        assertThatThrownBy(construct)
                .as("the host is validated once, at construction, not per send")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bare host");
    }

    @Test
    @DisplayName("should_invalidateOtherCodesExceptTheNewOne_when_sendSucceeds")
    void should_invalidateOtherCodesExceptTheNewOne_when_sendSucceeds() {
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(0);
        stubSaveAssigningId(42L);

        service.sendOtp(PHONE);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(phoneOtpRepository, otpSmsSender);
        order.verify(phoneOtpRepository).save(any());
        order.verify(otpSmsSender).send(eq(PHONE), anyString());
        order.verify(phoneOtpRepository).markOlderAsUsed(PHONE, 42L);
        assertThat(txManager.events).containsExactly("begin", "commit", "begin", "commit");
    }

    @Test
    @DisplayName("should_notInvalidateAnyCode_when_sendFails_soPreviousCodeStaysValid")
    void should_notInvalidateAnyCode_when_sendFails_soPreviousCodeStaysValid() {
        stubSaveAssigningId(42L);
        org.mockito.Mockito.doThrow(new com.beautica.notification.sms.SmsDeliveryException("down"))
                .when(otpSmsSender).send(eq(PHONE), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE)).isInstanceOf(BusinessException.class);

        verify(phoneOtpRepository, never()).markOlderAsUsed(anyString(), any());
    }

    @Test
    @DisplayName("should_takePhoneAdvisoryLockBeforeCountAndInsert_when_sendOtp")
    void should_takePhoneAdvisoryLockBeforeCountAndInsert_when_sendOtp() {
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(0);

        service.sendOtp(PHONE);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(phoneOtpRepository);
        order.verify(phoneOtpRepository).lockPhoneForSend(PHONE);
        order.verify(phoneOtpRepository).countByPhoneAndCreatedAtAfter(eq(PHONE), any());
        order.verify(phoneOtpRepository).save(any());
    }

    @Test
    @DisplayName("should_stillReturn_when_olderCodeInvalidationFailsAfterDelivery")
    void should_stillReturn_when_olderCodeInvalidationFailsAfterDelivery() {
        stubSaveAssigningId(42L);
        org.mockito.Mockito.doThrow(new IllegalStateException("db"))
                .when(phoneOtpRepository).markOlderAsUsed(PHONE, 42L);

        service.sendOtp(PHONE);

        verify(otpSmsSender).send(eq(PHONE), anyString());
    }

    @Test
    @DisplayName("should_stillReturn503_when_markingUndeliveredRowUsedFails")
    void should_stillReturn503_when_markingUndeliveredRowUsedFails() {
        stubSaveAssigningId(42L);
        org.mockito.Mockito.doThrow(new com.beautica.notification.sms.SmsDeliveryException("down"))
                .when(otpSmsSender).send(eq(PHONE), anyString());
        org.mockito.Mockito.doThrow(new IllegalStateException("db"))
                .when(phoneOtpRepository).markAsUsedById(42L);

        assertThatThrownBy(() -> service.sendOtp(PHONE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void stubSaveAssigningId(long id) {
        org.mockito.Mockito.doAnswer(inv -> {
            PhoneOtp row = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(row, "id", id);
            return row;
        }).when(phoneOtpRepository).save(any());
    }

    @Test
    @DisplayName("should_throw429_when_fourthSendWithin15Min")
    void should_throw429_when_fourthSendWithin15Min() {
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(3);

        assertThatThrownBy(() -> service.sendOtp(PHONE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(phoneOtpRepository, never()).save(any());
        verifyNoInteractions(otpSmsSender);
    }

    @Test
    @DisplayName("should_throw400_when_phoneMalformed")
    void should_throw400_when_phoneMalformed() {
        assertThatThrownBy(() -> service.sendOtp("12345"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(otpSmsSender);
        verify(phoneOtpRepository, never()).save(any());
    }

    /**
     * <b>Untested until 2026-08-18, and wrong when it finally was.</b> The send sat inside the
     * {@code @Transactional} method with no catch at all, so a Turbosms outage surfaced as a raw
     * {@code SmsDeliveryException} — a 500, with whatever the provider adapter chose to put in the
     * message.
     *
     * <p>It is now a deliberate, generic <b>503</b>. Note what this row does NOT assert: it does not
     * assert the swallow-and-warn every BOOKING path makes. That discipline is right there — the
     * booking is already committed and must not be undone by a vendor — and exactly wrong here: an
     * OTP row nobody received is worthless, and answering 200 would re-create, from a different
     * cause, the silent-failure defect the {@code OtpSmsSender} carve-out exists to close.
     */
    @Test
    @DisplayName("should_throw503WithNoProviderDetail_when_theOtpSmsCannotBeDelivered")
    void should_throw503WithNoProviderDetail_when_theOtpSmsCannotBeDelivered() {
        org.mockito.Mockito.doThrow(new com.beautica.notification.sms.SmsDeliveryException(
                        "Turbosms rejected the request with HTTP 503"))
                .when(otpSmsSender).send(eq(PHONE), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE))
                .isInstanceOf(BusinessException.class)
                .as("the provider's own wording — status codes, URLs — never reaches the guest")
                .hasMessageNotContaining("Turbosms")
                .hasMessageNotContaining("503")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * The compensation half of the row above: the OTP row is committed before the send (no DB
     * connection held during the provider call), so a failed dispatch marks it used (never deletes) —
     * it can never be redeemed but still counts toward the per-phone window cap.
     */
    @Test
    @DisplayName("should_markTheOtpRowUsedNotDelete_when_theOtpSmsCannotBeDelivered")
    void should_markTheOtpRowUsedNotDelete_when_theOtpSmsCannotBeDelivered() {
        org.mockito.Mockito.doAnswer(inv -> {
            PhoneOtp row = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(row, "id", 42L);
            return row;
        }).when(phoneOtpRepository).save(any());
        org.mockito.Mockito.doThrow(new com.beautica.notification.sms.SmsDeliveryException("down"))
                .when(otpSmsSender).send(eq(PHONE), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE)).isInstanceOf(BusinessException.class);

        verify(phoneOtpRepository).save(any());
        verify(phoneOtpRepository).markAsUsedById(42L);
        verify(phoneOtpRepository, never()).delete(any());
        verify(phoneOtpRepository, never()).deleteById(any());
        assertThat(txManager.events).containsExactly("begin", "commit", "begin", "commit");
    }

    @Test
    @DisplayName("should_callSenderWithNoActiveTransaction_when_sendOtp")
    void should_callSenderWithNoActiveTransaction_when_sendOtp() {
        java.util.concurrent.atomic.AtomicBoolean txActiveDuringSend = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> eventsAtSend =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            txActiveDuringSend.set(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            eventsAtSend.set(new java.util.ArrayList<>(txManager.events));
            return null;
        }).when(otpSmsSender).send(eq(PHONE), anyString());

        service.sendOtp(PHONE);

        assertThat(txActiveDuringSend.get())
                .as("no transaction (and so no pooled DB connection) may be open during the SMS call")
                .isFalse();
        assertThat(eventsAtSend.get())
                .as("the OTP row is committed before the send")
                .containsExactly("begin", "commit");
    }

    // ── verifyOtp ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnGuestToken_when_correctCode")
    void should_returnGuestToken_when_correctCode() throws Exception {
        String code = "482913";
        PhoneOtp otp = PhoneOtp.issue(PHONE, sha256Hex(code), FIXED_NOW.plusSeconds(600));
        when(phoneOtpRepository
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByIdDesc(eq(PHONE), any()))
                .thenReturn(Optional.of(otp));
        when(guestTokenProvider.generate(PHONE)).thenReturn(GUEST_TOKEN);

        String token = service.verifyOtp(PHONE, code);

        assertThat(token).isEqualTo(GUEST_TOKEN);
        assertThat(otp.isUsed()).as("OTP must be marked used after a successful verify").isTrue();
    }

    @Test
    @DisplayName("should_throw401Generic_when_wrongCode")
    void should_throw401Generic_when_wrongCode() throws Exception {
        PhoneOtp otp = PhoneOtp.issue(PHONE, sha256Hex("482913"), FIXED_NOW.plusSeconds(600));
        when(phoneOtpRepository
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByIdDesc(eq(PHONE), any()))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "000000"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(otp.isUsed()).as("wrong code must not consume the OTP").isFalse();
        verifyNoInteractions(guestTokenProvider);
    }

    @Test
    @DisplayName("should_throw401Generic_when_noActiveOtp_expiredOrNonexistent")
    void should_throw401Generic_when_noActiveOtp() {
        // findTop returns empty for both the expired and the nonexistent cases — the
        // service cannot (and must not) distinguish them: a single generic 401.
        when(phoneOtpRepository
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByIdDesc(eq(PHONE), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "482913"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(guestTokenProvider);
    }

    @Test
    @DisplayName("should_notLogPlaintextCode_when_sendOtp")
    void should_notLogPlaintextCode_when_sendOtp() {
        // Capture the code from the SMS, then assert it never appears in captured logs.
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(PhoneOtpService.class);
        appender.start();
        logger.addAppender(appender);
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(0);

        service.sendOtp(PHONE);

        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).send(eq(PHONE), smsText.capture());
        String code = smsText.getValue().replaceAll("(?s).*?(\\d{6}).*", "$1");
        String logged = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        logger.detachAppender(appender);

        assertThat(logged)
                .as("plaintext OTP code must never appear in log output")
                .doesNotContain(code);
        assertThat(logged)
                .as("log must carry only the masked phone")
                .contains("+380***4567")
                .doesNotContain(PHONE);
    }
}
