package com.beautica.booking.closure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The Phase 29.6 concurrency-safe claim, and the Phase 29.7 dry-run reporting queries that read
 * the SAME candidate window without claiming it.
 */
public interface ClosureReminderRepository extends JpaRepository<BookingClosureReminder, UUID> {

    /**
     * Atomically claims up to {@code maxPerProviderPerDay} elapsed {@code CONFIRMED} bookings PER
     * MASTER whose {@code endsAt} falls in {@code [windowStart, windowEnd]}, and returns the
     * booking ids actually claimed — the {@code RETURNING} set, not a prior read, decides which
     * bookings this call "owns". Mirrors {@code
     * NotificationOutboxRepository#claimPendingBatch}'s atomic-claim discipline: this repo has no
     * ShedLock, Railway performs rolling deploys, and this single statement is what makes two
     * concurrent instances contend safely on {@code booking_closure_reminders}'s primary key
     * instead of double-claiming.
     *
     * <p><b>{@code status = 'CONFIRMED' AND ends_at <= :windowEnd AND ends_at >= :windowStart} is
     * a native re-expression of {@link com.beautica.booking.domain.BookingClosureRule#awaitingClosure(OffsetDateTime)}
     * intersected with the lookback window</b> — this is a native bulk claim statement, so it
     * cannot delegate to the {@link org.springframework.data.jpa.domain.Specification} form.
     * Equivalence with the canonical rule (restricted to the same window) is pinned by {@code
     * ClosureReminderJobIT#should_agreeWithCanonicalRule_when_comparingNativeClaimCandidatesToBookingClosureRule}
     * — this is the ONE place in the codebase the rule is expressed twice, and it is fenced by
     * that equivalence test. Do not add a second ad-hoc "elapsed and unclosed" expression anywhere
     * else.
     *
     * <p><b>Per-provider cap</b> is enforced by the {@code ROW_NUMBER() OVER (PARTITION BY
     * b.master_id ORDER BY b.ends_at ASC)} window, ordered oldest-first, so the cap is
     * deterministic (the {@code maxPerProviderPerDay} OLDEST eligible bookings win) rather than
     * arbitrary — filtered inside the same statement (never a Java post-filter, which would still
     * have claimed the rows it then discarded).
     *
     * <p><b>Dedupe</b> is enforced by the {@code ON CONFLICT (booking_id) DO UPDATE ... WHERE}
     * clause: a booking already at {@code reminder_count >= maxPerBooking}, or reminded within the
     * last day ({@code last_sent_at >= dedupeCooldownBefore}), fails the conflict-action
     * {@code WHERE} and is therefore NOT returned by {@code RETURNING} — Postgres's documented
     * behaviour for a suppressed {@code ON CONFLICT DO UPDATE}. {@code dedupeCooldownBefore} is
     * computed by the caller as {@code now.minusDays(1)} from the injected {@link
     * java.time.Clock} (Anti-Bug §G) — never {@code NOW() - INTERVAL} in SQL — so it is
     * consistently testable with a frozen clock.
     *
     * <p>Must run inside an active transaction — same {@code Propagation.MANDATORY} contract as
     * {@code NotificationOutboxRepository#claimPendingBatch}, enforced here for the identical
     * reason: without a surrounding transaction, this statement's row locks are released the
     * instant it returns, defeating the whole point of the atomic claim.
     *
     * <p><b>Global cap (backend-perf MEDIUM — outbox FIFO starvation).</b> {@code
     * maxPerProviderPerDay} bounds claims PER MASTER, but nothing previously bounded the RUN'S
     * total row count — with {@code distinct_masters × maxPerProviderPerDay} unbounded, a platform
     * with thousands of masters could, in one run, enqueue tens of thousands of {@code
     * CLOSURE_REMINDER} rows into the SAME {@code notification_outbox} the drain worker services
     * strictly {@code ORDER BY created_at ASC} (see {@code
     * NotificationOutboxRepository#claimPendingBatch}) — every row created after that burst (a
     * fresh {@code BOOKING_CONFIRMED}, a {@code REVIEW_REQUESTED}) would queue behind it. The outer
     * {@code ORDER BY c.ends_at ASC LIMIT :maxTotalClaimsPerRun}, applied AFTER the per-provider
     * {@code rn} filter, caps the run's total INSERT attempts and — since it orders by the SAME
     * oldest-first key the per-provider window already uses — always prefers the platform's oldest
     * unclosed bookings first, so a run that hits the cap simply "catches up" further on the next
     * scheduled run rather than silently dropping arbitrary candidates. See {@link
     * ClosureReminderProperties#getMaxTotalClaimsPerRun()} for the default and hard clamp.
     *
     * <p><b>Dedupe pre-filter (backend-perf HIGH — permanently-stuck rows starving the global cap,
     * cycle-2 fix).</b> The global {@code LIMIT} above sits BEFORE the {@code ON CONFLICT ... WHERE}
     * dedupe guard runs. A booking already at {@code reminder_count >= maxPerBooking} can NEVER
     * satisfy that guard again — {@code ends_at} never changes for a booking that stays {@code
     * CONFIRMED} — so once enough PERMANENTLY-exhausted bookings occupy the globally-oldest {@code
     * ends_at} positions to fill {@code maxTotalClaimsPerRun} on their own, EVERY run claims zero
     * rows, forever (or until they age out of the lookback window), even with thousands of
     * legitimate never-reminded candidates sitting just past the cutoff. This is a silent liveness
     * bug: no exception, no distinguishing log signature from an ordinary quiet day. Fixed by the
     * {@code LEFT JOIN booking_closure_reminders r} + {@code AND (r.booking_id IS NULL OR
     * r.reminder_count < :maxPerBooking)} clause below, INSIDE the ranked subquery, BEFORE {@code
     * ROW_NUMBER()}/{@code ORDER BY}/{@code LIMIT} run — a row that can NEVER AGAIN satisfy the
     * {@code ON CONFLICT ... WHERE} guard (because it is already at the dedupe ceiling) is excluded
     * from ranking and from the global cap entirely, so it can no longer consume either budget.
     *
     * <p><b>The pre-filter is deliberately NARROWER than the {@code ON CONFLICT ... WHERE} guard —
     * it omits the {@code last_sent_at < dedupeCooldownBefore} half on purpose.</b> An earlier
     * version of this fix used the FULL guard predicate (reminder-count ceiling AND cooldown) as
     * the pre-filter, which looked more "obviously correct" (same predicate in both places, fenced
     * by an equivalence test) but broke a DIFFERENT, already-tested invariant: {@code
     * ClosureReminderJobIT#should_neverDoubleClaim_when_twoThreadsRaceConcurrently} (Control 4 —
     * the ShedLock substitute) failed deterministically, claiming 5 bookings for a single master
     * against a {@code maxPerProviderPerDay} of 3, across two overlapping (not microsecond-exact)
     * invocations. Root cause: once the FIRST invocation's claimed rows are committed, they carry
     * {@code last_sent_at = now}, which fails the cooldown check — a pre-filter that ALSO checks
     * cooldown then excludes those rows from ranking entirely, which lets the SECOND invocation's
     * {@code ROW_NUMBER()} re-rank the master's REMAINING un-claimed bookings from scratch and
     * promote them into {@code rn <= maxPerProviderPerDay}, claiming MORE for that master than the
     * per-run cap allows — exactly the "rolling deploy double-invocation must not double the claim
     * volume" property Control 4 exists to guarantee. A row that is merely COOLING DOWN (not yet at
     * the dedupe ceiling) is NOT the bug this fix targets — it self-heals within {@code
     * dedupeCooldownBefore}'s ~1-day window (the very next scheduled run reclaims it, spending its
     * OWN remaining {@code maxPerBooking} budget exactly as intended) and, critically, must keep
     * occupying its {@code rn} slot dedupe-blind so a second overlapping run's ranking is stable.
     * Only a row that is PERMANENTLY unclaimable ({@code reminder_count >= maxPerBooking}, which
     * never decreases and never times out) creates the unbounded, ever-growing squatter problem
     * backend-perf actually found — so the pre-filter checks ONLY that half of the guard.
     *
     * <p>This necessarily expresses the {@code reminder_count >= maxPerBooking} half of the dedupe
     * predicate in TWO places (here and the {@code ON CONFLICT ... WHERE} guard below, which keeps
     * BOTH halves) — mirrors the {@link com.beautica.booking.domain.BookingClosureRule} pattern
     * already used in this class, and is fenced the same way: {@code
     * ClosureReminderJobIT#should_admitExactlyNonExhaustedRows_when_comparingDedupePreFilterToReminderCountCeiling}
     * proves {@link #findDedupeEligibleCandidateIds} (the standalone query using the IDENTICAL
     * predicate text as this pre-filter) admits a row if and only if {@code reminder_count <
     * maxPerBooking} (or no ledger row exists), and that a real claim over that same fixture never
     * returns a row the pre-filter excluded (soundness) while still correctly leaving a
     * cooldown-throttled-but-not-exhausted row unclaimed via the {@code ON CONFLICT} guard, exactly
     * as before this fix. The {@code ON CONFLICT ... WHERE} guard is NOT weakened or removed — it
     * remains the only atomically race-safe, FULLY precise (reminder-count AND cooldown) check; the
     * pre-filter is a strictly-conservative candidate-selection optimisation on top of it (it only
     * ever removes rows that are UNCONDITIONALLY, permanently unclaimable), never a replacement.
     *
     * <p>This fix also closes the SAME flaw at the per-provider level: the {@code rn} window was
     * previously computed over ALL candidates for a master, including permanently-exhausted ones —
     * an exhausted booking sitting at the OLDEST {@code ends_at} for its master could occupy {@code
     * rn <= maxPerProviderPerDay} slots that a legitimate, never-reminded booking for the same
     * master needed, starving that master's daily cap exactly the way the global cap was starved.
     * Because the pre-filter excludes permanently-exhausted rows before {@code ROW_NUMBER()} runs
     * at all (not just before the outer {@code LIMIT}), both the per-provider and the global
     * starvation paths are fixed by the same change — while cooldown-only rows still occupy their
     * {@code rn} slot exactly as before, preserving Control 4's concurrent-safety guarantee.
     *
     * <p><b>Index evidence (backend-perf MEDIUM — undocumented plan choice; RE-MEASURED cycle-3,
     * 2026-08, against the CURRENT dedupe-pre-filter {@code LEFT JOIN} shape above — the prior
     * numbers here described the pre-{@code LEFT JOIN} statement and no longer apply).</b> The
     * inner {@code bookings} scan is still satisfied by {@code idx_bookings_master_slot_overlap}
     * ({@code (master_id, starts_at, ends_at) WHERE status = 'CONFIRMED'}, V26/V113) even though
     * the query supplies NO equality bound on the index's leading {@code master_id} column, for
     * the same reason as before — {@code ends_at} is present in the index tuple. But the {@code
     * LEFT JOIN booking_closure_reminders r} added by the cycle-2 dedupe pre-filter changed TWO
     * things about the plan versus the pre-join measurement:
     * <ul>
     *   <li><b>{@code Index Scan}, not {@code Index Only Scan}.</b> The join needs {@code b.id}
     *       (the join key), which is not a column in {@code idx_bookings_master_slot_overlap}, so
     *       every row now takes a heap fetch instead of being satisfied from the visibility map.</li>
     *   <li><b>A real quicksort, not the free {@code Incremental Sort}.</b> At realistic ledger
     *       scale Postgres builds the join as a {@code Hash Right Join}, which destroys the {@code
     *       (master_id, ends_at)} presortedness {@code idx_bookings_master_slot_overlap} gave the
     *       unjoined query, so the window function's {@code ORDER BY} now pays for a real sort.</li>
     * </ul>
     * Measured (this cycle, 2026-08, local dev DB: 100,028 seeded {@code bookings}, with {@code
     * booking_closure_reminders} seeded to 20,600 rows inside a transaction rolled back after
     * measurement — 10,000 rows for {@code COMPLETED} bookings, 10,000 for {@code NOT_COMPLETED},
     * and 300+300 rows for still-{@code CONFIRMED} bookings inside the lookback window at/near the
     * dedupe ceiling, approximating a platform that has already reminded roughly a fifth of its
     * all-time booking volume — never committed to the real local DB, which has an empty ledger):
     * {@code EXPLAIN (ANALYZE, BUFFERS)} on the real {@link #claimDueReminders} statement (default
     * {@code maxPerProviderPerDay=3}, {@code maxPerBooking=2}, {@code maxTotalClaimsPerRun=1000},
     * 14-day lookback) showed 596 buffers for the candidate-selection subquery alone, 855 buffers /
     * 3.84ms for the complete {@code INSERT ... ON CONFLICT} statement (selection + write + the
     * {@code booking_closure_reminders_booking_id_fkey} trigger check); {@link
     * #findWindowAggregates} against the identical window showed 598 buffers / 2.99ms. Plan for
     * both: {@code Hash Right Join} (Seq Scan on {@code booking_closure_reminders} — 305 buffers at
     * 20,600 rows — as the hash build side; {@code Index Scan}, not {@code Index Only}, on {@code
     * idx_bookings_master_slot_overlap} — 288 buffers, 751 matching rows — as the probe side)
     * feeding a quicksort, confirming both plan changes above. As before this cycle, the {@code
     * bookings}-side scan is NOT range-bound to the window — a near-empty window still touches
     * roughly the same ~288 buffers as the full 14-day window — so that side's cost still scales
     * with total platform {@code CONFIRMED} row count, not window size, unchanged from the earlier
     * finding.
     *
     * <p><b>Second scaling axis (cycle-3 note).</b> The {@code LEFT JOIN} introduces an INDEPENDENT
     * cost driver on top of the one above: every {@link #claimDueReminders} / {@link
     * #findWindowAggregates} call now also scans (to hash-build, or — at small enough sizes, per
     * the empty-ledger control below — to probe) the WHOLE {@code booking_closure_reminders}
     * ledger. Unlike {@code notification_outbox}, which {@code NotificationOutboxRepository}'s
     * drain worker keeps bounded by a 30-day purge, this ledger has NO TTL and grows monotonically
     * with the cumulative count of DISTINCT bookings ever reminded, for the platform's whole
     * lifetime — it only ever gets bigger. Confirmed empirically: run against this local DB's
     * actual (unseeded, empty) ledger, the planner instead picks a {@code Nested Loop Left Join}
     * that repeatedly probes {@code booking_closure_reminders} (trivially cheap at 0 rows) and,
     * because that join shape does not disturb ordering, KEEPS the {@code Incremental Sort} (388
     * buffers / 3.26ms total, {@code Presorted Key: b.master_id} still honoured) — i.e. this
     * finding's own two plan changes are themselves a function of ledger size, not a fixed
     * regression. Backend-perf's assessment is that this growth is likely self-correcting, because
     * {@code booking_id} is the ledger's own primary key: once the ledger is large enough that
     * scanning it wholesale costs more than probing it once per window row (the window side stays
     * bounded at roughly the platform's currently-elapsed-and-unclosed {@code CONFIRMED} count,
     * NOT the ledger's ever-growing total), Postgres should flip back to a {@code Nested Loop}
     * probing {@code booking_closure_reminders_pkey} — which would re-bound the join's own cost by
     * the window side again, independent of ledger size. This repo's own measurements so far only
     * bracket the two ends actually observed (0 rows: {@code Nested Loop} / {@code Incremental
     * Sort}, 388 buffers; 20,600 rows: {@code Hash Right Join} / quicksort, 855 buffers — already
     * ~2.2x the pre-{@code LEFT JOIN} baseline this note previously cited, direct evidence the
     * ledger's growth already costs something) — the actual crossover point back to a PK-probing
     * {@code Nested Loop} has NOT been measured and must not be assumed to have already arrived;
     * re-run this same {@code EXPLAIN (ANALYZE, BUFFERS)} comparison against the then-current
     * ledger size if this scan is ever flagged again by the trigger threshold below. No new index
     * is added for the ledger side by this note alone — self-correction via the existing PK is the
     * hypothesis to verify first, not a partial index to add pre-emptively.
     *
     * <p><b>Trigger threshold:</b> once platform-wide {@code CONFIRMED} row count OR the {@code
     * booking_closure_reminders} ledger grows enough that this scan exceeds roughly 10ms
     * (order-of-magnitude — re-measure before committing to a number), add a purpose-built partial
     * index {@code (ends_at) WHERE status = 'CONFIRMED'} scoped to exactly this predicate, and
     * re-run this same {@code EXPLAIN (ANALYZE, BUFFERS)} comparison to confirm it actually gets
     * chosen over {@code idx_bookings_master_slot_overlap} before committing the migration.
     *
     * @param windowStart          lookback ceiling — bookings older than this are never claimed
     * @param windowEnd            lookback floor — bookings more recent than this are never claimed
     * @param now                  the resolved instant to stamp {@code last_sent_at} with
     * @param maxPerProviderPerDay per-master cap on claims in this single run
     * @param maxPerBooking        dedupe ceiling — a booking is never claimed more than this many times, ever
     * @param dedupeCooldownBefore a previously-reminded booking is only reclaimed if its {@code
     *                             last_sent_at} is strictly before this instant (same-day re-run guard)
     * @param maxTotalClaimsPerRun global cap on total INSERT attempts in this single run, applied
     *                             oldest-{@code endsAt}-first, AFTER the per-provider cap
     * @return the booking ids actually claimed by THIS call — never {@code null}, may be empty
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            INSERT INTO booking_closure_reminders (booking_id, reminder_count, last_sent_at)
            SELECT c.id, 1, :now
            FROM (
                SELECT b.id, b.ends_at,
                       ROW_NUMBER() OVER (PARTITION BY b.master_id ORDER BY b.ends_at ASC) AS rn
                FROM bookings b
                LEFT JOIN booking_closure_reminders r ON r.booking_id = b.id
                WHERE b.status = 'CONFIRMED'
                  AND b.ends_at <= :windowEnd
                  AND b.ends_at >= :windowStart
                  AND (r.booking_id IS NULL OR r.reminder_count < :maxPerBooking)
            ) c
            WHERE c.rn <= :maxPerProviderPerDay
            ORDER BY c.ends_at ASC
            LIMIT :maxTotalClaimsPerRun
            ON CONFLICT (booking_id) DO UPDATE
                SET reminder_count = booking_closure_reminders.reminder_count + 1,
                    last_sent_at   = :now
                WHERE booking_closure_reminders.reminder_count < :maxPerBooking
                  AND booking_closure_reminders.last_sent_at < :dedupeCooldownBefore
            RETURNING booking_id
            """, nativeQuery = true)
    List<UUID> claimDueReminders(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("now") OffsetDateTime now,
            @Param("maxPerProviderPerDay") int maxPerProviderPerDay,
            @Param("maxPerBooking") int maxPerBooking,
            @Param("dedupeCooldownBefore") OffsetDateTime dedupeCooldownBefore,
            @Param("maxTotalClaimsPerRun") int maxTotalClaimsPerRun);

    /**
     * The raw candidate id set in the window — BEFORE any per-provider cap or dedupe. Exists
     * purely for {@code ClosureReminderJobIT}'s native-claim &#8801; canonical-rule equivalence
     * test (comparing this set, restricted to a test fixture, against {@code
     * BookingClosureRule.awaitingClosure(now)} intersected with the same window) — no production
     * caller needs the id list itself, only the count ({@link #findWindowAggregates}).
     */
    @Query(value = """
            SELECT b.id
              FROM bookings b
             WHERE b.status = 'CONFIRMED'
               AND b.ends_at <= :windowEnd
               AND b.ends_at >= :windowStart
            """, nativeQuery = true)
    List<UUID> findCandidateIds(@Param("windowStart") OffsetDateTime windowStart, @Param("windowEnd") OffsetDateTime windowEnd);

