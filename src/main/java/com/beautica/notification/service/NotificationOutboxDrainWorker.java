package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxDrainWorker {

    // Track 24.x auto-confirm: doCreateBooking now enqueues NEW_BOOKING + STATUS_CHANGED
    // atomically on every booking creation (previously the STATUS_CHANGED half only landed
    // later, on a separate provider /confirm request, naturally time-spreading the two events).
    // That doubles outbox volume at peak booking moments with zero time-spread, against this
    // fixed-capacity serial drain worker — bump batch size proportionately (20 -> 50) rather
    // than shortening fixedDelay or parallelizing dispatch (which would risk SMTP/FCM rate
    // limits and the retry/DEAD-row semantics phase 2 relies on). Conservative, reversible.
    private static final int BATCH_SIZE = 50;

    /**
     * Package-private (not {@code private}) so {@link NotificationOutboxReclaimJob} can apply
     * the exact same retry ceiling when a stranded {@code PROCESSING} row is reclaimed — a
     * reclaim counts as a delivery attempt, same as a failed dispatch here, and both paths must
     * agree on when an entry is dead-lettered.
     */
    static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LENGTH = 500;

    /**
     * Redacts URL query strings, JWT-shaped values, and Bearer header values from
     * exception messages before persisting them to last_error. Compiled once at class
     * load — never per invocation (Fix M3 / Security MEDIUM).
     */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "\\?[^\\s]+" +
            "|[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}" +
            "|(?i)bearer\\s+[A-Za-z0-9_\\-.]+"
    );

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationService notificationService;
    private final BookingRepository bookingRepository;
    private final ObjectMapper objectMapper;
    private final OutboxPayloadCipher cipher;

    /**
     * Self-proxy reference so that {@link #drain()} (and {@link #persistResults(List)}, for
     * its per-entry {@link #persistOne(EntryResult)} calls) can call phase methods through the
     * Spring AOP proxy and have their {@code @Transactional} annotations honoured. Direct
     * {@code this.claimBatch()} calls bypass the proxy and leave {@code MANDATORY} propagation
     * on {@code claimPendingBatch()} without a surrounding transaction (Fix HIGH-4
     * self-invocation AOP bypass).
     *
     * <p>This is a deliberate, documented exception to the project's no-field-injection
     * rule. Self-proxy injection cannot be expressed as a constructor parameter (circular
     * dependency at construction time), so {@code @Lazy @Autowired} field injection is
     * the only viable pattern without a full class split.
     */
    @Autowired
    @Lazy
    private NotificationOutboxDrainWorker self;

    /**
     * Drains the outbox in three phases to prevent SMTP I/O from holding a Hikari
     * connection for the full dispatch window (Fix HIGH-4 — SMTP inside TX).
     *
     * <p><b>Phase 1</b> ({@code REQUIRES_NEW} tx): claim the batch via
     * {@code FOR UPDATE SKIP LOCKED}. Transaction commits immediately after the
     * batch is materialized in memory, releasing the DB connection.
     *
     * <p><b>Phase 2</b> ({@code NOT_SUPPORTED}): perform all SMTP/push dispatch
     * calls outside any transaction. No DB connection is held during this phase.
     * Each entry's dispatch result (SENT or DEAD) is recorded in-memory.
     *
     * <p><b>Phase 3</b> ({@code NOT_SUPPORTED} — see {@link #persistResults(List)}): persist the
     * status updates collected in phase 2, ONE independent {@code REQUIRES_NEW} transaction per
     * entry, so one entry's persistence failure cannot roll back its batch-mates' already-decided
     * outcomes.
     *
     * <p>Worst-case phase 2 duration: {@code BATCH_SIZE × per-entry dispatch worst-case}. Each
     * {@code dispatch()} call makes ONE of two chains: (a) the common case — email (SMTP:
     * connect 5s + read 10s + write 10s ≈ 25s, {@code application.yml} mail.smtp.*) followed by
     * push ({@code FirebaseConfig}: connect 5s + read 10s ≈ 15s) — ≈ 40s; or (b) a guest-DECLINED
     * entry (Phase 25.7), which sends SMS INSTEAD of email/push ({@code TurbosmsService}: connect
     * 3s + read 5s ≈ 8s) — a third blocking-I/O call type on this same serial loop, but strictly
     * cheaper than chain (a), so it does not raise the batch-level bound. At
     * {@code BATCH_SIZE = 50}: 50 × 40s = 2000s worst case — but zero DB connections are held
     * during that time. {@link NotificationOutboxReclaimJob}'s stale-claim threshold (production
     * default 60 min) is set with a comfortable margin over this ~33 min figure.
     *
     * <p><b>Test-isolation note (QA, track 25.x booking-enrichment audit, 2026-07-14).</b> The
     * period is property-driven ({@code notification.outbox.drain.fixed-delay-ms}, default
     * {@code 5000} — unchanged production behaviour) specifically so {@code application-test.yml}
     * can push it out to an effectively-never-fires interval. Every integration test that calls
     * {@code drainWorker.drain()} directly (e.g. {@code NotificationOutboxIntegrationTest},
     * {@code GuestBookingDeclineNotificationIT}, {@code ReviewLoopIT}) runs inside a full
     * {@code @SpringBootTest} context where {@code SchedulingConfig}'s real
     * {@code @EnableScheduling} bean is ALSO live — with the previous hard-coded 5s delay, this
     * background timer raced the test's own manual call over the exact same PENDING row (claim
     * uses {@code FOR UPDATE SKIP LOCKED}, so the loser's {@code claimBatch()} silently returns
     * an empty batch instead of throwing), producing a rare "expected: SENT but was: PENDING"
     * flake plus a logged {@code ObjectOptimisticLockingFailureException} when the background
     * worker's phase-3 {@code save()} later targeted a row the test had already
     * {@code deleteAll()}'d.
     *
     * <p><b>Correction (backlog, MEDIUM concurrency fix).</b> The note above's conclusion — "so
     * this was a test-infrastructure gap, not a production concurrency defect" — was wrong, and
     * is the kind of stale reasoning this correction exists to prevent being copied forward
     * again. It's true that within a single JVM, {@code fixedDelay} serializes {@code drain()}
     * against itself. But production runs on Railway, which performs <em>rolling deploys</em>:
     * the old and new instance run concurrently — each with its own live {@code @Scheduled}
     * timer — for the duration of every deploy. Nothing coordinates {@code drain()} calls
     * across instances. Before this fix, {@link #claimBatch()} only held a row lock for the
     * duration of its own short transaction and never changed the row's status, so a second
     * instance's claim — arriving after the first instance's claim transaction committed but
     * before it finished dispatch — would re-claim and re-dispatch the same notification. The
     * fix: {@link com.beautica.notification.repository.NotificationOutboxRepository#claimPendingBatch(int)}
     * now flips the row to {@code PROCESSING} atomically, in the same statement as the claim, so
     * it is excluded from every other claimer — same instance or a different one — the instant
     * this phase's transaction commits. {@link NotificationOutboxReclaimJob} is the paired
     * safety net that recovers a row stranded in {@code PROCESSING} by a crashed instance.
     */
    @Scheduled(fixedDelayString = "${notification.outbox.drain.fixed-delay-ms:5000}",
               initialDelayString = "${notification.outbox.drain.initial-delay-ms:0}")
    public void drain() {
        // Calls via `self` so each phase method runs through the Spring AOP proxy
        // and its @Transactional annotation is honoured (self-invocation bypass fix).
        List<NotificationOutboxEntry> batch = self.claimBatch();
        if (batch.isEmpty()) return;

        List<EntryResult> results = self.dispatchAll(batch);

        self.persistResults(results);
    }

    /**
     * Phase 1 — claim a batch of PENDING rows inside a short {@code REQUIRES_NEW}
     * transaction. The transaction commits as soon as this method returns.
     *
     * <p>The claim itself flips each row to {@code PROCESSING} atomically (see
     * {@link com.beautica.notification.repository.NotificationOutboxRepository#claimPendingBatch(int)}),
     * so once this method returns, every returned entry is durably unavailable to any other
     * claimer — on this instance or any other — regardless of how long phases 2/3 take.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<NotificationOutboxEntry> claimBatch() {
        return outboxRepository.claimPendingBatch(BATCH_SIZE);
    }

    /**
     * Phase 2 — dispatch all entries with no open DB transaction.
     * SMTP/push I/O runs here; connections are never held during this phase.
     * Returns the same entry objects annotated with their dispatch outcomes so
     * that Phase 3 can persist them without a second DB round-trip per entry.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<EntryResult> dispatchAll(List<NotificationOutboxEntry> batch) {
        // Pre-load all booking IDs in one query to avoid N+1 (Fix Perf HIGH).
        Set<UUID> bookingIds = batch.stream()
                .filter(e -> e.getEventType() != OutboxEventType.INVITE)
                .map(NotificationOutboxEntry::getAggregateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Booking> bookingCache = bookingRepository.findAllByIdsWithGraph(new ArrayList<>(bookingIds))
                .stream()
                .collect(Collectors.toMap(Booking::getId, b -> b));

        List<EntryResult> results = new ArrayList<>(batch.size());
        for (NotificationOutboxEntry entry : batch) {
            try {
                dispatch(entry, bookingCache);
                results.add(new EntryResult(entry, OutboxStatus.SENT, entry.getAttempts(), null));
            } catch (Exception e) {
                int next = entry.getAttempts() + 1;
                String error = sanitizeAndTruncate(e.getMessage(), MAX_ERROR_LENGTH);
                OutboxStatus status = next >= MAX_ATTEMPTS ? OutboxStatus.DEAD : OutboxStatus.PENDING;
                results.add(new EntryResult(entry, status, next, error));
                log.warn("Outbox dispatch failed [{}] attempt {}/{}: {}",
                        entry.getId(), next, MAX_ATTEMPTS, e.getClass().getSimpleName());
            }
        }
        return results;
    }

    /**
     * Phase 3 — persist the dispatch outcomes, one entry per independent
     * {@code REQUIRES_NEW} transaction (via {@link #persistOne(EntryResult)}, called through
     * the {@code self} proxy so its {@code @Transactional} is honoured).
     *
     * <p><b>Per-entry isolation (MEDIUM concurrency fix, second half).</b> Previously every
     * entry's status write shared ONE {@code REQUIRES_NEW} transaction with a single flush at
     * commit. PostgreSQL aborts an entire transaction on the first statement-level error within
     * it — so one entry's write failure (e.g. its row was concurrently deleted by
     * {@link #purgeStaleOutboxRows()}, or any other transient fault) poisoned that shared
     * connection and rolled back every OTHER entry's status write in the same batch too. Those
     * other entries — which may have dispatched successfully — would then be re-claimed and
     * re-dispatched on the next tick, because their {@code SENT}/{@code DEAD} outcome was never
     * durably recorded. Giving each entry its own transaction means one entry's failure can only
     * ever cost that one entry (it stays {@code PROCESSING} until
     * {@link NotificationOutboxReclaimJob} reclaims it) — never its batch-mates.
     *
     * <p>A failure here is caught, not rethrown: this method must not let one entry's exception
     * abort the loop before its siblings get their chance to persist.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void persistResults(List<EntryResult> results) {
        for (EntryResult result : results) {
            try {
                self.persistOne(result);
            } catch (Exception e) {
                log.error("Failed to persist outbox result [{}] (target status={}): {}",
                        result.entry().getId(), result.status(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Persists a single entry's dispatch outcome inside its own short {@code REQUIRES_NEW}
     * transaction — see {@link #persistResults(List)} for why isolation matters. Applies the
     * computed status fields directly to the entity object (re-attached to the new session by
     * JPA merge semantics on save, or flushed via dirty-checking if still managed) and flushes
     * immediately so any failure surfaces from this call, not from a later implicit flush.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistOne(EntryResult result) {
        NotificationOutboxEntry entry = result.entry();
        entry.setStatus(result.status());
        entry.setAttempts(result.attempts());
        entry.setLastError(result.lastError());
        outboxRepository.saveAndFlush(entry);
    }

    /** Lightweight value object carrying dispatch outcome for one outbox entry. */
    private record EntryResult(NotificationOutboxEntry entry, OutboxStatus status, int attempts, String lastError) {}

    private static final Duration OUTBOX_RETENTION = Duration.ofDays(30);

    /**
     * Purges terminal outbox rows (SENT or DEAD) older than 30 days.
     *
     * <p>Runs daily at 03:00 to prevent unbounded table growth. At 100 bookings/day
     * the table would otherwise accumulate 36,500+ rows per year. The partial index
     * {@code idx_outbox_terminal_updated} (V59) makes this DELETE efficient even on
     * large tables — it pre-filters the {@code (status, updated_at)} columns.
     *
     * <p>Fix MEDIUM-8 PERF.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeStaleOutboxRows() {
        Instant cutoff = Instant.now().minus(OUTBOX_RETENTION);
        outboxRepository.deleteByStatusInAndUpdatedAtBefore(
                List.of(OutboxStatus.SENT, OutboxStatus.DEAD),
                cutoff
        );
        log.info("Outbox TTL purge complete (cutoff={})", cutoff);
    }

    private void dispatch(NotificationOutboxEntry entry, Map<UUID, Booking> bookingCache) {
        switch (entry.getEventType()) {
            case NEW_BOOKING      -> notificationService.notifyNewBooking(getBooking(entry, bookingCache));
            case STATUS_CHANGED   -> notificationService.notifyBookingStatusChanged(getBooking(entry, bookingCache));
            case CLIENT_CANCELLED -> notificationService.notifyClientCancelled(getBooking(entry, bookingCache));
            case BOOKING_RESCHEDULED -> notificationService.notifyBookingRescheduled(getBooking(entry, bookingCache));
            case REVIEW_REQUESTED -> notificationService.notifyReviewRequested(getBooking(entry, bookingCache));
            case INVITE -> {
                Map<String, String> p = readJson(entry.getPayload());
                // Decrypt inviteUrlSealed from payload (Phase 5.4a cipher); aggregateId is the
                // invite_tokens row UUID for traceability only — the URL itself is derived from
                // the sealed payload field, not from aggregateId.
                String sealedUrl = p.get("inviteUrlSealed");
                if (sealedUrl == null || sealedUrl.isBlank()) {
                    throw new IllegalStateException(
                            "INVITE outbox payload missing inviteUrlSealed (entry " + entry.getId() + ")");
                }
                String inviteUrl = cipher.open(sealedUrl);
                notificationService.sendInviteEmail(
                        p.get("email"),
                        inviteUrl,
                        p.get("salonName")
                );
            }
        }
    }

    private Booking getBooking(NotificationOutboxEntry entry, Map<UUID, Booking> cache) {
        Booking booking = cache.get(entry.getAggregateId());
        if (booking == null) {
            throw new IllegalStateException("Booking not found for outbox entry: " + entry.getId());
        }
        return booking;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readJson(String payload) {
        try {
            // TypeReference forces Jackson to validate that every value is a String.
            // Raw Map.class would produce Map<String,Object>, allowing nested objects
            // to reach callers and produce a late ClassCastException whose message
            // includes the full nested representation (Security MEDIUM).
            return objectMapper.readValue(payload,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize outbox payload", e);
        }
    }

    /**
     * Strips URL query strings, JWT-shaped tokens, and Bearer header values to prevent
     * secrets appearing in last_error, then truncates to the DB column limit.
     * Uses a pre-compiled Pattern (see SENSITIVE_PATTERN) — never compiled per call.
     */
    private String sanitizeAndTruncate(String msg, int max) {
        if (msg == null) return null;
        String sanitized = SENSITIVE_PATTERN.matcher(msg).replaceAll("[REDACTED]");
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max);
    }
}
