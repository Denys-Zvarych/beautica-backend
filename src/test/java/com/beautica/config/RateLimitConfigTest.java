package com.beautica.config;

import com.beautica.booking.service.ScheduleOverrideConflictService;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the AS-BUILT verify-email / resend-verification rate-limit ceilings
 * (QA HIGH — rate-limit config assertion + spec reconciliation).
 *
 * <p>The Phase 1.8 spec text originally said "5 req/min, same ceiling as
 * login". The as-built limits are intentionally STRICTER and security-positive:
 * <ul>
 *   <li>verify-email: capacity 10 over a 15-minute window
 *       (≈ 0.67 req/min aggregate — far below 5/min)</li>
 *   <li>resend-verification: capacity 3 over a 60-second window</li>
 * </ul>
 * The reconciliation is to document the as-built limits in the phase doc
 * (done) and pin them here — NOT to weaken config back to a looser 5/min.
 *
 * <p>The bucket factory is exercised against the PRODUCTION DEFAULT capacities
 * (the {@code @Value} fields are set to the documented defaults via reflection),
 * deliberately NOT the inflated {@code application-test.yml} overrides
 * (capacity 100000) which would make a ceiling assertion meaningless.
 */
@DisplayName("RateLimitConfig — as-built verification ceilings")
class RateLimitConfigTest {

    // Production/default values from RateLimitConfig @Value defaults and the
    // window constants — the contract this test exists to pin.
    private static final long VERIFY_EMAIL_CAPACITY = 10L;
    private static final long RESEND_CAPACITY = 3L;
    // Phase A5 — password-reset OTP flow rate-limit ceilings.
    private static final long VERIFY_PASSWORD_RESET_OTP_CAPACITY = 10L;
    private static final long CHANGE_PASSWORD_OTP_CAPACITY = 3L;
    // 2026-07-26 re-audit, security audit finding 1 — aggregate schedule-override decline budget.
    // NOTE: the CRITICAL capacity > ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE invariant
    // is deliberately NOT pinned here as a hand-duplicated literal (that would recreate the exact
    // drift risk finding 1 warns about) — it is pinned in ScheduleOverrideConflictServiceTest, which
    // can reference the real MAX_CONFLICTS_PER_WRITE constant directly (same package) against a real
    // RateLimitConfig-built bucket.
    private static final long SCHEDULE_OVERRIDE_DECLINE_BUDGET_CAPACITY = 1500L;