    /**
     * The dedupe pre-filter predicate expressed EXACTLY as it appears in {@link
     * #claimDueReminders}'s ranked subquery (the {@code LEFT JOIN booking_closure_reminders r} +
     * {@code AND (r.booking_id IS NULL OR r.reminder_count < :maxPerBooking)} clause) — restricted
     * to the window, WITHOUT the per-provider {@code rn} cap or the global {@code LIMIT}. This is
     * the SECOND place in the codebase this half of the dedupe predicate is written out (the first
     * is inside the {@code ON CONFLICT ... WHERE} guard on {@link #claimDueReminders}, which ALSO
     * keeps the cooldown half — see that method's javadoc for why the pre-filter is deliberately
     * narrower than the full guard). This method's id set is therefore a SUPERSET of what an actual
     * claim run returns — it admits every never-exhausted row, including ones a real claim would
     * still suppress for being on cooldown; it is sound (never excludes a row the guard would still
     * accept) but not the complete claim decision by itself.
     *
     * <p>Exists purely for {@code
     * ClosureReminderJobIT#should_admitExactlyNonExhaustedRows_when_comparingDedupePreFilterToReminderCountCeiling}
     * — no production caller needs this method; production always goes through the single atomic
     * {@link #claimDueReminders} statement.
     */
    @Query(value = """
            SELECT b.id
              FROM bookings b
              LEFT JOIN booking_closure_reminders r ON r.booking_id = b.id
             WHERE b.status = 'CONFIRMED'
               AND b.ends_at <= :windowEnd
               AND b.ends_at >= :windowStart
               AND (r.booking_id IS NULL OR r.reminder_count < :maxPerBooking)
            """, nativeQuery = true)
    List<UUID> findDedupeEligibleCandidateIds(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("maxPerBooking") int maxPerBooking);

