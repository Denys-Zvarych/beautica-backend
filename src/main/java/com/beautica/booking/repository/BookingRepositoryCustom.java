package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingPartition;
import com.beautica.booking.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Custom fragment backing the dynamic-{@link org.springframework.data.jpa.domain.Specification}
 * ID-page queries introduced by the Phase 26.1 audit fix (Finding 1 — HIGH, backend-perf).
 * Implemented by {@link BookingRepositoryCustomImpl}; see {@link BookingSpecifications} for the
 * full rationale on why the prior {@code (:statuses IS NULL OR …)} sentinel idiom was replaced.
 *
 * <p><b>Every method here requires a PAGED {@link Pageable}.</b> {@code Pageable.unpaged()} is
 * rejected with {@link IllegalArgumentException} rather than executed: an unbounded
 * {@code SELECT b.id} over a caller's whole booking history, fully hydrated afterwards, is never a
 * valid query on this fragment (Anti-Bug §E-3). {@code BookingService#normalizeBookingSort}
 * preserves {@code Unpaged} for its own callers, so the refusal has to live at this boundary.
 */
public interface BookingRepositoryCustom {

    /**
     * Callers must supply the authenticated user's own {@code master.id} — never an arbitrary
     * UUID (Anti-Bug §E-4; enforced by {@link BookingSpecifications#masterIdEquals}).
     * {@code statuses == null || statuses.isEmpty()} means "no status predicate" — the predicate
     * is omitted from the query entirely rather than bound as a null sentinel.
     *
     * <p><b>Phase 26.3 — ordering is {@code Pageable}-driven.</b> {@code pageable.getSort()} is
     * translated 1:1 into Criteria {@code Order}s by {@code BookingRepositoryCustomImpl.findIdPage}.
     * The {@code Sort} MUST already be validated and normalized by the caller
     * ({@code BookingService#normalizeBookingSort}) before it reaches here: whitelisted property
     * names only (no dot-paths — this method has no way to reject an arbitrary property path),
     * a guaranteed non-empty {@code Sort} (defaulted to {@code startsAt DESC} when the caller's
     * was unsorted), and a trailing unique-column tiebreaker. This repository method trusts that
     * contract and does not re-validate it.
     *
     * <p><b>Phase 26.2 — optional date range.</b> {@code from}/{@code toExclusive} are
     * already-resolved {@code Europe/Kyiv} instants (never {@code LocalDate} — the day/zone
     * conversion happens once in {@code BookingService#getMyBookings}); {@code null} means "no
     * predicate", mirroring the {@code statuses} contract exactly.
     *
     * <p><b>Phase 26.4 — optional service filter.</b> {@code serviceIds} is a caller-supplied,
     * de-duplicated, size-bounded (max 50 — see {@code BookingService#getMyBookings}) set of
     * {@code MasterService} ids; {@code null} or empty means "no predicate", same optional
     * contract as {@code statuses}/date range. Matches {@code b.masterService.id}, never
     * {@code b.masterService.serviceDefinition.id}.
     */
    Page<UUID> findIdsByMasterIdFiltered(
            UUID masterId, Collection<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable);

    /**
     * Callers must supply a pre-resolved, non-empty list of the authenticated owner's OWN active
     * salon ids (Anti-Bug §E-4; enforced by {@link BookingSpecifications#salonIdIn}). Same
     * status/ordering/date-range/service-filter contract as {@link #findIdsByMasterIdFiltered} —
     * including the Phase 26.3 pre-validated-{@code Sort} requirement, the Phase 26.2 optional
     * already-resolved-instant date bounds, and the Phase 26.4 optional bounded service filter.
     */
    Page<UUID> findIdsBySalonIdsFiltered(
            List<UUID> salonIds, Collection<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable);

    /**
     * Client-side counterpart to {@link #findIdsByMasterIdFiltered} (Phase 26.7.1 — closes the
     * client half of the sentinel work this class opened for the provider paths in Phase 26.1).
     * Callers must supply the authenticated user's own id as {@code clientId} — never an
     * arbitrary UUID (Anti-Bug §E-4; enforced by {@link BookingSpecifications#clientIdEquals}).
     * Same optional-predicate contract as {@link #findIdsByMasterIdFiltered}:
     * {@code statuses}/{@code serviceIds} of {@code null} or empty, and {@code from}/
     * {@code toExclusive} of {@code null}, all mean "no predicate" — omitted entirely from the
     * emitted SQL rather than bound as a runtime-null sentinel.
     *
     * <p>Backs {@code BookingService#listClientBookings}: this method returns the sargable,
     * sentinel-free ID page + count; the caller then hydrates via
     * {@code BookingRepository#hydrateClientBookingDetails(Collection)} — the
     * {@code SELECT new ClientBookingDetailProjection(...)} JPQL, unchanged, including its five
     * PII salon-precedence {@code CASE WHEN} expressions — and re-imposes this page's order onto
     * the hydrated rows, exactly mirroring the provider path's
     * {@code findIdsByMasterIdFiltered} + {@code findAllByIdsWithGraph} two-query pattern.
     *
     * <p>Same Phase 26.3 pre-validated-{@code Sort} contract as {@link #findIdsByMasterIdFiltered}
     * — the {@code Pageable} passed here MUST already be normalized by
     * {@code BookingService#normalizeBookingSort} before this method is invoked.
     */
    Page<UUID> findIdsByClientIdFiltered(
            UUID clientId, Collection<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable);

    // ── Phase 28.1/28.2 — GET /bookings/me?partition= ──────────────────────────────────────────
    //
    // Deliberately THREE NEW, distinctly-named methods rather than two extra params bolted onto
    // the three methods above. `partition` and `status` are mutually exclusive (§ precedence rule
    // — see BookingService#getMyBookings): a caller either filters by status/no-filter (the three
    // methods above, UNCHANGED) or by partition (these three), never both. Routing to a genuinely
    // separate method — never the same method with a `partition == null` branch bolted on — is
    // what makes the "absent `partition` ⇒ byte-identical to today" backwards-compatibility
    // contract airtight at the type level: the pre-28.1 methods above are not touched by a single
    // line, so every pre-28.1 caller (production and test) keeps compiling and behaving
    // identically. BookingService#listClientBookings/#listProviderBookings dispatch to whichever
    // family applies.

    /**
     * Partition counterpart to {@link #findIdsByMasterIdFiltered}. Same scope-predicate contract
     * (Anti-Bug §E-4 — the authenticated user's OWN {@code master.id}, never an arbitrary UUID)
     * and the same Phase 26.2/26.4 optional date-range/service-filter contract. {@code status} is
     * NOT a parameter here — {@code partition} composes {@link
     * BookingSpecifications#partition(BookingPartition, OffsetDateTime)} instead of {@link
     * BookingSpecifications#statusIn}, never both (this is what "status is ignored when partition
     * is present" means at the query layer: the status predicate is never even constructible from
     * this method's parameter list).
     *
     * <p>{@code now} is an already-resolved absolute instant (see {@link
     * BookingSpecifications#partition}'s javadoc for the clock/timezone invariant) — resolved
     * once in {@code BookingService#getMyBookings} from {@code Clock#instant()}, never computed
     * here.
     */
    Page<UUID> findIdsByMasterIdFilteredByPartition(
            UUID masterId, BookingPartition partition, OffsetDateTime now,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable);

    /** Partition counterpart to {@link #findIdsBySalonIdsFiltered} — see {@link
     * #findIdsByMasterIdFilteredByPartition} for the full contract. */
    Page<UUID> findIdsBySalonIdsFilteredByPartition(
            List<UUID> salonIds, BookingPartition partition, OffsetDateTime now,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable);

    /** Partition counterpart to {@link #findIdsByClientIdFiltered} — see {@link
     * #findIdsByMasterIdFilteredByPartition} for the full contract. */
    Page<UUID> findIdsByClientIdFilteredByPartition(
            UUID clientId, BookingPartition partition, OffsetDateTime now,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable);

    /**
     * Phase 29.4 — a bare {@code COUNT(*)} over an arbitrary {@link Specification}, backing
     * {@code GET /bookings/me/unclosed-count}. Deliberately generic (unlike every other method on
     * this interface, which is a named, purpose-specific query) because the caller
     * ({@code BookingService#getUnclosedCount}) composes its own scope-plus-closure {@link
     * Specification} and needs exactly one {@code SELECT COUNT(*)} for it — never a hydrated
     * {@link Page} whose {@code getTotalElements()} is read for its side effect (that would load
     * rows only to discard them), and never a per-scope-entry count loop.
     */
    long count(Specification<Booking> spec);
}
