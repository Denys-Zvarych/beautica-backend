package com.beautica.auth.phoneotp;

import com.beautica.common.exception.BusinessException;
import com.beautica.notification.sms.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * <b>CRITICAL-FIX REGRESSION NET (Phase 13.2 — uncapped OTP verify brute-force).</b>
 *
 * <p>backend-security found that {@code POST /book/otp/verify} accepts unlimited wrong
 * codes against a live OTP. The 4-digit code space is only 10 000 values, so within the
 * 10-minute TTL an attacker can enumerate every code and mint a guest token for an
 * arbitrary phone. The fix adds a <b>per-OTP attempt counter</b> that invalidates the
 * active code after a small number of wrong tries (N ≈ 5), so a subsequent <i>correct</i>
 * code is still rejected — the brute-force window collapses from 10 000 attempts to N.
 *
 * <p><b>This test encodes the not-yet-applied fix and is EXPECTED-RED until backend-dev
 * lands the attempt counter</b> (the current {@link PhoneOtpService#verifyOtp} never
 * invalidates on a wrong code, so the final correct attempt currently succeeds). The
 * fix loop's verifier run is what turns this green.
 *
 * <p>The exact invalidation threshold is asserted loosely (≤ a documented MAX) so a
 * reasonable choice of N (3..5) by the implementer does not force a test edit; only the
 * security <i>contract</i> is pinned: after the cap, the correct code no longer works.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhoneOtpService — verify brute-force cap (CRITICAL-fix regression, expected-red until fix lands)")
class PhoneOtpVerifyBruteForceTest {

    private static final String PHONE = "+380671234567";
    private static final String CORRECT_CODE = "4829";
    private static final String WRONG_CODE = "0000";
    private static final Instant FIXED_NOW = Instant.parse("2026-06-17T10:00:00Z");

    /**
     * Upper bound on the attempt cap the implementer may choose. The fix invalidates the
     * OTP on or before this many wrong attempts; firing this many wrong codes therefore
     * guarantees the OTP is dead regardless of the exact N (3..5) selected.
     */
    private static final int MAX_WRONG_ATTEMPTS_BEFORE_INVALIDATION = 5;

    @Mock private PhoneOtpRepository phoneOtpRepository;
    @Mock private PhoneOtpAttemptRecorder attemptRecorder;
    @Mock private GuestTokenProvider guestTokenProvider;
    @Mock private SmsService smsService;

    private PhoneOtpService service;

    @BeforeEach
    void setUp() {
        service = new PhoneOtpService(phoneOtpRepository, attemptRecorder, guestTokenProvider,
                smsService, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    @Test
    @DisplayName("should_return401_andInvalidateOtp_afterNthWrongCode")
    void should_return401_andInvalidateOtp_afterNthWrongCode() throws Exception {
        // A single active OTP is returned for every lookup — the same row the attacker is
        // hammering. The fix increments the attempt counter via an atomic conditional UPDATE
        // (PhoneOtpRepository#recordFailedAttempt), not by mutating the loaded entity. We
        // simulate that DB-side state here: the mock's recordFailedAttempt bumps a counter
        // and flips `used` at the cap, and the active-OTP lookup returns empty once used —
        // so the final CORRECT attempt no longer finds an active OTP and never mints a token.
        PhoneOtp otp = PhoneOtp.issue(PHONE, sha256Hex(CORRECT_CODE), FIXED_NOW.plusSeconds(600));
        AtomicInteger dbAttempts = new AtomicInteger(0);
        AtomicBoolean dbUsed = new AtomicBoolean(false);

        when(phoneOtpRepository
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(eq(PHONE), any()))
                .thenAnswer(inv -> dbUsed.get() ? Optional.empty() : Optional.of(otp));
        org.mockito.Mockito.doAnswer(inv -> {
                    int cap = inv.getArgument(1);
                    if (dbAttempts.incrementAndGet() >= cap) {
                        dbUsed.set(true);
                    }
                    return null;
                }).when(attemptRecorder)
                .recordFailedAttempt(any(), eq(PhoneOtp.MAX_VERIFY_ATTEMPTS));

        // Burn the attempt budget with wrong codes. Each is a generic 401.
        for (int attempt = 1; attempt <= MAX_WRONG_ATTEMPTS_BEFORE_INVALIDATION; attempt++) {
            assertThatThrownBy(() -> service.verifyOtp(PHONE, WRONG_CODE))
                    .as("wrong code attempt %d must return the generic 401", attempt)
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // The CRITICAL contract: after the cap, even the CORRECT code is rejected because
        // the attempt counter has invalidated the OTP.
        assertThatThrownBy(() -> service.verifyOtp(PHONE, CORRECT_CODE))
                .as("after %d wrong attempts the OTP must be invalidated — the correct code "
                        + "must now be rejected (brute-force window collapsed)",
                        MAX_WRONG_ATTEMPTS_BEFORE_INVALIDATION)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // The increment must always pass the entity id + the MAX cap to the atomic UPDATE
        // (the conditional-invalidation contract lives in the query, not in-memory).
        verify(attemptRecorder, atLeastOnce())
                .recordFailedAttempt(otp.getId(), PhoneOtp.MAX_VERIFY_ATTEMPTS);
        assertThat(dbUsed.get())
                .as("the brute-forced OTP must be marked used/invalidated after the attempt cap")
                .isTrue();
        // No guest token may ever be minted across the whole attack sequence.
        verifyNoInteractions(guestTokenProvider);
    }
}
