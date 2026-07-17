package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.Collection;
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
}
