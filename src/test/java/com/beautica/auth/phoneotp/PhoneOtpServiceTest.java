package com.beautica.auth.phoneotp;

import com.beautica.common.exception.BusinessException;
import com.beautica.notification.sms.OtpSmsSender;
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

    @Mock private PhoneOtpRepository phoneOtpRepository;
    @Mock private PhoneOtpAttemptRecorder attemptRecorder;
    @Mock private GuestTokenProvider guestTokenProvider;
    @Mock private OtpSmsSender otpSmsSender;

    private PhoneOtpService service;

    @BeforeEach
    void setUp() {
        service = new PhoneOtpService(phoneOtpRepository, attemptRecorder, guestTokenProvider,
                otpSmsSender, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
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
        String code = smsText.getValue().replaceAll(".*?(\\d{6}).*", "$1");

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
    @DisplayName("should_expirePriorUnusedOtps_when_newSend")
    void should_expirePriorUnusedOtps_when_newSend() {
        when(phoneOtpRepository.countByPhoneAndCreatedAtAfter(eq(PHONE), any())).thenReturn(0);

        service.sendOtp(PHONE);

        verify(phoneOtpRepository).markAllUnusedAsUsed(PHONE);
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
     * The rollback half of the row above, which is the part that matters operationally: the OTP row
     * must not survive a failed dispatch. {@code save} is called (the row is written before the
     * send, so the code is durable if the send succeeds), but the exception propagates out of the
     * {@code @Transactional} method, so Spring rolls the insert back — the guest keeps their full
     * per-phone window budget, which {@code countByPhoneAndCreatedAtAfter} derives from those very
     * rows, and can retry at once.
     */
    @Test
    @DisplayName("should_letTheOtpRowRollBack_when_theOtpSmsCannotBeDelivered")
    void should_letTheOtpRowRollBack_when_theOtpSmsCannotBeDelivered() {
        org.mockito.Mockito.doThrow(new com.beautica.notification.sms.SmsDeliveryException("down"))
                .when(otpSmsSender).send(eq(PHONE), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE)).isInstanceOf(BusinessException.class);

        // The row was written, and the throw is what makes it never commit. A caught-and-swallowed
        // failure here would leave a redeemable code the guest never got AND spend a budget slot.
        verify(phoneOtpRepository).save(any());
    }

    // ── verifyOtp ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnGuestToken_when_correctCode")
    void should_returnGuestToken_when_correctCode() throws Exception {
        String code = "482913";
        PhoneOtp otp = PhoneOtp.issue(PHONE, sha256Hex(code), FIXED_NOW.plusSeconds(600));
        when(phoneOtpRepository
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(eq(PHONE), any()))
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
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(eq(PHONE), any()))
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
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(eq(PHONE), any()))
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
        String code = smsText.getValue().replaceAll(".*?(\\d{6}).*", "$1");
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