    /**
     * The Phase 29.7 dry-run report's {@code candidates}, {@code cappedByProviderLimit}, {@code
     * oldestCandidateEndsAt}, and {@code newestCandidateEndsAt} figures, computed in ONE scan of
     * the window's candidate set via {@code FILTER (WHERE …)}/{@code MIN}/{@code MAX} aggregates
     * over a single {@code ROW_NUMBER()} derived table.
     *
     * <p><b>backend-perf MEDIUM fix — {@code buildResult} used to fire 5 independent scans per
     * run</b> ({@code countCandidates}, {@code countCappedByProviderLimit}, {@code
     * countDistinctProviders}, {@code findOldestCandidateEndsAt}, {@code
     * findNewestCandidateEndsAt}), each re-scanning the SAME {@code status = 'CONFIRMED' AND
     * ends_at BETWEEN window} predicate the claim statement above already computed —
     * ~1.3–2.4ms each, and — per this method's own index-evidence note on {@link
     * #claimDueReminders} — NOT bounded by window size, so the 5x multiplier scaled with total
     * platform {@code CONFIRMED} row count. This method collapses four of those five into one
     * query; {@link #countDistinctProviders} stays separate because it filters on the claim's
     * {@code RETURNING} id set, not the window bounds, and cannot share this aggregate pass.
     *
     * <p>{@code MIN}/{@code MAX} return {@link Instant}, not {@link OffsetDateTime} — same
     * pgjdbc raw-native-scalar rationale as the methods this replaces: a native-query scalar
     * {@code TIMESTAMPTZ} comes back from pgjdbc as an {@code Instant}, and Spring Data does not
     * convert it for a directly-returned scalar the way Hibernate converts an entity-mapped {@code
     * OffsetDateTime} attribute. Callers convert via {@code OffsetDateTime.ofInstant(...,
     * ZoneOffset.UTC)} — see {@link ClosureReminderClaimService#buildResult}. On an empty
     * candidate set, {@code COUNT(*)}/the FILTER counts are {@code 0} (an aggregate query with no
     * {@code GROUP BY} always returns exactly one row) and {@code MIN}/{@code MAX} are {@code
     * null}, matching the null semantics of the methods this replaces.
     *
     * <p><b>{@code excludedByDedupe} (cycle-2 fix — re-derived alongside the {@link
     * #claimDueReminders} pre-filter).</b> {@code capped_by_provider_limit} must now be measured
     * over the SAME reduced candidate set the claim statement actually ranks — a permanently-
     * exhausted booking ({@code reminder_count >= maxPerBooking}) never reaches {@code
     * ROW_NUMBER()} in the claim statement, so it must never reach it here either, or this method's
     * {@code cappedByProviderLimit} would silently diverge from what the claim statement produces.
     * Deliberately checks ONLY {@code reminder_count < maxPerBooking} — NOT the cooldown clause —
     * mirroring {@link #claimDueReminders}'s pre-filter exactly (see that method's javadoc for why
     * including cooldown here would corrupt this figure the same way it would corrupt the claim's
     * own per-provider concurrent-safety guarantee). This stays a SINGLE scan: {@code rn} is
     * computed with {@code PARTITION BY b.master_id, dedupe_eligible}, which computes the exact
     * same per-master oldest-first ranking among only the non-exhausted rows as the claim
     * statement's {@code rn} (the exhausted partition's {@code rn} values are never read — {@code
     * capped_by_provider_limit}'s {@code FILTER} only looks at {@code c.dedupe_eligible} rows).
     * {@code candidate_count} deliberately stays the RAW count of every {@code CONFIRMED} booking
     * in the window (unaffected by dedupe state) — it is the "how many bookings are sitting in this
     * window at all" figure the existing dry-run log line and {@code ClosureReminderGlobalCapIT}
     * already rely on; {@code excludedByDedupe} is the new figure that explains how much of {@code
     * candidateCount} is PERMANENTLY exhausted and therefore removed from ranking before it starts.
     *
     * <p><b>Call ordering matters (cycle-2 fix).</b> {@link ClosureReminderClaimService} MUST call
     * this method BEFORE {@link #claimDueReminders} runs in the same transaction — see {@code
     * ClosureReminderClaimService#fetchAggregates}'s javadoc. Calling it after would make this
     * query see the SAME transaction's own just-inserted claim rows (read-committed "read your own
     * writes"), corrupting {@code cappedByProviderLimit} by treating freshly-claimed bookings as
     * newly dedupe-relevant and re-ranking the remainder.
     */
    @Query(value = """
            SELECT COUNT(*)                                                                    AS candidate_count,
                   COUNT(*) FILTER (WHERE NOT c.dedupe_eligible)                                AS excluded_by_dedupe,
                   COUNT(*) FILTER (WHERE c.dedupe_eligible AND c.rn > :maxPerProviderPerDay)    AS capped_by_provider_limit,
                   MIN(c.ends_at)                                                                AS oldest_ends_at,
                   MAX(c.ends_at)                                                                AS newest_ends_at
              FROM (
                  SELECT b.ends_at,
                         (r.booking_id IS NULL OR r.reminder_count < :maxPerBooking) AS dedupe_eligible,
                         ROW_NUMBER() OVER (
                             PARTITION BY b.master_id,
                                          (r.booking_id IS NULL OR r.reminder_count < :maxPerBooking)
                             ORDER BY b.ends_at ASC
                         ) AS rn
                    FROM bookings b
                    LEFT JOIN booking_closure_reminders r ON r.booking_id = b.id
                   WHERE b.status = 'CONFIRMED'
                     AND b.ends_at <= :windowEnd
                     AND b.ends_at >= :windowStart
              ) c
            """, nativeQuery = true)
    ClosureReminderWindowAggregates findWindowAggregates(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("maxPerProviderPerDay") int maxPerProviderPerDay,
            @Param("maxPerBooking") int maxPerBooking);

    /**
     * How many DISTINCT masters own the given (already-claimed) booking ids — the Phase 29.7
     * dry-run report's {@code providersAffected} figure. Callers MUST guard the empty-collection
     * case themselves (an empty {@code IN ()} is invalid SQL) — see {@code
     * ClosureReminderClaimService}.
     */
    @Query(value = "SELECT COUNT(DISTINCT b.master_id) FROM bookings b WHERE b.id IN (:ids)", nativeQuery = true)
    long countDistinctProviders(@Param("ids") Collection<UUID> ids);
}
