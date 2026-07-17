package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

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
}