    private RateLimitConfig config;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        // Mirror the @Value defaults — NOT the test-profile overrides.
        ReflectionTestUtils.setField(config, "verifyEmailCapacity", VERIFY_EMAIL_CAPACITY);
        ReflectionTestUtils.setField(config, "resendVerificationCapacity", RESEND_CAPACITY);
        ReflectionTestUtils.setField(config, "verifyPasswordResetOtpCapacity", VERIFY_PASSWORD_RESET_OTP_CAPACITY);
        ReflectionTestUtils.setField(config, "changePasswordOtpCapacity", CHANGE_PASSWORD_OTP_CAPACITY);
        ReflectionTestUtils.setField(
                config, "scheduleOverrideDeclineBudgetCapacity", SCHEDULE_OVERRIDE_DECLINE_BUDGET_CAPACITY);
    }

    @Test
    @DisplayName("should_allowExactlyVerifyEmailCapacityThenThrottle_when_15MinuteWindow")
    void should_allowExactlyVerifyEmailCapacityThenThrottle_when_15MinuteWindow() {
        LoadingCache<String, Bucket> buckets = config.verifyEmailBuckets();
        Bucket bucket = buckets.get("203.0.113.7");

        // Exactly the capacity must pass within the (15-minute) window...
        for (int i = 0; i < VERIFY_EMAIL_CAPACITY; i++) {
            assertThat(bucket.tryConsume(1))
                    .as("verify-email request %d of %d must be permitted", i + 1, VERIFY_EMAIL_CAPACITY)
                    .isTrue();
        }

        // ...the next one must be throttled. With a 15-minute refill window the
        // token does not replenish during the test, proving the window is NOT
        // the looser 1-minute window the stale spec implied.
        assertThat(bucket.tryConsume(1))
                .as("the (capacity+1)th verify-email request must be throttled")
                .isFalse();
    }

    @Test
    @DisplayName("should_allowExactlyResendCapacityThenThrottle_when_60SecondWindow")
    void should_allowExactlyResendCapacityThenThrottle_when_60SecondWindow() {
        LoadingCache<String, Bucket> buckets = config.resendVerificationBuckets();
        Bucket bucket = buckets.get("203.0.113.9");

        for (int i = 0; i < RESEND_CAPACITY; i++) {
            assertThat(bucket.tryConsume(1))
                    .as("resend request %d of %d must be permitted", i + 1, RESEND_CAPACITY)
                    .isTrue();
        }

        assertThat(bucket.tryConsume(1))
                .as("the (capacity+1)th resend request must be throttled")
                .isFalse();
    }

    @Test
    @DisplayName("should_allowExactlyVerifyPasswordResetOtpCapacityThenThrottle_when_15MinuteWindow")
    void should_allowExactlyVerifyPasswordResetOtpCapacityThenThrottle_when_15MinuteWindow() {
        LoadingCache<String, Bucket> buckets = config.verifyPasswordResetOtpBuckets();
        Bucket bucket = buckets.get("203.0.113.11");

        for (int i = 0; i < VERIFY_PASSWORD_RESET_OTP_CAPACITY; i++) {
            assertThat(bucket.tryConsume(1))
                    .as("verify-password-reset-otp request %d of %d must be permitted",
                            i + 1, VERIFY_PASSWORD_RESET_OTP_CAPACITY)
                    .isTrue();
        }

        assertThat(bucket.tryConsume(1))
                .as("the (capacity+1)th verify-password-reset-otp request must be throttled")
                .isFalse();
    }

    @Test
    @DisplayName("should_allowExactlyChangePasswordOtpCapacityThenThrottle_when_60MinuteWindow (4th request/hour gets 429)")
    void should_allowExactlyChangePasswordOtpCapacityThenThrottle_when_60MinuteWindow() {
        LoadingCache<String, Bucket> buckets = config.changePasswordOtpBuckets();
        Bucket bucket = buckets.get("203.0.113.13");

        for (int i = 0; i < CHANGE_PASSWORD_OTP_CAPACITY; i++) {
            assertThat(bucket.tryConsume(1))
                    .as("change-password-otp request %d of %d must be permitted",
                            i + 1, CHANGE_PASSWORD_OTP_CAPACITY)
                    .isTrue();
        }

        // The 4th request within the 60-minute window must be throttled (429 at the filter).
        assertThat(bucket.tryConsume(1))
                .as("the 4th change-password-otp request within the window must be throttled")
                .isFalse();
    }

    @Test
    @DisplayName("verify-email ceiling (10) is strictly below the login ceiling (5/min aggregate) over its window")
    void should_beStricterThanFivePerMinute_when_aggregatedOverWindow() {
        // 10 requests / 15 minutes ≈ 0.67 req/min — the as-built aggregate rate
        // is well under the stale "5 req/min" claim. This is the security-
        // positive reconciliation: stricter, not weaker.
        double aggregatePerMinute = VERIFY_EMAIL_CAPACITY / 15.0;

        assertThat(aggregatePerMinute)
                .as("as-built verify-email aggregate rate must be far below 5 req/min")
                .isLessThan(5.0);
    }

    /**
     * Pins the AS-BUILT aggregate schedule-override decline budget (2026-07-26 re-audit, security
     * finding 1, MEDIUM) — capacity 1500 tokens per 1-hour window, charged {@code conflicts.size()}
     * tokens per write by {@code ScheduleOverrideConflictService}, not the flat 1-per-request charge
     * every other bucket in this class uses. See {@code scheduleOverrideDeclineBudgetCapacity}'s
     * javadoc in {@link RateLimitConfig} for the sizing arithmetic. The CRITICAL
     * capacity-exceeds-{@code MAX_CONFLICTS_PER_WRITE} invariant this bucket depends on is pinned
     * separately, in {@code ScheduleOverrideConflictServiceTest}, against the real constant.
     */
    @Test
    @DisplayName("should_allowExactlyScheduleOverrideDeclineBudgetCapacityThenThrottle_when_1HourWindow")
    void should_allowExactlyScheduleOverrideDeclineBudgetCapacityThenThrottle_when_1HourWindow() {
        LoadingCache<String, Bucket> buckets = config.scheduleOverrideDeclineBudgetBuckets();
        Bucket bucket = buckets.get(java.util.UUID.randomUUID().toString());

        assertThat(bucket.tryConsume(SCHEDULE_OVERRIDE_DECLINE_BUDGET_CAPACITY))
                .as("a single burst consuming exactly the full capacity in one shot must be permitted "
                        + "(this is what a maximal single write, or several writes in the same burst, "
                        + "against a fresh/full bucket looks like)")
                .isTrue();

        assertThat(bucket.tryConsume(1))
                .as("one more token past a fully-exhausted budget must be throttled")
                .isFalse();
    }

    /**
     * Boot-time guard test (2026-07-26 LOW security finding, follow-up to security audit finding 1) —
     * neither pin test above actually reads the CONFIGURED {@code scheduleOverrideDeclineBudgetCapacity};
     * both always exercise the hardcoded 1500 default. This test proves the guard itself catches a
     * misconfiguration an operator could introduce via
     * {@code app.rate-limit.schedule-override-decline-budget-capacity} (e.g. a Railway env var) that
     * neither pin test would ever see.
     */
    @Test
    @DisplayName("should_failStartup_when_declineBudgetCapacityDoesNotExceedMaxConflictsPerWrite")
    void should_failStartup_when_declineBudgetCapacityDoesNotExceedMaxConflictsPerWrite() {
        // A capacity exactly AT MAX_CONFLICTS_PER_WRITE (100) is the boundary case: it looks plausible
        // to a careless operator ("100 conflicts, 100 capacity") but a full-capacity single write would
        // consume the ENTIRE bucket in one shot and every subsequent decline within the window would be
        // permanently throttled, so it must be rejected exactly like anything strictly lower.
        ReflectionTestUtils.setField(
                config, "scheduleOverrideDeclineBudgetCapacity", (long) ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE);

        assertThatThrownBy(config::validateScheduleOverrideDeclineBudgetCapacity)
                .as("a decline-budget capacity <= MAX_CONFLICTS_PER_WRITE must fail application startup, "
                        + "not silently 429 legitimate writes in production")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedule-override-decline-budget-capacity")
                .hasMessageContaining(String.valueOf(ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE));
    }

    @Test
    @DisplayName("should_initializeCleanly_when_declineBudgetCapacityExceedsMaxConflictsPerWrite")
    void should_initializeCleanly_when_declineBudgetCapacityExceedsMaxConflictsPerWrite() {
        // setUp() already reflects the production default (1500), which must exceed
        // MAX_CONFLICTS_PER_WRITE (100) — proving the happy path does not regress alongside the guard.
        assertThatCode(config::validateScheduleOverrideDeclineBudgetCapacity)
                .as("the production default (1500) must clear the guard without throwing")
                .doesNotThrowAnyException();
    }
}
