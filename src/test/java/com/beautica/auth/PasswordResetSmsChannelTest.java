package com.beautica.auth;

import com.beautica.auth.phoneotp.PhoneOtpPurpose;
import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.PasswordResetOtpPolicyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetSmsChannel — SMS branch of forgot / change password (B516, AUTH-D18)")
class PasswordResetSmsChannelTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String PHONE = "+380671234567";

    @Mock private PhoneOtpService phoneOtpService;

    private PasswordResetSmsChannel channel;

    @BeforeEach
    void setUp() {
        var policy = new PasswordResetOtpPolicyConfig(Duration.ofMinutes(15), 5, 10, Duration.ofMinutes(15),
                Duration.ofSeconds(60), Duration.ofMinutes(10));
        channel = new PasswordResetSmsChannel(phoneOtpService, Runnable::run, policy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a request 20 s after the last one must wait the remaining 41 s of the 60 s cooldown")
    void should_reportRemainingSeconds_when_lastCodeWithinCooldown() {
        assertThat(channel.cooldownRemainingSeconds(NOW.minusSeconds(20))).contains(41L);
    }

    @Test
    @DisplayName("an account never sent a reset SMS, or sent one over 60 s ago, may get a code now")
    void should_allowSend_when_noCodeOrCooldownOver() {
        assertThat(channel.cooldownRemainingSeconds(null)).isEmpty();
        assertThat(channel.cooldownRemainingSeconds(NOW.minusSeconds(61))).isEmpty();
    }

    @Test
    @DisplayName("without an active transaction the reset code is handed to the SMS sender at once")
    void should_sendPasswordResetCode_when_noTransactionActive() {
        channel.sendAfterCommit(PHONE);

        verify(phoneOtpService).sendCode(PHONE, null, PhoneOtpPurpose.PASSWORD_RESET);
    }

    @Test
    @DisplayName("a provider failure is swallowed so the forgot response stays identical")
    void should_swallowProviderFailure_when_sendFails() {
        doThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "down"))
                .when(phoneOtpService).sendCode(org.mockito.ArgumentMatchers.eq(PHONE), isNull(),
                        org.mockito.ArgumentMatchers.eq(PhoneOtpPurpose.PASSWORD_RESET));

        assertThatCode(() -> channel.sendAfterCommit(PHONE)).doesNotThrowAnyException();
    }
}
