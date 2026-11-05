package com.beautica.notification.telegram.webhook;

import com.beautica.auth.filter.ClientIpResolver;
import com.beautica.config.TelegramEnabledCondition;
import com.beautica.config.TelegramProperties;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 406 D2 + D4 — authenticates a webhook call by the Bot API {@code secret_token} header and throttles only the
 * callers that fail it.
 *
 * <ul>
 *   <li>Header {@value #SECRET_HEADER} vs {@code app.telegram.webhook-secret}, compared with
 *       {@link MessageDigest#isEqual} on UTF-8 bytes (expected value first: its running time depends on the expected
 *       length only). A blank configured secret rejects everything (fail closed).</li>
 *   <li>Each rejected call charges the caller IP's bucket ({@code app.rate-limit.telegram-webhook-capacity} per
 *       minute, {@link ClientIpResolver} — the same IP rule as every other per-IP bucket). Genuine Telegram traffic
 *       carries the secret and is never charged, so it is never throttled.</li>
 *   <li>Never logs the header value, IP or body, and never one line per call: rejections ({@code rejected}, bad
 *       or missing secret) and throttled calls ({@code throttled}) are only counted, and one summary line
 *       {@code event=telegram_webhook_rejected rejected=N throttled=M since=<epoch s>} is written at most once per
 *       {@link #REJECT_LOG_WINDOW} for the whole endpoint — so a flood, even one rotating IPs (which never reaches
 *       429), cannot grow the log with the number of callers (406 perf audit). {@code since} is when the first
 *       counted call of that line happened. The first rejection of a window logs at once; what piles up after it is
 *       flushed by the first call (of any kind) once the next window opens, or by the 60 s scheduled flush on a
 *       quiet bot (scheduling is on app-wide: {@code SchedulingConfig}).</li>
 * </ul>
 *
 * <p>Exists only while the channel is on (same condition as the controller), so a dark deploy runs no flush job.
 */
@Slf4j
@Component
@Conditional(TelegramEnabledCondition.class)
public class TelegramWebhookSecretVerifier {

    public static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    /** Bot API {@code secret_token} is at most 256 chars; a longer header is rejected without comparing. */
    private static final int MAX_SECRET_LENGTH = 256;

    static final Duration REJECT_LOG_WINDOW = Duration.ofMinutes(1);
    private static final long NONE = -1L;

    public enum Outcome { ACCEPTED, REJECTED, THROTTLED }

    private final byte[] expectedSecret;
    private final LoadingCache<String, Bucket> rejectBuckets;
    private final Clock clock;
    private final AtomicLong nextRejectLogAtMillis = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong pendingRejected = new AtomicLong();
    private final AtomicLong pendingThrottled = new AtomicLong();
    private final AtomicLong pendingSinceMillis = new AtomicLong(NONE);

    public TelegramWebhookSecretVerifier(
            TelegramProperties properties,
            @Qualifier("telegramWebhookRejectBuckets") LoadingCache<String, Bucket> rejectBuckets,
            Clock clock) {
        this.expectedSecret = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
        this.rejectBuckets = rejectBuckets;
        this.clock = clock;
    }

    public Outcome verify(HttpServletRequest request) {
        // Deliberate order: the constant-time secret check runs BEFORE the bucket, so Telegram's own (authenticated)
        // traffic is never charged or throttled — do not move the bucket in front of it (406 D4).
        if (matches(request.getHeader(SECRET_HEADER))) {
            flushRejectLogIfDue();
            return Outcome.ACCEPTED;
        }
        Bucket bucket = rejectBuckets.get(ClientIpResolver.resolve(request));
        Outcome outcome = bucket.tryConsume(1) ? Outcome.REJECTED : Outcome.THROTTLED;
        pendingSinceMillis.compareAndSet(NONE, clock.millis());
        (outcome == Outcome.REJECTED ? pendingRejected : pendingThrottled).incrementAndGet();
        flushRejectLogIfDue();
        return outcome;
    }

    /**
     * Writes the pending counts once the window has passed; one CAS winner per window, others only count. Also runs
     * every 60 s on its own so a burst on a quiet bot is not held until the next call. Counters are drained with
     * {@code getAndSet(0)}, so a call racing a flush lands in this line or the next — never lost, never twice.
     */
    @Scheduled(fixedDelay = 60_000)
    public void flushRejectLogIfDue() {
        if (pendingRejected.get() == 0 && pendingThrottled.get() == 0) {
            return;
        }
        long now = clock.millis();
        long next = nextRejectLogAtMillis.get();
        if (now >= next && nextRejectLogAtMillis.compareAndSet(next, now + REJECT_LOG_WINDOW.toMillis())) {
            long since = pendingSinceMillis.getAndSet(NONE);
            long rejected = pendingRejected.getAndSet(0);
            long throttled = pendingThrottled.getAndSet(0);
            if (rejected + throttled > 0) {
                log.warn("event=telegram_webhook_rejected rejected={} throttled={} since={}", rejected, throttled,
                        (since == NONE ? now : since) / 1000);
            }
        }
    }

    private boolean matches(String provided) {
        if (expectedSecret.length == 0 || provided == null || provided.isEmpty()
                || provided.length() > MAX_SECRET_LENGTH) {
            return false;
        }
        return MessageDigest.isEqual(expectedSecret, provided.getBytes(StandardCharsets.UTF_8));
    }
}
