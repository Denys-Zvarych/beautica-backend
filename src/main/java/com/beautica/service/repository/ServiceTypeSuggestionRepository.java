package com.beautica.service.repository;

import com.beautica.common.repository.TransactionLockTimeout;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.entity.ServiceTypeSuggestionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link ServiceTypeSuggestion}. Mirrors the token-lookup +
 * dedup-existence shape of {@code PlatformCategoryRepository}.
 */
public interface ServiceTypeSuggestionRepository extends JpaRepository<ServiceTypeSuggestion, UUID>, TransactionLockTimeout {

    /**
     * Token lookup for the approve/reject POST. Matched on the hashed token only;
     * expiry, status, and the constant-time comparison are enforced in the service.
     * Returns at most one row — token_hash is effectively unique while PENDING.
     */
    Optional<ServiceTypeSuggestion> findByTokenHash(String tokenHash);

    /**
     * Locking variant ({@code FOR UPDATE}) for the decision (write) path only, so concurrent approve/reject
     * on one token serialize. The read-only review page keeps {@link #findByTokenHash}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServiceTypeSuggestion s where s.tokenHash = :tokenHash")
    Optional<ServiceTypeSuggestion> findByTokenHashForUpdateNoLockTimeout(@Param("tokenHash") String tokenHash);

    /** {@link #findByTokenHashForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout} ({@link TransactionLockTimeout}). */
    default Optional<ServiceTypeSuggestion> findByTokenHashForUpdate(String tokenHash) {
        applyTransactionLockTimeout();
        return findByTokenHashForUpdateNoLockTimeout(tokenHash);
    }

    /**
     * Dedup guard: returns {@code true} when a PENDING suggestion already exists for
     * the same category slug and (case-insensitively) the same suggested name. A
     * previously decided (APPROVED/REJECTED) suggestion does not block a re-suggest.
     *
     * <p>{@code LOWER(...)} normalization mirrors the partial UNIQUE index
     * {@code ux_service_type_suggestion_pending_dedup} (V197), which is the real guard against the
     * check-then-insert race; this check is the fast path that avoids a doomed INSERT.
     * {@code EXISTS} stops at the first match instead of counting.
     */
    @Query("""
            SELECT CASE WHEN EXISTS (
                SELECT 1 FROM ServiceTypeSuggestion s
                 WHERE s.status = com.beautica.service.entity.ServiceTypeSuggestionStatus.PENDING
                   AND s.categoryName = :categoryName
                   AND LOWER(s.suggestedName) = LOWER(:suggestedName)
            ) THEN true ELSE false END
            """)
    boolean existsPendingByCategoryNameAndSuggestedNameIgnoreCase(
            @Param("categoryName") String categoryName,
            @Param("suggestedName") String suggestedName);

    /**
     * B502 — the row a catalog-admin name edit mutates, locked ({@code FOR UPDATE}) so the edit serialises with a
     * concurrent email-link approve/reject ({@link #findByTokenHashForUpdate}); the loser then sees the new status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServiceTypeSuggestion s where s.id = :id")
    Optional<ServiceTypeSuggestion> findByIdForUpdateNoLockTimeout(@Param("id") UUID id);

    /** {@link #findByIdForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout}. */
    default Optional<ServiceTypeSuggestion> findByIdForUpdate(UUID id) {
        applyTransactionLockTimeout();
        return findByIdForUpdateNoLockTimeout(id);
    }

    /**
     * B502 — the rename pre-check: another PENDING suggestion (not {@code excludedId}) with the same category slug and
     * case-insensitive name. Same {@code LOWER(...)} key as V197's {@code ux_service_type_suggestion_pending_dedup},
     * which stays the real guard (the service translates its violation to the same 409).
     */
    @Query("""
            SELECT CASE WHEN EXISTS (
                SELECT 1 FROM ServiceTypeSuggestion s
                 WHERE s.status = com.beautica.service.entity.ServiceTypeSuggestionStatus.PENDING
                   AND s.categoryName = :categoryName
                   AND LOWER(s.suggestedName) = LOWER(:suggestedName)
                   AND s.id <> :excludedId
            ) THEN true ELSE false END
            """)
    boolean existsOtherPendingByCategoryNameAndSuggestedNameIgnoreCase(
            @Param("categoryName") String categoryName,
            @Param("suggestedName") String suggestedName,
            @Param("excludedId") UUID excludedId);

    /**
     * B505 (SVC-57) — PENDING suggestions naming the category (a plain string, no FK); the catalog-admin delete refuses
     * a category with any. Decided rows are kept as history and do not block. The literal {@code 'PENDING'} (not a
     * bind parameter) lets the planner always match the V197 partial index {@code WHERE status = 'PENDING'}.
     */
    @Query(value = "SELECT COUNT(*) FROM service_type_suggestion WHERE category_name = :categoryName "
            + "AND status = 'PENDING'", nativeQuery = true)
    long countPendingByCategoryName(@Param("categoryName") String categoryName);
}
