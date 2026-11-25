package com.beautica.service.repository;

import com.beautica.common.repository.TransactionLockTimeout;
import com.beautica.service.entity.PlatformCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformCategoryRepository extends JpaRepository<PlatformCategory, Long>, TransactionLockTimeout {

    boolean existsByNameIgnoreCase(String name);

    /**
     * Returns {@code true} when a category with the given name (exact case) exists,
     * is currently active, and is APPROVED. Used by the service-creation usability
     * gate: PENDING and REJECTED categories must never be selectable.
     */
    boolean existsByNameAndActiveTrueAndStatus(
            String name, com.beautica.service.entity.PlatformCategoryStatus status);

    /**
     * Returns {@code true} when a name is already taken by an APPROVED or PENDING
     * row — the 409 guard for new self-service requests. A previously REJECTED name
     * may be requested again.
     */
    boolean existsByNameIgnoreCaseAndStatusIn(
            String name, List<com.beautica.service.entity.PlatformCategoryStatus> statuses);

    /**
     * Batch selectability check: returns the subset of {@code names} that exist as
     * APPROVED + active categories (exact case). The bulk-create path collects the
     * DISTINCT categories derived from the resolved service types and validates them
     * in a single round-trip instead of one {@code SELECT EXISTS} per item; the
     * service asserts the returned set equals the requested set (any missing name →
     * the same 400 as {@code validateCategoryActive}).
     */
    @Query("""
            SELECT pc.name FROM PlatformCategory pc
             WHERE pc.name IN :names
               AND pc.active = true
               AND pc.status = com.beautica.service.entity.PlatformCategoryStatus.APPROVED
            """)
    List<String> findSelectableNamesIn(@org.springframework.data.repository.query.Param("names") java.util.Collection<String> names);

    /**
     * Token lookup for the approve/reject POST. Matched on the hashed token only;
     * expiry, status, and constant-time comparison are enforced in the service.
     * Returns at most one row — token_hash is effectively unique while PENDING.
     */
    Optional<PlatformCategory> findByTokenHash(String tokenHash);

    /**
     * Same lookup as {@link #findByTokenHash} but takes a row lock ({@code FOR UPDATE}). Used ONLY by the
     * decision (write) path so concurrent approve/reject on one token serialize: the loser's re-check fails
     * on the cleared token_hash → empty → ALREADY_DECIDED. Needs a read-write transaction; the read-only
     * GET review page keeps the non-locking variant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PlatformCategory c where c.tokenHash = :tokenHash")
    Optional<PlatformCategory> findByTokenHashForUpdateNoLockTimeout(@Param("tokenHash") String tokenHash);

    /** {@link #findByTokenHashForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout} ({@link TransactionLockTimeout}). */
    default Optional<PlatformCategory> findByTokenHashForUpdate(String tokenHash) {
        applyTransactionLockTimeout();
        return findByTokenHashForUpdateNoLockTimeout(tokenHash);
    }

    /**
     * Resolves a platform category by its canonical (exact-case) name slug. Used by
     * {@code ServiceTypePromotionService} (Phase 16.9.1) to read the V78
     * {@code service_category_id} bucket needed to create a {@link com.beautica.service.entity.ServiceType}.
     */
    Optional<PlatformCategory> findByName(String name);

    /**
     * B502 — the row a catalog-admin request edit mutates, locked ({@code FOR UPDATE}) so the edit serialises with a
     * concurrent email-link approve/reject ({@link #findByTokenHashForUpdate}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PlatformCategory c where c.id = :id")
    Optional<PlatformCategory> findByIdForUpdateNoLockTimeout(@Param("id") Long id);

    /** {@link #findByIdForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout}. */
    default Optional<PlatformCategory> findByIdForUpdate(Long id) {
        applyTransactionLockTimeout();
        return findByIdForUpdateNoLockTimeout(id);
    }

    /**
     * B504 — the live category a catalog-admin rename / hide / unhide mutates, keyed by its immutable wire name and
     * row-locked. Hibernate 6 renders {@code PESSIMISTIC_WRITE} on PostgreSQL as {@code FOR NO KEY UPDATE} (not
     * {@code FOR UPDATE}). That is enough here: {@code FOR NO KEY UPDATE} conflicts with itself, so a second portal edit
     * and a request decision (the id / token finders above, also {@code PESSIMISTIC_WRITE}) on the same row serialise,
     * and none of them changes the key ({@code name} is immutable). It does NOT conflict with the {@code FOR KEY SHARE}
     * an FK check takes, so it cannot fence a concurrent insert of a referencing row — the B505 delete uses
     * {@link #findByNameForDelete} for that.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PlatformCategory c where c.name = :name")
    Optional<PlatformCategory> findByNameForUpdateNoLockTimeout(@Param("name") String name);

    /** {@link #findByNameForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout}. */
    default Optional<PlatformCategory> findByNameForUpdate(String name) {
        applyTransactionLockTimeout();
        return findByNameForUpdateNoLockTimeout(name);
    }

    /**
     * B504 — true when ANOTHER approved or pending category (hidden ones included) already shows this display name,
     * case-insensitive; a rejected request's name never blocks. Guards a rename against two identical picker labels.
     */
    @Query("""
            SELECT COUNT(pc) > 0 FROM PlatformCategory pc
             WHERE LOWER(pc.displayName) = LOWER(:displayName)
               AND pc.id <> :excludeId
               AND pc.status IN (com.beautica.service.entity.PlatformCategoryStatus.APPROVED,
                                 com.beautica.service.entity.PlatformCategoryStatus.PENDING)
            """)
    boolean existsOtherLiveByDisplayNameIgnoreCase(@Param("displayName") String displayName,
                                                   @Param("excludeId") Long excludeId);

    /** Phase 372 — batch display-name lookup for suggestion feed items (one statement per page). */
    List<PlatformCategory> findByNameIn(Collection<String> names);

    /**
     * Approved + active categories for the authenticated picker
     * ({@code GET /api/v1/service-categories/approved}), ordered for deterministic
     * output. Bounded by the predicate (no unbounded full-table scan of stale rows).
     */
    @Query("""
            SELECT pc FROM PlatformCategory pc
             WHERE pc.status = com.beautica.service.entity.PlatformCategoryStatus.APPROVED
               AND pc.active = true
             ORDER BY pc.displayName
            """)
    List<PlatformCategory> findApprovedActive();

    /**
     * B504 (track D18) — every APPROVED category in ANY {@code active} state, by display name: the LABEL source for
     * services that already use a category the operator has since hidden (D4: they keep showing, under the display
     * name, not the slug). Never a picker source — {@link #findApprovedActive()} stays that.
     */
    @Query("""
            SELECT pc FROM PlatformCategory pc
             WHERE pc.status = com.beautica.service.entity.PlatformCategoryStatus.APPROVED
             ORDER BY pc.displayName
            """)
    List<PlatformCategory> findApprovedIncludingHidden();

    /**
     * Returns all platform categories with their usage counts from
     * {@code service_definitions.category}.
     *
     * <p>The LEFT JOIN ensures categories with zero usages are included.
     * The result is ordered by category name for deterministic output.
     *
     * <p>Convention note: this query crosses into {@code service_definitions}.
     * The crossing is isolated to this native query — no service-layer cross-repo
     * call is introduced; only the repository method wraps the join.
     */
    @Query(value = """
            SELECT pc.id,
                   pc.name,
                   pc.active,
                   pc.created_at,
                   COUNT(sd.category) AS usage_count
              FROM platform_categories pc
              LEFT JOIN service_definitions sd ON sd.category = pc.name
             GROUP BY pc.id, pc.name, pc.active, pc.created_at
             ORDER BY pc.name
            """, nativeQuery = true)
    List<CategoryUsageProjection> findAllWithUsageCounts();

    /**
     * Projection interface for the usage-count query result.
     */
    interface CategoryUsageProjection {
        Long getId();
        String getName();
        boolean isActive();
        java.time.OffsetDateTime getCreatedAt();
        long getUsageCount();
    }

    /**
     * B505 — the category a catalog-admin DELETE removes, locked with a real {@code FOR UPDATE} (native on purpose).
     * Hibernate 6 renders {@code PESSIMISTIC_WRITE} on PostgreSQL as {@code FOR NO KEY UPDATE}, which does NOT conflict
     * with the {@code FOR KEY SHARE} an FK check takes; {@code FOR UPDATE} does, so a concurrent insert of a type under
     * this category (V73 FK) either commits before the usage count and is counted, or waits and then fails its FK.
     */
    @Query(value = "SELECT pc.* FROM platform_categories pc WHERE pc.name = :name FOR UPDATE", nativeQuery = true)
    Optional<PlatformCategory> findByNameForDeleteNoLockTimeout(@Param("name") String name);

    /** {@link #findByNameForDeleteNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout}. */
    default Optional<PlatformCategory> findByNameForDelete(String name) {
        applyTransactionLockTimeout();
        return findByNameForDeleteNoLockTimeout(name);
    }

    /**
     * B505 — a shared lock ({@code FOR SHARE}, native on purpose: JPA has no portable mapping) on the category a new
     * PENDING service-type suggestion names. It conflicts with the delete's {@code FOR UPDATE}
     * ({@link #findByNameForDelete}), so a suggestion create and a category delete serialise: the delete counts a
     * committed suggestion (409), or the create sees the category gone. Concurrent creates share it.
     */
    @Query(value = "SELECT pc.* FROM platform_categories pc WHERE pc.name = :name FOR SHARE", nativeQuery = true)
    Optional<PlatformCategory> findByNameForShareNoLockTimeout(@Param("name") String name);

    /** {@link #findByNameForShareNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout}. */
    default Optional<PlatformCategory> findByNameForShare(String name) {
        applyTransactionLockTimeout();
        return findByNameForShareNoLockTimeout(name);
    }
}
