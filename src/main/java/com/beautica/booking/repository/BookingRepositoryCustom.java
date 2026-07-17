package com.beautica.booking.repository;

import com.beautica.booking.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Custom fragment backing the dynamic-{@link org.springframework.data.jpa.domain.Specification}
 * ID-page queries introduced by the Phase 26.1 audit fix (Finding 1 — HIGH, backend-perf).
 * Implemented by {@link BookingRepositoryCustomImpl}; see {@link BookingSpecifications} for the
 * full rationale on why the prior {@code (:statuses IS NULL OR …)} sentinel idiom was replaced.
 */
public interface BookingRepositoryCustom {

    /**
     * Callers must supply the authenticated user's own {@code master.id} — never an arbitrary
     * UUID (Anti-Bug §E-4; enforced by {@link BookingSpecifications#masterIdEquals}).
     * {@code statuses == null || statuses.isEmpty()} means "no status predicate" — the predicate
     * is omitted from the query entirely rather than bound as a null sentinel.
     *
     * <p>Ordering is a HARD {@code ORDER BY b.startsAt DESC}, independent of {@code pageable}'s
     * own {@link org.springframework.data.domain.Sort} — identical to the pre-fix JPQL. Phase
     * 26.3 owns making this {@code Pageable}-driven; changing it here would silently pull that
     * phase's scope forward.
     */
    Page<UUID> findIdsByMasterIdFiltered(UUID masterId, Collection<BookingStatus> statuses, Pageable pageable);

    /**
     * Callers must supply a pre-resolved, non-empty list of the authenticated owner's OWN active
     * salon ids (Anti-Bug §E-4; enforced by {@link BookingSpecifications#salonIdIn}). Same
     * status/ordering contract as {@link #findIdsByMasterIdFiltered}.
     */
    Page<UUID> findIdsBySalonIdsFiltered(List<UUID> salonIds, Collection<BookingStatus> statuses, Pageable pageable);
}
