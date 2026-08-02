package com.beautica.booking.closure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate outcome of one {@link ClosureReminderClaimService} run (live or dry-run) — exactly
 * the numbers the Phase 29.7 dry-run log line reports, per the locked track-25 "notes/PII are
 * never logged" rule: no booking id appears in the log line itself (the {@link #claimedIds} list
 * only backs {@link #claimable()} and, in the live path, which ids to enqueue — {@link
 * ClosureReminderJob} never logs {@link #claimedIds} directly).
 *
 * <p>{@link #cappedByGlobalLimit} (backend-perf MEDIUM fix, Finding 4 — the global per-run cap)
 * exists so {@link #suppressedByDedupe} stays an ACCURATE observable rather than silently
 * absorbing rows the global cap dropped. Without this field, {@code
 * ClosureReminderClaimService#buildResult} would attribute every post-provider-cap row the global
 * {@code LIMIT} truncated to "dedupe", which is simply false and would mislead the human reviewing
 * dry-run counts during the Phase 29.7 rollout gate this record exists to serve.
 *
 * <p>{@link #excludedByDedupe} (backend-perf HIGH fix, cycle 2 — permanently-stuck rows starving
 * the global/per-provider caps) is the newest split: rows PERMANENTLY exhausted ({@code
 * reminder_count >= maxPerBooking}) that the pre-filter rules out BEFORE ranking (see {@code
 * ClosureReminderRepository#claimDueReminders}'s pre-filter) never occupy a {@code
 * cappedByProviderLimit} or {@code cappedByGlobalLimit} slot at all — they are removed from the
 * competition entirely, not merely capped out of it. The exact partition is: {@code candidateCount
 * == excludedByDedupe + cappedByProviderLimit + cappedByGlobalLimit + suppressedByDedupe +
 * claimable}. Rows merely COOLING DOWN (not yet exhausted) are deliberately NOT in {@link
 * #excludedByDedupe} — the pre-filter still admits them into ranking (see that method's javadoc
 * for why: excluding cooldown-only rows from ranking would let a second overlapping run re-rank
 * the remainder and claim MORE than the per-provider cap allows, breaking Control 4's
 * concurrent-safety guarantee) — so {@link #suppressedByDedupe} is where a cooldown-throttled
 * candidate that made it into ranking, but then failed the {@code ON CONFLICT ... WHERE} guard's
 * cooldown check, actually lands. Non-zero {@link #suppressedByDedupe} is therefore routine (any
 * recently-but-not-permanently reminded booking still in the window produces it), not solely a
 * concurrency signal.
 */
public record ClosureReminderRunResult(
        List<UUID> claimedIds,
        long candidateCount,
        long excludedByDedupe,
        long cappedByProviderLimit,
        long cappedByGlobalLimit,
        long suppressedByDedupe,
        long providersAffected,
        OffsetDateTime oldestCandidateEndsAt,
        OffsetDateTime newestCandidateEndsAt) {

    public long claimable() {
        return claimedIds.size();
    }
}
