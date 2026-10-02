package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.inapp.push.InAppPushDispatcher;
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
import java.util.concurrent.RejectedExecutionException;
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
     * The event types whose dispatch consumes a {@link BookingVisit} — the ones {@link #dispatch}
     * routes through {@link #getVisit}. Kept beside the switch it mirrors: adding a visit-aware
     * case there without adding it here would leave that case resolving every visit to its lead
     * booking alone, which is the exact defect the visit resolver exists to fix.
     *
     * <p>{@code SALON_CLOSED} (Phase 269/293) and {@code MASTER_REMOVED} (Phase 298) are
     * visit-aware too, but for a DIFFERENT reason than {@code NEW_BOOKING}/{@code STATUS_CHANGED}:
     * those two are enqueued against the visit's lead booking while every sibling is still a
     * separate row to describe. {@code SALON_CLOSED}/{@code MASTER_REMOVED} are already
     * deduplicated to ONE entry per visit at enqueue time (D12 —
     * {@code NotificationOutboxService#enqueueSalonClosed}/{@code #enqueueMasterRemoved}'s caller
     * picks the representative booking), so this resolution exists only so the copy can say
     * «3 послуги» instead of naming one service out of the three that were actually declined.
     */
    private static final Set<OutboxEventType> VISIT_AWARE_EVENTS = Set.of(
            OutboxEventType.NEW_BOOKING, OutboxEventType.STATUS_CHANGED, OutboxEventType.SALON_CLOSED,
            OutboxEventType.MASTER_REMOVED);

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
    private final BookingVisitResolver visitResolver;
    private final ObjectMapper objectMapper;
    private final OutboxPayloadCipher cipher;
    private final InAppPushDispatcher inAppPushDispatcher;

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
     *
     * <p><b>Every DB read this phase makes happens in the pre-load block below, before the dispatch
     * loop starts</b> — including the {@code INAPP_PUSH} feed rows, their recipients, the recipients'
     * active device tokens and the rendered push copy ({@link InAppPushDispatcher#prepare}, a bounded
     * number of statements for the whole batch). The push hand-off passes the pre-loaded tokens, so
     * it triggers no lookup on the drain thread; the only DB read after the hand-off is the one indexed
     * ownership re-check query per recipient that {@link PushNotificationService#sendToDevices} runs on
     * {@code pushExecutor}.
     * That is the whole point of the phase: once the loop begins, each iteration
     * can block for ~40 s on SMTP + FCM, and holding (or re-acquiring) a Hikari connection across
     * that window is what the three-phase split exists to prevent. The visit hydration was briefly
     * done per entry, inside the loop — up to 50 connection check-outs interleaved with the SMTP
     * calls, and two of them per created visit, since a visit enqueues both {@code NEW_BOOKING} and
     * {@code STATUS_CHANGED} against the same lead booking. It is batched here for the same reason
     * the booking cache is: one statement for the whole batch, none after dispatch begins.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<EntryResult> dispatchAll(List<NotificationOutboxEntry> batch) {
        // Pre-load all booking IDs in one query to avoid N+1 (Fix Perf HIGH).
        Set<UUID> bookingIds = batch.stream()
                // INAPP_PUSH's aggregate_id is a feed-row id, not a booking id (phase 339).
                .filter(e -> e.getEventType() != OutboxEventType.INVITE
                        && e.getEventType() != OutboxEventType.INAPP_PUSH)
                .map(NotificationOutboxEntry::getAggregateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Booking> bookingCache = bookingRepository.findAllByIdsWithGraph(new ArrayList<>(bookingIds))
                .stream()
                .collect(Collectors.toMap(Booking::getId, b -> b));

        // Second (and last) pre-load: the chained sibling rows of every visit this batch touches,
        // in ONE query — see BookingVisitResolver#hydrate. Keyed by appointment id, so the two
        // outbox rows of one visit share a single hydration instead of querying twice.
        Map<UUID, List<Booking>> visitSiblings = visitResolver.hydrate(visitAwareLeads(batch, bookingCache));

        // Third pre-load: every INAPP_PUSH entry of the batch resolved to a ready-to-send push in a
        // bounded number of statements (rows, recipients, device tokens — one each — then one batch
        // assembler call for all recipients), never per entry or per recipient. See InAppPushDispatcher#prepare.
        // A failure here must cost ONLY the INAPP_PUSH entries (each failed below, attempt + 1) — the
        // batch's e-mail entries do not depend on it and must still be dispatched.
        Map<UUID, InAppPushDispatcher.PushPlan> pushPlans = Map.of();
        String pushPrepareError = null;
        try {
            pushPlans = inAppPushDispatcher.prepare(inAppPushIds(batch));
        } catch (RuntimeException e) {
            pushPrepareError = e.getClass().getSimpleName();
            log.warn("INAPP_PUSH prepare failed, failing only the push entries of this batch: {}", pushPrepareError);
        }

        List<EntryResult> results = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            NotificationOutboxEntry entry = batch.get(i);
            if (pushPrepareError != null && entry.getEventType() == OutboxEventType.INAPP_PUSH) {
                results.add(failedAttempt(entry, pushPrepareError));
                continue;
            }
            try {
                dispatch(entry, bookingCache, visitSiblings, pushPlans);
                results.add(new EntryResult(entry, OutboxStatus.SENT, entry.getAttempts(), null));
            } catch (RejectedExecutionException e) {
                // Spring's TaskRejectedException (what @Async actually throws) extends it.
                // NOTE: this catch is not push-specific — a RejectedExecutionException from ANY
                // executor reached by dispatch(...), the e-mail executor included (AbortPolicy), lands
                // here too and is deliberately handled the same way: the entry and the rest of the
                // batch are re-queued without counting an attempt (so a persistently saturated
                // e-mail executor is retried every tick and never walks the row to DEAD).
                // Backpressure, not a delivery failure: the push executor (AbortPolicy, bounded queue)
                // is saturated. Counting this as an attempt would walk healthy rows to DEAD during a
                // burst, so this entry and every not-yet-dispatched one go back to PENDING with their
                // attempts untouched, and the rest of the batch is NOT dispatched — the next tick
                // retries them once the executor has drained.
                log.warn("Push executor saturated at outbox entry [{}] — re-queueing {} entries without "
                        + "counting an attempt: {}", entry.getId(), batch.size() - i, e.getClass().getSimpleName());
                requeueRemaining(batch, i, results);
                break;
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

    /** One counted failed attempt: PENDING for a retry, DEAD once {@code MAX_ATTEMPTS} is reached. */
    private static EntryResult failedAttempt(NotificationOutboxEntry entry, String error) {
        int next = entry.getAttempts() + 1;
        OutboxStatus status = next >= MAX_ATTEMPTS ? OutboxStatus.DEAD : OutboxStatus.PENDING;
        log.warn("Outbox dispatch failed [{}] attempt {}/{}: {}", entry.getId(), next, MAX_ATTEMPTS, error);
        return new EntryResult(entry, status, next, error);
    }

    /** Marks {@code batch[from..]} PENDING with attempts unchanged — see the rejection catch above. */
    private static void requeueRemaining(List<NotificationOutboxEntry> batch, int from, List<EntryResult> results) {
        for (NotificationOutboxEntry entry : batch.subList(from, batch.size())) {
            results.add(new EntryResult(entry, OutboxStatus.PENDING, entry.getAttempts(), null));
        }
    }

    /** The feed-row ids of the batch's {@code INAPP_PUSH} entries (their {@code aggregate_id}). */
    private static List<UUID> inAppPushIds(List<NotificationOutboxEntry> batch) {
        return batch.stream()
                .filter(e -> e.getEventType() == OutboxEventType.INAPP_PUSH)
                .map(NotificationOutboxEntry::getAggregateId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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

    /**
     * The lead bookings of the batch's visit-aware entries — the input to
     * {@link BookingVisitResolver#hydrate(java.util.Collection)}. An entry whose booking is missing
     * from the cache is skipped silently here; {@link #getBooking} raises the real error for it
     * inside the loop, where the failure is attributed to that one entry rather than to the batch.
     */
    private List<Booking> visitAwareLeads(List<NotificationOutboxEntry> batch, Map<UUID, Booking> bookingCache) {
        return batch.stream()
                .filter(e -> VISIT_AWARE_EVENTS.contains(e.getEventType()))
                .map(e -> bookingCache.get(e.getAggregateId()))
                .filter(Objects::nonNull)
                .toList();
    }

    private void dispatch(NotificationOutboxEntry entry, Map<UUID, Booking> bookingCache,
                          Map<UUID, List<Booking>> visitSiblings,
                          Map<UUID, InAppPushDispatcher.PushPlan> pushPlans) {
        switch (entry.getEventType()) {
            // The two visit-aware events: one outbox row describes the WHOLE visit, so the sibling
            // booking rows are hydrated here (see BookingVisitResolver) and threaded through every
            // channel. Cardinality is untouched — still one row, one notification, N services named.
            case NEW_BOOKING      -> notificationService.notifyNewBooking(getVisit(entry, bookingCache, visitSiblings));
            case STATUS_CHANGED   -> notificationService.notifyBookingStatusChanged(getVisit(entry, bookingCache, visitSiblings));
            case CLIENT_CANCELLED -> notificationService.notifyClientCancelled(getBooking(entry, bookingCache));
            case BOOKING_RESCHEDULED -> {
                Booking booking = getBooking(entry, bookingCache);
                if (resolveRescheduleInitiatedByProvider(entry)) {
                    notificationService.notifyBookingRescheduledClient(booking);
                } else {
                    notificationService.notifyBookingRescheduled(booking);
                }
            }
            case REVIEW_REQUESTED -> notificationService.notifyReviewRequested(getBooking(entry, bookingCache));
            case CLOSURE_REMINDER -> notificationService.notifyClosureReminder(getBooking(entry, bookingCache));
            case SALON_CLOSED -> notificationService.notifySalonClosed(getVisit(entry, bookingCache, visitSiblings));
            case MASTER_REMOVED -> notificationService.notifyMasterRemoved(getVisit(entry, bookingCache, visitSiblings));
            // Phase 339 — one Android push for one feed row; aggregate_id is the feed row id. The push
            // was rendered from live data in the pre-load block; an id with no plan is a D8 skip
            // (row gone/read, recipient gone or token-less) and is marked SENT without calling FCM.
            case INAPP_PUSH -> {
                InAppPushDispatcher.PushPlan plan = pushPlans.get(entry.getAggregateId());
                if (plan != null) {
                    inAppPushDispatcher.dispatch(plan);
                }
            }
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

    /**
     * Reads the {@code BOOKING_RESCHEDULED} payload's {@code initiatedBy} field (Phase 27.3).
     * {@code true} routes the notification to the client ({@code
     * notifyBookingRescheduledClient}); {@code false} (or an absent/null payload — a PENDING row
     * written before this field existed) keeps the pre-27.3 provider-facing notification, the
     * documented backward-compatible default (see {@code NotificationOutboxService
     * #enqueueBookingRescheduled(UUID, boolean)}).
     */
    private boolean resolveRescheduleInitiatedByProvider(NotificationOutboxEntry entry) {
        String payload = entry.getPayload();
        if (payload == null || payload.isBlank()) {
            return false;
        }
        return "PROVIDER".equals(readJson(payload).get("initiatedBy"));
    }

    /**
     * Resolves the cached lead booking into the full {@link BookingVisit}, purely in memory: both
     * the booking and its chained siblings were loaded by the phase-2 pre-load block, so this issues
     * no statement and takes no connection while dispatch is in flight.
     */
    private BookingVisit getVisit(NotificationOutboxEntry entry, Map<UUID, Booking> cache,
                                  Map<UUID, List<Booking>> visitSiblings) {
        return visitResolver.resolve(getBooking(entry, cache), visitSiblings);
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
