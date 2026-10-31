package com.beautica.review.invite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Duration;
import java.time.LocalTime;
import java.util.regex.Pattern;

/**
 * Phase 400 — guest review invite SMS settings, bound from {@code app.booking.review-invite}. Validated fail-fast at
 * bind time. Defaults are the locked production values; only {@code enabled} and {@code minPolicyVersion} are meant
 * to be set per environment.
 *
 * @param enabled          D2 feature flag — default {@code false}. Off: no invite row is created and any scheduled
 *                         row is skipped ({@code INVITES_DISABLED}) instead of sent
 * @param minPolicyVersion D2 — the privacy-policy version (website 018) that discloses review invites; a guest who
 *                         accepted an older version never gets one. Required (non-blank) when {@code enabled}
 * @param delay            D3 — {@code send_after = completedAt + delay}
 * @param quietStart       D3 — start of the Kyiv quiet window (inclusive)
 * @param quietEnd         D3 — end of the Kyiv quiet window (exclusive)
 * @param sendAt           D3 — Kyiv wall-clock time a quiet-window invite is moved to
 * @param staleAfter       D3 — unsent this long after {@code send_after} → {@code SKIPPED (STALE)}
 * @param ttl              D4 — {@code expires_at = sent_at + ttl}
 * @param batchSize        D4a — rows per claim / expiry statement (one job tick)
 * @param maxAttempts      D4 — SMS failures before {@code FAILED}
 * @param smsText          D5 — SMS copy; must contain {@code {token}} exactly once and render to one UCS-2 segment.
 *                         In PROD it must also link to {@value #PROD_LINK} ({@link #linksToProdHost}, enforced by
 *                         {@code EnvironmentFingerprintGuard} rule {@code prod-review-invite-sms-link})
 * @param sendBudget       audit cycle 1 — wall-clock budget of one job tick's send loop; claimed rows not attempted
 *                         when it runs out go back to {@code SCHEDULED}. Capped at the 30 s scheduler shutdown grace minus one
 *                         in-flight SMS (connect + read timeout) minus {@link #SEND_WRITE_BACK_MARGIN}
 */
