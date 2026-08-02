package com.beautica.booking.closure;

import java.time.Instant;

/**
 * Spring Data native-query interface projection for {@link
 * ClosureReminderRepository#findWindowAggregates}. Backs the Phase 29.7 dry-run report's {@code
 * candidates}, {@code cappedByProviderLimit}, {@code oldestCandidateEndsAt}, and {@code
 * newestCandidateEndsAt} figures — all four computed in a SINGLE scan of the window's candidate
 * set (backend-perf MEDIUM finding: these were previously four independent queries, each
 * re-scanning the identical predicate).
 *
 * <p>Getters follow Spring Data's relaxed native-projection naming convention — {@code
 * candidate_count} → {@link #getCandidateCount()}, etc. See {@link
 * com.beautica.salon.repository.SalonSearchProjection} (a sibling native-query interface
 * projection) for the same snake_case-alias convention used elsewhere in this codebase.
 */
public interface ClosureReminderWindowAggregates {

    /** Every {@code CONFIRMED} booking whose {@code endsAt} falls in the window — see {@code
     * ClosureReminderRepository#findWindowAggregates} javadoc. */
    long getCandidateCount();

    /** Of {@link #getCandidateCount()}, how many are PERMANENTLY exhausted ({@code reminder_count
     * >= maxPerBooking}, which never decreases and never times out) — excluded from ranking
     * entirely by {@code ClosureReminderRepository#claimDueReminders}'s pre-filter (cycle-2 fix)
     * BEFORE the per-provider {@code rn} cap or the global {@code LIMIT} ever run, so these rows
     * can no longer consume either budget. Deliberately does NOT include rows merely cooling down
     * (not yet exhausted) — see {@link ClosureReminderRepository#claimDueReminders} javadoc for why
     * that distinction is load-bearing for Control 4's concurrent-safety guarantee. */
    long getExcludedByDedupe();

    /** Of {@link #getCandidateCount()} MINUS {@link #getExcludedByDedupe()} (i.e. of the
     * dedupe-eligible rows only), how many are dropped purely by the per-provider-per-day cap. */
    long getCappedByProviderLimit();

    /** The oldest candidate's {@code endsAt} in the window, or {@code null} if there are none. See
     * {@code ClosureReminderRepository#findWindowAggregates} for the {@link Instant} (not {@code
     * OffsetDateTime}) return-type rationale. */
    Instant getOldestEndsAt();

    /** The newest candidate's {@code endsAt} in the window, or {@code null} if there are none. */
    Instant getNewestEndsAt();
}
