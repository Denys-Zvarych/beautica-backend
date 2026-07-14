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
    private static final int MAX_ATTEMPTS = 3;
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
     * Self-proxy reference so that {@link #drain()} can call the three phase methods
     * through the Spring AOP proxy and have their {@code @Transactional} annotations
     * honoured. Direct {@code this.claimBatch()} calls bypass the proxy and leave
     * {@code MANDATORY} propagation on {@code claimPendingBatch()} without a surrounding
     * transaction (Fix HIGH-4 self-invocation AOP bypass).
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
     * <p><b>Phase 3</b> ({@code REQUIRES_NEW} tx): persist the status updates
     * collected in phase 2 using dirty-checking on re-attached entries.
     *
     * <p>Worst-case phase 2 duration: {@code BATCH_SIZE × SMTP_TIMEOUT_SECS}
     * (e.g. 20 × 20s = 400s) — but zero DB connections are held during that time.
     */
    @Scheduled(fixedDelay = 5_000)
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
     * Phase 3 — persist the dispatch outcomes inside a short {@code REQUIRES_NEW}
     * transaction. Applies the computed status fields directly to the entity objects
     * (which are re-attached to the new session by JPA merge semantics on save, or
     * flushed via dirty-checking if they were still managed). Uses
     * {@code outboxRepository.save()} to guarantee persistence in both managed and
     * detached scenarios.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistResults(List<EntryResult> results) {
        for (EntryResult result : results) {
            NotificationOutboxEntry entry = result.entry();
            entry.setStatus(result.status());
            entry.setAttempts(result.attempts());
            entry.setLastError(result.lastError());
            outboxRepository.save(entry);
        }
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
