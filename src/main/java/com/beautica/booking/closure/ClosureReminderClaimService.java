package com.beautica.booking.closure;

import com.beautica.notification.service.NotificationOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Owns the two ways a {@link ClosureReminderJob} run can execute — live (claims for real,
 * enqueues outbox rows) and dry-run (Phase 29.7 — runs the IDENTICAL claim statement, then rolls
 * the transaction back, so the reported {@code claimable} count is exactly what the live path
 * would have produced, never a second "estimate" definition that could drift).
 *
 * <p>This class — not {@link ClosureReminderJob} — is where {@link
 * NotificationOutboxService#enqueueClosureReminder(UUID)} is called, and ONLY for ids {@link
 * ClosureReminderRepository#claimDueReminders} already {@code RETURNING}'d in the SAME
 * transaction. Nothing here reads or writes {@code bookings.status} — see {@code
 * ClosureReminderArchitectureTest}, which scans this whole package.
 */
@Service
@RequiredArgsConstructor
public class ClosureReminderClaimService {

    private final ClosureReminderRepository repository;
    private final NotificationOutboxService outboxService;

    /**
     * Live path — claims real rows and enqueues a {@code CLOSURE_REMINDER} outbox row for each,
     * inside the same transaction as the claim (so an outbox-write failure rolls back the claim
     * too, rather than leaving a booking marked "reminded" with no reminder ever sent).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClosureReminderRunResult claimAndEnqueue(ClosureReminderWindow window) {
        ClosureReminderWindowAggregates aggregates = fetchAggregates(window);
        List<UUID> claimedIds = claim(window);
        for (UUID bookingId : claimedIds) {
            outboxService.enqueueClosureReminder(bookingId);
        }
        return buildResult(window, aggregates, claimedIds);
    }

    /**
     * Dry-run path (Phase 29.7) — runs the SAME {@link ClosureReminderRepository#claimDueReminders}
     * statement the live path uses, in its own {@code REQUIRES_NEW} transaction, then marks that
     * transaction rollback-only before returning. Spring's transaction interceptor converts the
     * pending commit into a rollback transparently (a transaction's OWN {@code setRollbackOnly()}
     * call never raises {@link org.springframework.transaction.UnexpectedRollbackException} back
     * to the caller — that exception is reserved for a PARTICIPATING transaction whose outer
     * caller tries to commit after an inner participant marked it rollback-only, which cannot
     * happen here since this method always opens its own new physical transaction).
     *
     * <p>Deliberately does NOT call {@code outboxService.enqueueClosureReminder} — a dry run must
     * emit ZERO outbox rows (phase-225 acceptance criterion), and the claim rows this method wrote
     * never survive past this method's rollback either.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClosureReminderRunResult dryRun(ClosureReminderWindow window) {
        ClosureReminderWindowAggregates aggregates = fetchAggregates(window);
        List<UUID> claimedIds = claim(window);
        ClosureReminderRunResult result = buildResult(window, aggregates, claimedIds);
        markRollbackOnly();
        return result;
    }

    private List<UUID> claim(ClosureReminderWindow window) {
        return repository.claimDueReminders(
                window.windowStart(),
                window.windowEnd(),
                window.now(),
                window.maxPerProviderPerDay(),
                window.maxPerBooking(),
                window.dedupeCooldownBefore(),
                window.maxTotalClaimsPerRun());
    }

    /**
     * Runs {@link ClosureReminderRepository#findWindowAggregates} BEFORE {@link #claim} — this
     * ordering is load-bearing, not incidental. {@code findWindowAggregates} now joins {@code
     * booking_closure_reminders} (the cycle-2 dedupe-pre-filter fix), and a transaction always sees
     * its OWN uncommitted writes (read-committed "read your own writes"). If this ran AFTER {@code
     * claim}, any booking {@code claim} JUST pushed to {@code reminder_count == maxPerBooking} in
     * THIS SAME transaction (a booking that had already been reminded {@code maxPerBooking - 1}
     * times before this run — routine with the default {@code maxPerBooking = 2}, not an edge
     * case) would look freshly PERMANENTLY-exhausted to this query, corrupting {@code
     * cappedByProviderLimit} in two ways: undercounting that row's own contribution, AND silently
     * shifting the {@code rn} rank of every OTHER still-eligible booking for the same master into a
     * lower position (since {@code rn} is assigned only among the remaining non-exhausted rows),
     * hiding excess rows that ARE still over the per-provider cap. Calling this first guarantees it
     * reads the exact SAME pre-claim ledger snapshot {@link #claim}'s own pre-filter subquery reads
     * (nothing else writes to {@code booking_closure_reminders} between these two statements in the
     * same transaction).
     */
    private ClosureReminderWindowAggregates fetchAggregates(ClosureReminderWindow window) {
        return repository.findWindowAggregates(
                window.windowStart(), window.windowEnd(), window.maxPerProviderPerDay(), window.maxPerBooking());
    }

    /**
     * Reports the dry-run/live-run stats from exactly TWO queries — down from the original five
     * (backend-perf MEDIUM: {@code countCandidates}/{@code countCappedByProviderLimit}/{@code
     * findOldestCandidateEndsAt}/{@code findNewestCandidateEndsAt} each independently re-scanned
     * the identical window predicate the claim statement itself already evaluated). {@link
     * ClosureReminderRepository#findWindowAggregates} computes the first five figures in one pass;
     * {@link ClosureReminderRepository#countDistinctProviders} necessarily stays separate — it
     * filters on the claim's {@code RETURNING} id set, not the window bounds, so it cannot share
     * the window aggregate's scan. Both dry-run and live paths call this identically, so the
     * dry-run report's numbers stay exactly what the live path would have produced.
     *
     * <p><b>Counter partition (re-derived, cycle-2 fix).</b> {@link
     * ClosureReminderRepository#claimDueReminders} now pre-filters PERMANENTLY-exhausted rows
     * ({@code reminder_count >= maxPerBooking} — never cooldown-only rows, see that method's
     * javadoc for why the distinction is load-bearing) OUT of the ranked subquery entirely, before
     * {@code ROW_NUMBER()}/the per-provider cap/the global {@code LIMIT} ever run — a permanently
     * stuck row was previously able to occupy the globally-oldest ranking positions forever and
     * starve the whole run to zero claims. The five terms below are an EXACT partition of {@code
     * candidateCount} — every candidate lands in precisely one bucket:
     * <pre>
     * candidateCount == excludedByDedupe + cappedByProviderLimit + cappedByGlobalLimit
     *                   + suppressedByDedupe + claimable
     * </pre>
     * <ul>
     *   <li>{@code excludedByDedupe} — NEW bucket. Rows PERMANENTLY exhausted ({@code
     *       reminder_count >= maxPerBooking}) that the pre-filter rules out before ranking even
     *       starts; these never had a chance to compete for a provider or global slot. Rows merely
     *       cooling down are NOT in this bucket — see below.</li>
     *   <li>{@code cappedByProviderLimit} — unchanged NAME, changed MEANING: now measured only over
     *       the non-exhausted remainder ({@code candidateCount - excludedByDedupe}), matching what
     *       the claim statement's {@code rn} actually ranks.</li>
     *   <li>{@code cappedByGlobalLimit} — of the non-exhausted, provider-cap-surviving remainder,
     *       how many the outer {@code LIMIT} truncates. Unchanged formula, applied to the smaller
     *       (exhausted-rows-removed) base.</li>
     *   <li>{@code suppressedByDedupe} — the residual gap between what the claim statement
     *       ATTEMPTED to insert after the global cap and what it actually {@code RETURNING}'d. This
     *       is the bucket cooldown-throttled rows now fall into (the pre-filter deliberately still
     *       admits them into ranking — see {@link ClosureReminderRepository#claimDueReminders}
     *       javadoc — so the {@code ON CONFLICT ... WHERE} guard's cooldown half is what actually
     *       suppresses them here), plus any genuine same-statement cross-instance concurrency race
     *       on the {@code ON CONFLICT} arbiter. Non-zero here is EXPECTED and routine whenever a
     *       recently-reminded, not-yet-exhausted booking is still inside the window — see {@code
     *       ClosureReminderDryRunIT#should_matchClaimableCount_when_comparingDryRunToLiveOnIdenticalFreshFixtures}.</li>
     *   <li>{@code claimable} — {@code claimedIds.size()}, the rows actually {@code RETURNING}'d.</li>
     * </ul>
     */
    private ClosureReminderRunResult buildResult(
            ClosureReminderWindow window, ClosureReminderWindowAggregates aggregates, List<UUID> claimedIds) {
        long candidateCount = aggregates.getCandidateCount();
        long excludedByDedupe = aggregates.getExcludedByDedupe();
        long cappedByProviderLimit = aggregates.getCappedByProviderLimit();
        long providersAffected = claimedIds.isEmpty() ? 0 : repository.countDistinctProviders(claimedIds);

        // eligibleBeforeGlobalCap is dedupe-pre-filtered AND post-provider-cap: the row count the
        // claim statement's ROW_NUMBER() filter alone would allow through, now measured over the
        // SAME reduced (dedupe-eligible) base the claim statement itself ranks. The global LIMIT
        // then truncates that further, BEFORE the ON CONFLICT dedupe guard ever runs (see
        // ClosureReminderRepository#claimDueReminders) — so attributing every row NOT ultimately
        // claimed to "dedupe" would be wrong once the global cap binds. cappedByGlobalLimit
        // isolates exactly the rows the global LIMIT dropped; suppressedByDedupe is computed
        // against what actually reached the ON CONFLICT clause, not against the pre-global-cap
        // figure.
        long eligibleBeforeGlobalCap = candidateCount - excludedByDedupe - cappedByProviderLimit;
        long attemptedAfterGlobalCap = Math.min(eligibleBeforeGlobalCap, window.maxTotalClaimsPerRun());
        long cappedByGlobalLimit = eligibleBeforeGlobalCap - attemptedAfterGlobalCap;
        long suppressedByDedupe = Math.max(0, attemptedAfterGlobalCap - claimedIds.size());

        OffsetDateTime oldest = toOffsetDateTime(aggregates.getOldestEndsAt());
        OffsetDateTime newest = toOffsetDateTime(aggregates.getNewestEndsAt());

        return new ClosureReminderRunResult(
                claimedIds, candidateCount, excludedByDedupe, cappedByProviderLimit, cappedByGlobalLimit,
                suppressedByDedupe, providersAffected, oldest, newest);
    }

    /** See {@link ClosureReminderRepository#findWindowAggregates} for why the repository returns {@link Instant}, not {@link OffsetDateTime}, from a raw native scalar. */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void markRollbackOnly() {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }
}
