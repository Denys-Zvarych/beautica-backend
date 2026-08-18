package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingPartition;
import com.beautica.booking.enums.BookingStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.support.PageableExecutionUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Criteria-API implementation of {@link BookingRepositoryCustom} (Phase 26.1 audit fix, Finding
 * 1 — HIGH, backend-perf). See {@link BookingSpecifications} for the full design rationale.
 *
 * <p>{@code EntityManager} is field-injected via {@link PersistenceContext} — the documented
 * Spring exception to constructor injection, mirroring {@code SearchService}'s own use of the
 * same annotation for the same reason (Spring intercepts it specially to supply a
 * transaction-aware shared proxy). This class becomes a real Spring bean via Spring Data's
 * repository-fragment scanning (the {@code <CustomInterfaceName>Impl} naming convention), so
 * standard field injection applies.
 *
 * <p>Deliberately does NOT delegate to {@code JpaSpecificationExecutor.findAll(Specification,
 * Pageable)} — that convenience method selects full {@link Booking} rows, which would break the
 * two-query ID-page + graph-hydrate pattern this seam must preserve
 * ({@code BookingService.listProviderBookings}). {@code BookingRepository} deliberately does not
 * extend {@code JpaSpecificationExecutor} for the same reason — this remains true after Phase
 * 26.3. Building the {@link CriteriaQuery} by hand keeps the {@code SELECT b.id} projection while
 * still — as of Phase 26.3 — deriving {@code ORDER BY} from {@code pageable.getSort()} (see
 * {@link #findIdPage}).
 */
class BookingRepositoryCustomImpl implements BookingRepositoryCustom {

    /**
     * Message for the {@link IllegalArgumentException} every entry point raises on an
     * {@code Unpaged} {@link Pageable} — see {@link #findIdPage} for why unpaged is refused here
     * rather than silently executed as an unbounded scan. Never reaches a client: no HTTP path can
     * produce an {@code Unpaged} instance.
     */
    static final String UNPAGED_REJECTED_MESSAGE =
            "Pageable.unpaged() is not supported by BookingRepositoryCustom — "
                    + "an unbounded booking id scan is never a valid query; pass a paged Pageable";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<UUID> findIdsByMasterIdFiltered(
            UUID masterId, Collection<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.masterIdEquals(masterId));
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(BookingSpecifications.statusIn(statuses));
        }
        spec = applyDateRange(spec, from, toExclusive);
        spec = applyServiceFilter(spec, serviceIds);
        return findIdPage(spec, pageable);
    }

    @Override
    public Page<UUID> findIdsBySalonIdsFiltered(
            List<UUID> salonIds, Collection<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.salonIdIn(salonIds));
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(BookingSpecifications.statusIn(statuses));
        }
        spec = applyDateRange(spec, from, toExclusive);
        spec = applyServiceFilter(spec, serviceIds);
        return findIdPage(spec, pageable);
    }

    @Override
    public Page<UUID> findIdsByClientIdFiltered(
            UUID clientId, Collection<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.clientIdEquals(clientId));
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(BookingSpecifications.statusIn(statuses));
        }
        spec = applyDateRange(spec, from, toExclusive);
        spec = applyServiceFilter(spec, serviceIds);
        return findIdPage(spec, pageable);
    }

    // ── Phase 28.1/28.2 — GET /bookings/me?partition= (see BookingRepositoryCustom's class-level
    // comment for why these are separate methods, never extra params on the three above) ────────

    @Override
    public Page<UUID> findIdsByMasterIdFilteredByPartition(
            UUID masterId, BookingPartition partition, OffsetDateTime now,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.masterIdEquals(masterId))
                .and(BookingSpecifications.partition(partition, now));
        spec = applyDateRange(spec, from, toExclusive);
        spec = applyServiceFilter(spec, serviceIds);
        return findIdPage(spec, pageable);
    }

    @Override
    public Page<UUID> findIdsBySalonIdsFilteredByPartition(
            List<UUID> salonIds, BookingPartition partition, OffsetDateTime now,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.salonIdIn(salonIds))
                .and(BookingSpecifications.partition(partition, now));
        spec = applyDateRange(spec, from, toExclusive);
        spec = applyServiceFilter(spec, serviceIds);
        return findIdPage(spec, pageable);
    }

    @Override
    public Page<UUID> findIdsByClientIdFilteredByPartition(
            UUID clientId, BookingPartition partition, OffsetDateTime now,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.clientIdEquals(clientId))
                .and(BookingSpecifications.partition(partition, now));
        spec = applyDateRange(spec, from, toExclusive);
        spec = applyServiceFilter(spec, serviceIds);
        return findIdPage(spec, pageable);
    }

    /**
     * Phase 29.4 — public entry point onto the existing {@link #countMatching} helper (previously
     * private, used only as {@link #findIdPage}'s deferred count supplier). Exposed verbatim —
     * same {@link CriteriaQuery}-building logic, no new query shape — so {@code
     * BookingService#getUnclosedCount} gets exactly one {@code SELECT COUNT(*)} statement for an
     * arbitrary caller-composed {@link Specification}.
     */
    @Override
    public long count(Specification<Booking> spec) {
        return countMatching(spec);
    }

    /**
     * Composes the optional Phase 26.2 date-range predicates onto {@code spec} — each bound
     * applied only when non-null, exactly like the {@code statuses} predicate above, so a caller
     * that omits both bounds gets byte-for-byte the same SQL text Phase 26.1 already produces.
     */
    private static Specification<Booking> applyDateRange(
            Specification<Booking> spec, OffsetDateTime from, OffsetDateTime toExclusive) {
        Specification<Booking> result = spec;
        if (from != null) {
            result = result.and(BookingSpecifications.startsAtOnOrAfter(from));
        }
        if (toExclusive != null) {
            result = result.and(BookingSpecifications.startsAtBefore(toExclusive));
        }
        return result;
    }

    /**
     * Composes the optional Phase 26.4 {@code masterService.id IN :serviceIds} predicate onto
     * {@code spec} — applied only when the caller supplied a non-empty set, exactly like
     * {@code statuses} above, so a caller that omits {@code serviceId} entirely gets byte-for-byte
     * the same SQL text Phase 26.2 already produces (no dead {@code IS NULL OR} branch — see
     * {@link BookingSpecifications}'s class javadoc for why that idiom is rejected on this path).
     */
    private static Specification<Booking> applyServiceFilter(
            Specification<Booking> spec, Collection<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return spec;
        }
        return spec.and(BookingSpecifications.masterServiceIdIn(serviceIds));
    }

    /**
     * Executes {@code spec} as a {@code SELECT b.id} query ordered by {@code pageable.getSort()}
     * (Phase 26.3 — previously a HARDCODED {@code ORDER BY b.startsAt DESC}, independent of the
     * caller's {@code Sort}), plus a matching {@code COUNT(*)} query built from the same
     * {@link Specification} — the same id-page-plus-count shape the superseded {@code @Query}
     * methods produced.
     *
     * <p>The count is deferred through {@link PageableExecutionUtils#getPage} rather than executed
     * unconditionally, so Spring Data's standard short-circuit applies (see
     * {@link #countMatching}) — the previous {@code new PageImpl<>(ids, pageable, total)} bypassed
     * it and paid a full-history {@code COUNT(*)} even on a first page that already ended.
     *
     * <p><b>Trusts an already-validated {@code Sort}.</b> {@code pageable.getSort()} is translated
     * 1:1 into Criteria {@link Order}s via {@link Root#get(String)} on each
     * {@link Sort.Order#getProperty()}. This is safe ONLY because both callers
     * ({@code findIdsByMasterIdFiltered} / {@code findIdsBySalonIdsFiltered}) are reached
     * exclusively through {@code BookingService#getMyBookings}, which normalizes and whitelists
     * the {@code Sort} (via {@code normalizeBookingSort}) to {@code startsAt} — plus a trailing
     * {@code id} tiebreaker — before either method is ever invoked (Phase 26.8 narrowed the
     * whitelist from {@code {startsAt, priceAtBooking}}; {@code createdAt} was never a member of
     * it). An unvalidated {@code Sort} reaching this method would let
     * {@link Root#get(String)} resolve an arbitrary property path (including through a join
     * alias), which is exactly the ordering oracle Phase 26.3 closes at the service boundary; do
     * NOT call this method (or its two public callers) with a raw, caller-supplied {@code Sort}.
     *
     * <p><b>{@code Pageable.unpaged()} is UNSUPPORTED and rejected with
     * {@link IllegalArgumentException}.</b> The alternative — skipping
     * {@code setFirstResult}/{@code setMaxResults} for an {@code Unpaged} instance — is not a
     * safety guard but its opposite: it would turn a loud failure into a SILENT UNBOUNDED
     * {@code SELECT b.id} that materialises every matching booking id into a {@code List} and
     * hydrates all of them (Anti-Bug §E-3). Unreachable over HTTP either way — both entry points
     * carry {@code @PageableDefault} and {@code spring.data.web.pageable.max-page-size} — but
     * {@code BookingService#normalizeBookingSort} deliberately PRESERVES {@code Unpaged} for
     * direct in-process callers, so this boundary is where that must be refused. No production
     * caller passes one today; the only {@code Pageable.unpaged()} callers are
     * {@code BookingServiceTest} cases that mock this repository away entirely.
     */
    private Page<UUID> findIdPage(Specification<Booking> spec, Pageable pageable) {
        if (pageable.isUnpaged()) {
            throw new IllegalArgumentException(UNPAGED_REJECTED_MESSAGE);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<UUID> idQuery = cb.createQuery(UUID.class);
        Root<Booking> idRoot = idQuery.from(Booking.class);
        idQuery.select(idRoot.get("id"));
        Predicate idPredicate = spec.toPredicate(idRoot, idQuery, cb);
        if (idPredicate != null) {
            idQuery.where(idPredicate);
        }
        idQuery.orderBy(toCriteriaOrders(cb, idRoot, pageable.getSort()));

        // Unconditional, and safe to be so: the unpaged guard at the top of this method has
        // already rejected the only Pageable for which getOffset()/getPageSize() throw.
        TypedQuery<UUID> typedIdQuery = entityManager.createQuery(idQuery);
        typedIdQuery.setFirstResult((int) pageable.getOffset());
        typedIdQuery.setMaxResults(pageable.getPageSize());
        List<UUID> ids = typedIdQuery.getResultList();

        return PageableExecutionUtils.getPage(ids, pageable, () -> countMatching(spec));
    }

    /**
     * Builds and executes the {@code COUNT(*)} companion to {@link #findIdPage}'s id query, from
     * the same {@link Specification}.
     *
     * <p>Invoked <b>only</b> from inside the {@link PageableExecutionUtils#getPage} supplier, which
     * is why the {@link CriteriaQuery} is constructed here rather than in the caller: Spring Data's
     * short-circuit rules skip the count entirely when the returned content already determines the
     * total — a first page (offset 0) shorter than the page size, or a short last page; the util's
     * third case, an unpaged request, is unreachable here because {@link #findIdPage} rejects
     * {@code Unpaged} outright — and a query built eagerly in the caller would still cost the
     * round trip this indirection exists to avoid. That short-circuit is exactly the common case on
     * {@code GET /bookings/me?size=20}: a user with fewer than 20 bookings previously paid a
     * {@code COUNT(*)} over their entire booking history on every app open.
     *
     * <p>{@code getTotalElements()} stays exact in the skipped cases — the util derives it from
     * {@code offset + content.size()}, which is the true total precisely when the content is short
     * (see {@code PageableExecutionUtils#getPage}). No caller's total changes; only the round trip
     * disappears.
     */
    private long countMatching(Specification<Booking> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Booking> countRoot = countQuery.from(Booking.class);
        countQuery.select(cb.count(countRoot));
        Predicate countPredicate = spec.toPredicate(countRoot, countQuery, cb);
        if (countPredicate != null) {
            countQuery.where(countPredicate);
        }
        return entityManager.createQuery(countQuery).getSingleResult();
    }

    /**
     * Translates a Spring Data {@link Sort} into an ordered list of Criteria {@link Order}s
     * against {@code root} — each {@link Sort.Order} maps to a single simple property path
     * (never a join traversal; the whitelist upstream guarantees this). Property order is
     * preserved so the mandatory {@code id} tiebreaker (appended last by
     * {@code BookingService#normalizeBookingSort}) always sorts after the caller's own criteria.
     */
    private static List<Order> toCriteriaOrders(CriteriaBuilder cb, Root<Booking> root, Sort sort) {
        return sort.stream()
                .map(order -> order.isAscending()
                        ? cb.asc(root.get(order.getProperty()))
                        : cb.desc(root.get(order.getProperty())))
                .toList();
    }
}
