package com.beautica.master.dto;

import java.util.List;

/**
 * Response envelope for {@code POST /api/v1/masters/{masterId}/overrides/conflicts} (backend-perf
 * audit finding 4, 2026-07-26 design). Wraps {@link OverrideConflictResponse} with an explicit
 * truncation signal rather than returning an unbounded {@code List} or silently dropping rows past
 * the cap: a requested {@code [from, to]} range can span up to 366 days, so without a cap this
 * endpoint could return an unbounded number of conflicts.
 *
 * <p>Not a {@code Pageable}/{@code PageResponse<T>} (the project's usual list convention): this is a
 * POST with a body-driven query shape (kind/mode/intervals/times), not a plain paginated GET, so
 * there is no natural {@code ?page=}/{@code ?size=} query-param home for page-number semantics. The
 * mobile client renders a single scrollable list plus a count for this screen, so a boolean flag it
 * can turn into "show N more" messaging serves the UX better than either a hard error past the cap
 * or a silently incomplete list.
 *
 * <p><b>{@code scanTruncated} (backend-perf audit finding 3, 2026-07-26 re-audit).</b> The
 * underlying candidate scan itself is capped (independent of, and earlier than, the
 * {@code truncated}/result cap above) so this endpoint's memory/row count cannot scale unbounded
 * with a master's total confirmed-booking volume across a range of up to 366 days. When
 * {@code scanTruncated} is {@code true}, {@code totalCount} is no longer guaranteed to be the TRUE
 * total — it is only a LOWER BOUND, computed over the rows the scan managed to load. This is a
 * SEPARATE signal from {@code truncated} on purpose: a caller can tell "there are more conflicts
 * than are shown" ({@code truncated}) apart from "even the count itself may be incomplete"
 * ({@code scanTruncated}) — see {@code ScheduleOverrideConflictService#MAX_CANDIDATES_SCANNED}'s
 * javadoc for when this can actually occur (in practice, an implausibly large confirmed-booking
 * volume for one master).
 *
 * @param conflicts     up to the service's result cap, in the same order the underlying query
 *                      returns them (ascending {@code startsAt})
 * @param totalCount    the number of conflicts found among the candidates the scan loaded — the TRUE
 *                      total when {@code scanTruncated} is {@code false}; otherwise only a lower
 *                      bound. May exceed {@code conflicts.size()} when {@code truncated} is
 *                      {@code true}
 * @param truncated     {@code true} iff {@code totalCount} exceeds the service's result cap and
 *                      {@code conflicts} was cut off — never a silent drop with no signal
 * @param scanTruncated {@code true} iff the underlying candidate SCAN itself hit its own row cap
 *                      before every {@code CONFIRMED} booking in range could be loaded — see above
 */
public record OverrideConflictPreviewResponse(
        List<OverrideConflictResponse> conflicts,
        int totalCount,
        boolean truncated,
        boolean scanTruncated
) {
}
