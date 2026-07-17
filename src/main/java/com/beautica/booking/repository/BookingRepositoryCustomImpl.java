package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

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
 * Pageable)}, for two reasons:
 * <ol>
 *   <li>That convenience method selects full {@link Booking} rows, which would break the
 *       two-query ID-page + graph-hydrate pattern this seam must preserve
 *       ({@code BookingService.listProviderBookings}).</li>
 *   <li>It derives its {@code ORDER BY} from {@code pageable.getSort()} — which would make
 *       {@code ?sort=} silently take effect, pulling Phase 26.3's fix forward as an unreviewed
 *       side effect. Building the {@link CriteriaQuery} by hand keeps the {@code SELECT b.id}
 *       projection AND lets {@code ORDER BY b.startsAt DESC} stay hardcoded exactly as it was in
 *       the pre-fix JPQL.</li>
 * </ol>
 */
class BookingRepositoryCustomImpl implements BookingRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<UUID> findIdsByMasterIdFiltered(
            UUID masterId, Collection<BookingStatus> statuses, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.masterIdEquals(masterId));
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(BookingSpecifications.statusIn(statuses));
        }
        return findIdPage(spec, pageable);
    }

    @Override
    public Page<UUID> findIdsBySalonIdsFiltered(
            List<UUID> salonIds, Collection<BookingStatus> statuses, Pageable pageable) {
        Specification<Booking> spec = Specification.where(BookingSpecifications.salonIdIn(salonIds));
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(BookingSpecifications.statusIn(statuses));
        }
        return findIdPage(spec, pageable);
    }

    /**
     * Executes {@code spec} as a {@code SELECT b.id} query with a HARDCODED
     * {@code ORDER BY b.startsAt DESC} (see class javadoc — intentionally independent of
     * {@code pageable.getSort()}), plus a matching {@code COUNT(*)} query built from the same
     * {@link Specification} — the same id-page-plus-count shape the superseded {@code @Query}
     * methods produced.
     */
    private Page<UUID> findIdPage(Specification<Booking> spec, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<UUID> idQuery = cb.createQuery(UUID.class);
        Root<Booking> idRoot = idQuery.from(Booking.class);
        idQuery.select(idRoot.get("id"));
        Predicate idPredicate = spec.toPredicate(idRoot, idQuery, cb);
        if (idPredicate != null) {
            idQuery.where(idPredicate);
        }
        idQuery.orderBy(cb.desc(idRoot.get("startsAt")));

        TypedQuery<UUID> typedIdQuery = entityManager.createQuery(idQuery);
        typedIdQuery.setFirstResult((int) pageable.getOffset());
        typedIdQuery.setMaxResults(pageable.getPageSize());
        List<UUID> ids = typedIdQuery.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Booking> countRoot = countQuery.from(Booking.class);
        countQuery.select(cb.count(countRoot));
        Predicate countPredicate = spec.toPredicate(countRoot, countQuery, cb);
        if (countPredicate != null) {
            countQuery.where(countPredicate);
        }
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(ids, pageable, total);
    }
}
