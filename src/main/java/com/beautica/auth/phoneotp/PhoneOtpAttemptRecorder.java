package com.beautica.auth.phoneotp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a wrong-verify attempt independently of the {@code verifyOtp} transaction.
 *
 * <p>{@link PhoneOtpService#verifyOtp} throws a generic 401 immediately after a wrong code,
 * which rolls back its own {@code @Transactional} boundary. The brute-force attempt counter
 * must <b>survive</b> that rollback — otherwise the cap can never accumulate. This recorder
 * therefore runs the atomic increment in a separate {@code REQUIRES_NEW} transaction that
 * commits before the outer rollback, so each wrong code durably advances the counter.
 *
 * <p>Extracted into its own bean (not a private method on {@link PhoneOtpService}) because a
 * self-invoked {@code @Transactional(REQUIRES_NEW)} method bypasses the Spring AOP proxy and
 * would silently join the caller's transaction (§F.3).
 */
@Component
@RequiredArgsConstructor
public class PhoneOtpAttemptRecorder {

    private final PhoneOtpRepository phoneOtpRepository;

    /**
     * Atomically records one wrong verify attempt against the OTP and invalidates it at the
     * cap, in a fresh transaction that commits independently of the caller's (rolled-back)
     * verify transaction.
     *
     * @param otpId the id of the OTP being brute-forced
     * @param cap   {@link PhoneOtp#MAX_VERIFY_ATTEMPTS} — the per-OTP attempt ceiling
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(Long otpId, int cap) {
        phoneOtpRepository.recordFailedAttempt(otpId, cap);
    }
}
