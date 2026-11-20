package com.beautica.auth;

import com.beautica.auth.phoneotp.PhoneOtpPurpose;
import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.config.PasswordResetOtpPolicyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * B516 (AUTH-12, AUTH-15, AUTH-D18) — the SMS branch of forgot / change password, for an account WITHOUT a confirmed
 * email: the code goes to its verified phone through {@link PhoneOtpService} (purpose
 * {@link PhoneOtpPurpose#PASSWORD_RESET}, 10 min (AUTH-D26 b), 5 attempts per code, the per-phone cap shared with every other
 * purpose, same SMS text). The caller has already passed App Check (AUTH-54).
 *
 * <p>Like the email branch, the SMS is handed to {@code smsSendExecutor} after the caller's transaction commits:
 * no DB connection or row lock is held across the provider call, the anonymous forgot-password latency does not depend
 * on whether an SMS is sent (no oracle), and a provider failure or the per-phone cap is logged, never surfaced (the
 * forgot response is byte-identical in every case). Only the 60 s resend cooldown is decided synchronously — from the
 * account's own {@code password_reset_sms_sent_at} stamp, written by the caller on the locked row in the same
 * transaction (the {@code phone_otps} row only exists after the asynchronous send, too late to serialise requests).
 */
@Component
public class PasswordResetSmsChannel {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetSmsChannel.class);

    private final PhoneOtpService phoneOtpService;
    private final TaskExecutor smsSendExecutor;
    private final PasswordResetOtpPolicyConfig policy;
    private final Clock clock;

    public PasswordResetSmsChannel(PhoneOtpService phoneOtpService,
                                   @Qualifier("smsSendExecutor") TaskExecutor smsSendExecutor,
                                   PasswordResetOtpPolicyConfig policy,
                                   Clock clock) {
        this.phoneOtpService = phoneOtpService;
        this.smsSendExecutor = smsSendExecutor;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * @param lastRequestedAt the account's {@code password_reset_sms_sent_at}, read on the row-locked user (V248);
     *                        {@code null} = never requested
     * @return seconds until the next code may be sent, or empty when a code may be sent now (60 s cooldown)
     */
    public Optional<Long> cooldownRemainingSeconds(Instant lastRequestedAt) {
        if (lastRequestedAt == null) {
            return Optional.empty();
        }
        Instant nextAllowed = lastRequestedAt.plus(policy.resendCooldown());
        Instant now = clock.instant();
        return now.isBefore(nextAllowed)
                ? Optional.of(Duration.between(now, nextAllowed).getSeconds() + 1)
                : Optional.empty();
    }

    /** Sends the code after the current transaction commits (immediately when none is active). */
    public void sendAfterCommit(String phoneE164) {
        // AUTH-D26 (c) audit-fix: the per-(phone, network) cap needs the caller's network, which only the request
        // thread knows — captured here, carried to the executor.
        String clientNet = phoneOtpService.currentClientNetwork();
        Runnable handOff = () -> handOff(phoneE164, clientNet);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            handOff.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                handOff.run();
            }
        });
    }

    private void handOff(String phoneE164, String clientNet) {
        try {
            smsSendExecutor.execute(() -> sendQuietly(phoneE164, clientNet));
        } catch (RuntimeException rejected) {
            log.warn("password-reset SMS hand-off refused: {}", rejected.getClass().getSimpleName());
        }
    }

    /** Never throws: a 429 (per-phone cap) or 503 (provider) is logged by class name only — no phone, no code. */
    private void sendQuietly(String phoneE164, String clientNet) {
        try {
            phoneOtpService.sendCodeFromNetwork(phoneE164, PhoneOtpPurpose.PASSWORD_RESET, clientNet);
        } catch (RuntimeException e) {
            log.warn("password-reset SMS not sent: {}", e.getClass().getSimpleName());
        }
    }
}