@ConfigurationProperties(prefix = "app.booking.review-invite")
public record ReviewInviteProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String minPolicyVersion,
        @DefaultValue("2h") Duration delay,
        @DefaultValue("21:00") @DateTimeFormat(pattern = "HH:mm") LocalTime quietStart,
        @DefaultValue("09:00") @DateTimeFormat(pattern = "HH:mm") LocalTime quietEnd,
        @DefaultValue("10:00") @DateTimeFormat(pattern = "HH:mm") LocalTime sendAt,
        @DefaultValue("72h") Duration staleAfter,
        @DefaultValue("14d") Duration ttl,
        @DefaultValue("50") int batchSize,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue(DEFAULT_SMS_TEXT) String smsText,
        @DefaultValue("10s") Duration sendBudget
) {

    public static final String TOKEN_PLACEHOLDER = "{token}";
    public static final String DEFAULT_SMS_TEXT = "Оцініть ваш візит: beautica.app/r/" + TOKEN_PLACEHOLDER;

    /** One UCS-2 SMS segment (Cyrillic forces UCS-2). */
    public static final int MAX_SINGLE_SEGMENT_UCS2 = 70;
    /** Raw token length: 16 random bytes, base64url without padding. */
    public static final int TOKEN_LENGTH = 22;

    /** The only link host/path a PROD invite may carry (website 019 serves {@code /r/{token}} there). */
    public static final String PROD_LINK = "beautica.app/r/" + TOKEN_PLACEHOLDER;
    /** Default {@code sendBudget}. */
    public static final Duration DEFAULT_SEND_BUDGET = Duration.ofSeconds(10);
    /**
     * Time reserved after the last in-flight SMS for its outcome write-back: the post-send
     * {@code recordFailedSend}/{@code recordAmbiguousSend} transaction that settles the row. Without it a
     * send that returns right at the grace edge is torn down before its row is written.
     */
    public static final Duration SEND_WRITE_BACK_MARGIN = Duration.ofSeconds(2);
    /**
     * Static bind-time ceiling, audit cycle 2 (phase 408 audit-fix: minus the Telegram leg):
     * {@code SchedulingConfig.SCHEDULER_SHUTDOWN_GRACE_SECONDS} (30 s) minus {@link #TELEGRAM_LEG_WORST_CASE} (10 s)
     * minus the default Turbosms connect (3 s) and read (5 s) timeouts minus {@link #SEND_WRITE_BACK_MARGIN} (2 s).
     * The budget is checked BEFORE each send, so the worst tick is budget + one in-flight routed send (Telegram 403,
     * SMS, markBlocked) + its write-back; 10 + 10 + 3 + 5 + 2 = 30 s still drains inside the grace. The exact bound for the timeouts actually configured is
     * re-derived at boot by {@code ReviewInviteSendBudgetGuard} through {@link #requireSendBudgetWithin}; this
     * constant only rejects an obviously oversized value at bind time.
     */
    // Accepted (phase 408 audit, 2026-10-30): the worst tick is exactly 30 s (10 budget + 10 Telegram leg + 8 SMS +
    // 2 write-back) — zero slack against the scheduler grace. It does not count Hikari pool-acquire waits; a slow
    // pool at shutdown can cost the final write-back (row stays SENT / unreleased), never a lost message.
    public static final Duration MAX_SEND_BUDGET = Duration.ofSeconds(10);

    /**
     * Phase 408 audit-fix — the Telegram work around the SMS on a routed invite: send-gate admission wait (1 s) + Bot
     * API connect (2 s) + read (4 s) + (cycle 2) the gone-path {@code markBlocked} lock wait (3 s) that follows the
     * SMS fallback. Mirrors {@code TelegramBotApiClient} / {@code TelegramLinkService} constants (pinned by
     * {@code ReviewInviteSendBudgetGuardTest}). The worst send is: Telegram 403, SMS fallback, then markBlocked.
     */
    public static final Duration TELEGRAM_LEG_WORST_CASE = Duration.ofSeconds(10);

    /**
     * {@value #PROD_LINK}, optionally {@code https://}, as a whole word: preceded by start/whitespace (so neither
     * {@code evil-beautica.app} nor {@code evil.com/beautica.app} matches) and followed by end/whitespace/punctuation.
     */
    private static final Pattern PROD_LINK_PATTERN =
            Pattern.compile("(?<!\\S)(?:https://)?beautica\\.app/r/\\{token\\}(?=$|[\\s.,!?])");

    private static final Pattern POLICY_VERSION = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}(\\.\\d{1,3})?$");
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_ATTEMPTS_CAP = 10;

    public ReviewInviteProperties {
        if (minPolicyVersion == null) {
            minPolicyVersion = "";
        }
        if (enabled && minPolicyVersion.isBlank()) {
            throw new IllegalStateException(
                    "app.booking.review-invite.min-policy-version is required when review invites are enabled");
        }
        if (!minPolicyVersion.isBlank() && !POLICY_VERSION.matcher(minPolicyVersion).matches()) {
            throw new IllegalStateException("app.booking.review-invite.min-policy-version must look like 2026-10-20");
        }
        requirePositive("delay", delay);
        requirePositive("stale-after", staleAfter);
        requirePositive("ttl", ttl);
        if (quietStart == null || quietEnd == null || sendAt == null) {
            throw new IllegalStateException("app.booking.review-invite quiet-start/quiet-end/send-at are required");
        }
        if (isQuiet(sendAt, quietStart, quietEnd)) {
            throw new IllegalStateException("app.booking.review-invite.send-at must lie outside the quiet window");
        }
        requireInRange("batch-size", batchSize, MAX_BATCH_SIZE);
        requireInRange("max-attempts", maxAttempts, MAX_ATTEMPTS_CAP);
        requireSingleSegmentTemplate(smsText);
        requirePositive("send-budget", sendBudget);
        if (sendBudget.compareTo(MAX_SEND_BUDGET) > 0) {
            throw new IllegalStateException("app.booking.review-invite.send-budget must be <= " + MAX_SEND_BUDGET
                    + " (was " + sendBudget + ")");
        }
    }

    /**
     * Audit cycle 2 — the largest send budget whose worst tick (budget + one in-flight SMS: connect + read timeout +
     * {@link #SEND_WRITE_BACK_MARGIN} for its outcome write) still finishes inside {@code shutdownGrace}. Zero or
     * negative when the timeouts alone exceed the grace.
     */
    public static Duration maxSendBudget(Duration shutdownGrace, Duration connectTimeout, Duration readTimeout,
                                         Duration telegramLeg) {
        return shutdownGrace.minus(telegramLeg).minus(connectTimeout).minus(readTimeout).minus(SEND_WRITE_BACK_MARGIN);
    }

    /**
     * Audit cycle 2 — fails the boot when {@code sendBudget} exceeds {@link #maxSendBudget} for the SMS timeouts
     * actually configured: a shutdown would otherwise cut the last in-flight send, which is then neither marked sent
     * nor released.
     */
    public void requireSendBudgetWithin(Duration shutdownGrace, Duration connectTimeout, Duration readTimeout,
                                        Duration telegramLeg) {
        Duration max = maxSendBudget(shutdownGrace, connectTimeout, readTimeout, telegramLeg);
        if (sendBudget.compareTo(max) > 0) {
            throw new IllegalStateException("app.booking.review-invite.send-budget must be <= " + max
                    + " (scheduler grace " + shutdownGrace + " - telegram leg " + telegramLeg + " - sms connect "
                    + connectTimeout + " - sms read " + readTimeout + " - write-back " + SEND_WRITE_BACK_MARGIN
                    + "; was " + sendBudget + ")");
        }
    }

    /**
     * Audit cycle 1 — {@code true} when {@code smsText} links to {@value #PROD_LINK} (host validated as a whole word).
     * A {@code null} template is the binding default, which does.
     */
    public static boolean linksToProdHost(String smsText) {
        return smsText == null || PROD_LINK_PATTERN.matcher(smsText).find();
    }

    /** {@code [quietStart, quietEnd)}, wrapping midnight when {@code quietStart > quietEnd}. */
    public static boolean isQuiet(LocalTime time, LocalTime quietStart, LocalTime quietEnd) {
        if (quietStart.isBefore(quietEnd)) {
            return !time.isBefore(quietStart) && time.isBefore(quietEnd);
        }
        return !time.isBefore(quietStart) || time.isBefore(quietEnd);
    }

    private static void requireSingleSegmentTemplate(String smsText) {
        if (smsText == null || smsText.indexOf(TOKEN_PLACEHOLDER) < 0
                || smsText.indexOf(TOKEN_PLACEHOLDER) != smsText.lastIndexOf(TOKEN_PLACEHOLDER)) {
            throw new IllegalStateException("app.booking.review-invite.sms-text must contain {token} exactly once");
        }
        int rendered = smsText.length() - TOKEN_PLACEHOLDER.length() + TOKEN_LENGTH;
        if (rendered > MAX_SINGLE_SEGMENT_UCS2) {
            throw new IllegalStateException("app.booking.review-invite.sms-text renders to " + rendered
                    + " chars; one UCS-2 segment is " + MAX_SINGLE_SEGMENT_UCS2);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalStateException("app.booking.review-invite." + name + " must be positive (was " + value + ")");
        }
    }

    private static void requireInRange(String name, int value, int max) {
        if (value < 1 || value > max) {
            throw new IllegalStateException("app.booking.review-invite." + name + " must be in [1, " + max
                    + "] (was " + value + ")");
        }
    }
}
