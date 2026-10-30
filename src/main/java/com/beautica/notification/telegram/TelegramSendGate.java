package com.beautica.notification.telegram;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Everything that may refuse a Telegram send <b>before</b> any HTTP request (Phase 404 D4 + audit cycles 1–2), checked
 * in this order so a refused send consumes neither a limiter token nor a connection slot:
 * <ol>
 *   <li><b>Per-chat 429 back-off</b> — after a 429 with {@code parameters.retry_after} for chat X, sends to X (only X)
 *       are refused until that instant. Telegram also answers 429 for one chat's own flood limit, so a global block
 *       would let one user push everyone to SMS (audit cycle 2). Bounded Caffeine cache, at most
 *       {@value #MAX_BACKED_OFF_CHATS} chats, each entry expiring after its own {@code retry_after} (≤ 1 h),
 *       timed by the injected {@link Clock}.</li>
 *   <li><b>Concurrency</b> — at most {@value #MAX_CONCURRENT_SENDS} in-flight HTTP/1.1 requests; {@code tryAcquire},
 *       never a wait, so a slow Bot API cannot pile up request threads. Taken BEFORE the rate token, and given back
 *       if the bucket then refuses, so neither refusal spends the other's budget.</li>
 *   <li><b>Rate</b> — one global Bucket4j bucket: burst {@value #BURST_CAPACITY}, greedy refill
 *       {@value #SENDS_PER_SECOND}/s, so any 1 s window admits at most 5 + 25 = 30, Telegram's bulk limit.</li>
 * </ol>
 * Every refusal is a transient failure: the router falls back to SMS.
 */
final class TelegramSendGate {

    static final int BURST_CAPACITY = 5;
    static final int SENDS_PER_SECOND = 25;
    static final int MAX_CONCURRENT_SENDS = 8;
    static final int MAX_BACKED_OFF_CHATS = 10_000;
    /** Upper bound on an honoured {@code retry_after}, so a garbage value cannot mute a chat for days. */
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /** Why a send was refused; the name is logged as {@code cause=}. */
    enum Refusal { BACKOFF, RATE_LIMITED, CONCURRENCY }

    private final Clock clock;
    private final Bucket bucket;
    private final Semaphore inFlight;
    /** chatId → blocked-until; never logged (chat ids are PII under D5). */
    private final Cache<Long, Instant> blockedUntil;

    TelegramSendGate(Clock clock) {
        this(clock, new Semaphore(MAX_CONCURRENT_SENDS));
    }

    /** Test seam: the caller owns the semaphore and can exhaust it deterministically. */
    TelegramSendGate(Clock clock, Semaphore inFlight) {
        this.clock = clock;
        this.inFlight = inFlight;
        this.bucket = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(BURST_CAPACITY)
                        .refillGreedy(SENDS_PER_SECOND, Duration.ofSeconds(1))
                        .build())
                .withCustomTimePrecision(timeMeter(clock))
                .build();
        this.blockedUntil = Caffeine.newBuilder()
                .maximumSize(MAX_BACKED_OFF_CHATS)
                .expireAfter(new UntilInstant(clock))
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /**
     * Admits one send to {@code chatId}. On success the caller holds a connection slot and MUST call
     * {@link #release()} in a {@code finally}.
     *
     * @return the refusal, or empty when admitted
     */
    Optional<Refusal> tryAdmit(long chatId) {
        Instant until = blockedUntil.getIfPresent(chatId);
        if (until != null && clock.instant().isBefore(until)) {
            return Optional.of(Refusal.BACKOFF);
        }
        if (!inFlight.tryAcquire()) {
            return Optional.of(Refusal.CONCURRENCY);
        }
        if (!bucket.tryConsume(1)) {
            inFlight.release();
            return Optional.of(Refusal.RATE_LIMITED);
        }
        return Optional.empty();
    }

    void release() {
        inFlight.release();
    }

    /**
     * Honours a 429 {@code retry_after} for one chat; non-positive values are ignored, large ones capped at
     * {@link #MAX_BACKOFF}. A later, longer back-off for the same chat wins.
     */
    void backOff(long chatId, long retryAfterSeconds) {
        if (retryAfterSeconds <= 0) {
            return;
        }
        Instant until = clock.instant().plusSeconds(Math.min(retryAfterSeconds, MAX_BACKOFF.toSeconds()));
        blockedUntil.asMap().merge(chatId, until, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** Test hook: tokens left in the global bucket. */
    long availableTokens() {
        return bucket.getAvailableTokens();
    }

    /** Each entry lives exactly until its blocked-until instant. */
    private record UntilInstant(Clock clock) implements Expiry<Long, Instant> {
        @Override
        public long expireAfterCreate(Long chatId, Instant until, long currentTime) {
            return Math.max(0, Duration.between(clock.instant(), until).toNanos());
        }

        @Override
        public long expireAfterUpdate(Long chatId, Instant until, long currentTime, long currentDuration) {
            return expireAfterCreate(chatId, until, currentTime);
        }

        @Override
        public long expireAfterRead(Long chatId, Instant until, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    private static TimeMeter timeMeter(Clock clock) {
        return new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return TimeUnit.MILLISECONDS.toNanos(clock.millis());
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }
}
