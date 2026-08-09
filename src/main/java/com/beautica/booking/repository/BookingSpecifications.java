package com.beautica.booking.repository;

import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingPartition;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Composable {@link Specification} predicates backing the {@code GET /bookings/me} provider
 * ID-page query family (Phase 26.1 audit fix, Finding 1 — HIGH, backend-perf).
 *
 * <p><b>Design decision — why the sentinel idiom was rejected.</b> The pre-fix
 * {@code findIdsByMasterIdFiltered} / {@code findIdsBySalonIdsFiltered} used a single static
 * JPQL string with {@code (:statuses IS NULL OR b.status IN :statuses)}. On a 503k-row synthetic
 * table, backend-perf reproduced the following: PgJDBC's default {@code prepareThreshold=5}
 * means Postgres re-plans this statement as a GENERIC plan after its 5th execution — a plan that
 * cannot see the bound parameter value at plan time and therefore cannot fold away the dead
 * {@code OR} branch:
 * <pre>
 * Custom plan:  Index Scan + LIMIT pushdown        -&gt; 0.041 ms
 * Generic plan: Bitmap Heap Scan + top-N heapsort  -&gt; 0.555 ms
 *               Filter: (($2 IS NULL) OR ((status)::text = ANY ($2)))
 * </pre>
 * ~19x slower — and the gap SCALES with the provider's total row count, so it punishes the
 * busiest masters/salons hardest. Pre-26.1 these two scopes had a dedicated no-filter query with
 * no status predicate at all (a structurally guaranteed clean plan); the 26.1 collapse traded
 * that guarantee away for a single shared code path.
 *
 * <p>The fix keeps the single-code-path goal but builds the {@code WHERE} dynamically instead of
 * encoding "no predicate" as a runtime-null sentinel: the emitted SQL contains ONLY the
 * predicates actually requested. "Scope only" and "scope + status" are two distinct SQL texts,
 * so each gets its own Postgres plan-cache entry and neither ever contains a branch the planner
 * has to reason about blind. This also composes cleanly for Phase 26.2 (date range) and Phase
 * 26.4 (service filter) — both are expected to add sibling {@code Specification} factory methods
 * here rather than growing a second sentinel onto the JPQL string.
 *
 * <p>Scope predicates ({@link #masterIdEquals} / {@link #salonIdIn}) are intentionally separate,
 * hard, non-nullable factory methods — never folded into an optional filter — so a caller cannot
 * accidentally compose a query with no scope predicate at all. A nullable scope predicate would
 * be a horizontal privilege escalation (Anti-Bug §E-4); backend-security verified these scope
 * checks survived the 26.1 collapse and they must survive this one too.
 */
public final class BookingSpecifications {

    private BookingSpecifications() {
    }

    /**
     * Hard scope predicate: {@code b.master.id = :masterId}. ALWAYS required — never made
     * optional or combined conditionally — callers must supply the authenticated user's own
     * {@code master.id} (resolved via {@code masterRepository.findByUserId(actorUserId)}), never
     * an arbitrary UUID.
     */
    public static Specification<Booking> masterIdEquals(UUID masterId) {
        return (root, query, cb) -> cb.equal(root.get("master").get("id"), masterId);
    }

    /**
     * Hard scope predicate: {@code b.client.id = :clientId} (Phase 26.7.1). ALWAYS required —
     * never made optional or combined conditionally, mirroring {@link #masterIdEquals} exactly.
     * Callers must supply the authenticated client's own user id — never an arbitrary UUID
     * (Anti-Bug §E-4). This is what lets {@code findClientBookingDetails}'s three-sentinel
     * {@code WHERE} (see that method's javadoc) be replaced by a sargable ID page: the ownership
     * boundary now lives here, on the ID-page {@link Specification}, instead of inline in the
     * JPQL projection string.
     */
    public static Specification<Booking> clientIdEquals(UUID clientId) {
        return (root, query, cb) -> cb.equal(root.get("client").get("id"), clientId);
    }

    /**
     * Hard scope predicate: {@code JOIN b.master m JOIN m.salon s WHERE s.id IN :salonIds}.
     * ALWAYS required. Mirrors the pre-fix JPQL join shape exactly. Callers must supply a
     * pre-resolved, non-empty list of the authenticated owner's OWN active salon ids (via
     * {@code salonRepository.findIdsByOwnerIdAndIsActiveTrue}).
     */
    public static Specification<Booking> salonIdIn(List<UUID> salonIds) {
        return (root, query, cb) -> {
            Join<Booking, Master> master = root.join("master");
            Join<Master, Salon> salon = master.join("salon");
            return salon.get("id").in(salonIds);
        };
    }

    /**
     * Optional predicate: {@code b.status IN :statuses}. Callers must omit this predicate
     * entirely (never call this method / never bind a null) when the caller supplied no status
     * filter — that is what keeps the emitted SQL free of the dead-branch shape this fix removes.
     */
    public static Specification<Booking> statusIn(Collection<BookingStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /**
     * Optional predicate: {@code b.startsAt >= :from} (Phase 26.2 date-range filter). Callers
     * must omit this predicate entirely — never call this method / never bind a null — when the
     * caller supplied no {@code from} bound, same optional-predicate contract as {@link #statusIn}.
     * {@code from} is an already-resolved {@code Europe/Kyiv} start-of-day instant; the
     * {@code LocalDate -> OffsetDateTime} conversion happens in {@code BookingService}, never here
     * (this class stays instant-only and zone-agnostic).
     */
    public static Specification<Booking> startsAtOnOrAfter(OffsetDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startsAt"), from);
    }

    /**
     * Optional predicate: {@code b.startsAt < :toExclusive} (Phase 26.2 date-range filter).
     * Deliberately named {@code toExclusive}, not {@code to} — this is the HALF-OPEN upper bound
     * that makes an inclusive-of-the-whole-day {@code to} actually inclusive once
     * {@code BookingService} resolves it as {@code to.plusDays(1).atStartOfDay(KYIV)}. A
     * {@code <=} predicate here would silently drop every booking after 00:00 Kyiv on the final
     * day — do not reintroduce it.
     */
    public static Specification<Booking> startsAtBefore(OffsetDateTime toExclusive) {
        return (root, query, cb) -> cb.lessThan(root.get("startsAt"), toExclusive);
    }

    /**
     * Optional predicate: {@code b.masterService.id IN :serviceIds} (Phase 26.4 service filter,
     * backing the design's «Послуга» multi-select). Callers must omit this predicate entirely —
     * never call this method / never bind a null or empty collection — when the caller supplied
     * no {@code serviceId} filter, same optional-predicate contract as {@link #statusIn} /
     * {@link #startsAtOnOrAfter}.
     *
     * <p>Filters on {@code masterService.id} — the direct {@code @ManyToOne} FK {@link Booking}
     * already carries — never {@code masterService.serviceDefinition.id}. This resolves via
     * {@link jakarta.persistence.criteria.Path#get(String)} as an implicit single-valued
     * navigation (a correlated subselect-free {@code =}/{@code IN} against the FK column), not an
     * explicit {@link jakarta.persistence.criteria.Join} — so, like {@link #masterIdEquals}, it
     * adds no join and no {@code DISTINCT} requirement; the separate graph-hydrate query
     * ({@code findAllByIdsWithGraph}) still does the actual {@code JOIN FETCH b.masterService}.
     * {@code BookingService} de-duplicates and bounds {@code serviceIds} to 50 entries before this
     * predicate is ever composed — see {@code BookingService#getMyBookings}.
     */
    public static Specification<Booking> masterServiceIdIn(Collection<UUID> serviceIds) {
        return (root, query, cb) -> root.get("masterService").get("id").in(serviceIds);
    }

    /**
     * Optional predicate: {@code b.endsAt >= :now} — the {@code UPCOMING} half-open bound of the
     * Phase 28.1 {@code partition} filter (see {@link #partition}). Never called standalone by
     * {@code BookingService}; composed only inside {@link #partition}, mirroring how {@link
     * #startsAtOnOrAfter} is a building block rather than a caller-facing filter in its own right
     * for this feature. Callers must omit this predicate entirely — never bind a null — when no
     * partition boundary applies, same optional-predicate contract as every other method here.
     *
     * <p><b>{@code now} MUST be an already-resolved absolute instant</b> — an {@link
     * OffsetDateTime} derived from {@link java.time.Clock#instant()} (e.g. {@code
     * clock.instant().atOffset(ZoneOffset.UTC)}), resolved ONCE in {@code BookingService}, exactly
     * like {@code from}/{@code toExclusive} are resolved once before reaching {@link
     * #startsAtOnOrAfter}/{@link #startsAtBefore}. {@link com.beautica.common.TimeZones#KYIV}
     * MUST NOT appear anywhere in this method, {@link #endsAtBefore}, or {@link #partition} — the
     * {@code UPCOMING}/{@code PAST} split is an absolute-instant comparison against the stored
     * {@code TIMESTAMPTZ ends_at} column ({@code Booking.endsAt} is an {@link OffsetDateTime},
     * never derived from {@code startsAt + duration} at read time), never a calendar-day boundary.
     * Kyiv governs exactly two things elsewhere in this service — resolving the day-granular
     * {@code from}/{@code to} params and wall-clock display — conflating instant-ordering with
     * Kyiv-day derivation is this repo's live bug class; do not reintroduce it here.
     */
    public static Specification<Booking> endsAtOnOrAfter(OffsetDateTime now) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("endsAt"), now);
    }

    /**
     * Optional predicate: {@code b.endsAt < :now} — the {@code PAST} half-open bound of the Phase
     * 28.1 {@code partition} filter (see {@link #partition}). Deliberately the strict {@code <}
     * counterpart to {@link #endsAtOnOrAfter}'s {@code >=}: a booking whose {@code endsAt} exactly
     * equals {@code now} lands in {@code UPCOMING} and only there, so the two predicates are
     * disjoint and total over any instant. Same clock/timezone invariants as {@link
     * #endsAtOnOrAfter} apply — read that method's javadoc before touching this one.
     */
    public static Specification<Booking> endsAtBefore(OffsetDateTime now) {
        return (root, query, cb) -> cb.lessThan(root.get("endsAt"), now);
    }

    /**
     * Optional predicate composing the Phase 28.1 {@code GET /bookings/me?partition=} filter.
     * Callers must omit this predicate entirely — never call this method / never bind a null —
     * when the caller supplied no {@code partition}, same optional-predicate contract as every
     * other method in this class. Per the controller/service precedence rule, a caller that DOES
     * supply {@code partition} must NOT also apply {@link #statusIn} — the two are mutually
     * exclusive, {@code partition} always wins (see {@code BookingController#listMyBookings} and
     * {@code BookingService#getMyBookings} for where that precedence is enforced).
     *
     * <pre>
     * UPCOMING    status = CONFIRMED                       AND ends_at &gt;= :now
     * PAST        status IN (COMPLETED, NOT_COMPLETED)
     *             OR (status = CONFIRMED                    AND ends_at &lt;  :now)
     * CANCELLED   status IN (CANCELLED, DECLINED)
     * </pre>
     *
     * <p><b>Why {@code cb.or(...)} here is a deliberate, scoped exception — not a style
     * regression to "clean up".</b> This is the FIRST use of {@link
     * jakarta.persistence.criteria.CriteriaBuilder#or} anywhere in {@code com.beautica} — every
     * other optional predicate in this class, and every composition in {@code
     * BookingRepositoryCustomImpl}, chains with {@code .and(...)} only. {@code PAST} is the sole
     * case in the entire {@code GET /bookings/me} filter surface that genuinely needs a boolean
     * OR, because it unions two independently-true conditions: "closed and done" ({@code
     * COMPLETED}/{@code NOT_COMPLETED}, regardless of when the visit's window ended) and "still
     * nominally open but its window has already elapsed" — an unclosed {@code CONFIRMED} row that
     * no job ever transitions (see {@link BookingPartition}'s javadoc, and {@code
     * BookingTemporalGuard#assertElapsedForComplete}, which gates completion on {@code startsAt}
     * — NOT {@code endsAt} — so a provider can legitimately produce a {@code COMPLETED} booking
     * whose {@code endsAt} is still in the FUTURE; that row must land in {@code PAST} too, which
     * is why the {@code COMPLETED}/{@code NOT_COMPLETED} branch carries no {@code endsAt}
     * condition at all). Building this OR INSIDE one method, as a single {@link
     * jakarta.persistence.criteria.Predicate} tree, is what lets the caller {@code .and()} the
     * result onto the scope predicate exactly like every other optional predicate here — an OR
     * assembled instead via two separate {@code .and()} chains combined with {@link
     * Specification#or} at the {@code BookingRepositoryCustomImpl} call site would NOT distribute
     * correctly over the already-chained scope/date-range/service {@code .and()} predicates, so it
     * must be self-contained here. Do not split this back out "for symmetry" with the AND-only
     * methods above — the asymmetry is the point.
     *
     * <p><b>Clock/timezone invariants</b> — see {@link #endsAtOnOrAfter}'s javadoc; they apply
     * identically here. {@code now} must already be an absolute-instant {@link OffsetDateTime}
     * resolved once in {@code BookingService} from {@link java.time.Clock#instant()}; {@link
     * com.beautica.common.TimeZones#KYIV} must never appear in this method.
     *
     * <p><b>Phase 28.4 audit fix (Finding 1 — HIGH, backend-perf) — the redundant
     * {@code status IN (COMPLETED, NOT_COMPLETED, CONFIRMED)} conjunct on the {@code PAST}
     * branch.</b> This extra {@code cb.and(...)} is a LOGICAL NO-OP — every disjunct of the
     * {@code cb.or(...)} it wraps already requires one of those three statuses, so it can never
     * change which rows match. It exists purely as a query-planner hint, proven necessary by
     * {@code EXPLAIN (ANALYZE, BUFFERS)} evidence recorded in {@code
     * docs/backend-phases/phase-216-28.1-booking-partition-enum-and-specifications.md} (Finding 1
     * write-up) against a seeded 50,000-row single-master dataset:
     *
     * <ul>
     *   <li>Without this conjunct, at a deep {@code OFFSET} (~20,000 rows in) Postgres cannot
     *       prove the query implies any partial index scoped to {@code status IN (...)}, so it
     *       falls back to a {@code Seq Scan} over the ENTIRE {@code bookings} table (not just this
     *       master's rows) plus an explicit {@code Sort} — 17.7ms, and the cost SCALES WITH THE
     *       GLOBAL TABLE SIZE, exactly the "scales with total row count" pattern Anti-Bug §E
     *       warns about.</li>
     *   <li>With this conjunct present AND the {@code idx_bookings_master_past_partition_starts_at}
     *       partial index (V130) in place, Postgres matches the conjunct directly against the
     *       index's {@code WHERE} clause (a trivial syntactic match — no cross-clause implication
     *       proof required) and scans only THIS MASTER's own eligible rows instead of the whole
     *       table — decoupling cost from global table growth even though, at this test's specific
     *       scale, the planner's default statistics on {@code status} still make it prefer a
     *       {@code Bitmap Index Scan} (~16ms) over the theoretically-optimal plain ordered
     *       {@code Index Scan} (~4.9ms, confirmed by forcing {@code enable_bitmapscan = off}).
     *       Both are real, measured improvements over the no-index baseline; see the phase doc for
     *       the full plan output of all three variants.</li>
     * </ul>
     *
     * <p>Do not remove this conjunct "for simplification" — without it, V130's index is silently
     * dead weight (write-amplification with zero read benefit) because Postgres never proves the
     * unmodified {@code cb.or(...)} implies the index's partial predicate.
     *
     * <p><b>Phase 29.1 — the {@code PAST} branch's second OR-leg delegates to {@link
     * BookingClosureRule#awaitingClosure(OffsetDateTime)}</b> instead of re-writing {@code
     * statusIn(CONFIRMED).and(endsAtBefore(now))} inline. {@link BookingClosureRule} is now the
     * single canonical definition of "an elapsed CONFIRMED booking awaits closure" — this delegate
     * call is byte-for-byte the same predicate composition the inline expression produced before,
     * so it changes no query result and no query plan.
     *
     * <p><b>Phase 29.3 — the standalone {@code AWAITING_CLOSURE} arm.</b> Gives that same OR-leg a
     * name a caller can request on its own ({@code GET /bookings/me?partition=AWAITING_CLOSURE}).
     * {@link BookingPartition#AWAITING_CLOSURE} is a NAMED SUBSET OF {@code PAST}, not a fourth
     * member of the total-disjoint cover — see {@link BookingPartition}'s javadoc. Its predicate is
     * a plain {@code status = 'CONFIRMED'} equality (no OR/AND nesting), so it needs none of the
     * {@code PAST} arm's redundant outer conjunct: Postgres's partial-index implication prover
     * handles {@code status = 'CONFIRMED'} &#8658; {@code status IN ('COMPLETED','NOT_COMPLETED',
     * 'CONFIRMED')} without help — the gap that forced the conjunct is specific to the nested
     * OR/AND shape of {@code PAST}.
     */
    public static Specification<Booking> partition(BookingPartition partition, OffsetDateTime now) {
        return switch (partition) {
            case UPCOMING -> statusIn(EnumSet.of(BookingStatus.CONFIRMED)).and(endsAtOnOrAfter(now));
            case PAST -> (root, query, cb) -> cb.and(
                    statusIn(EnumSet.of(BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED,
                            BookingStatus.CONFIRMED)).toPredicate(root, query, cb),
                    cb.or(
                            statusIn(EnumSet.of(BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED))
                                    .toPredicate(root, query, cb),
                            BookingClosureRule.awaitingClosure(now).toPredicate(root, query, cb)));
            case CANCELLED -> statusIn(EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED));
            case AWAITING_CLOSURE -> BookingClosureRule.awaitingClosure(now);
        };
    }
}
