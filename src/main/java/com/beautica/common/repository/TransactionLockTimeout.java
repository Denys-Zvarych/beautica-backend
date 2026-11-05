package com.beautica.common.repository;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * Mixin for repositories whose JPQL {@code @Lock(PESSIMISTIC_*)} finders must not wait unbounded on a row lock.
 *
 * <p><strong>Why.</strong> The application pool sets no session {@code lock_timeout} (deliberately: a session
 * default would mask a lock query that forgot its own). Every lock-taking query therefore bounds its own wait
 * with the transaction-local {@code set_config('lock_timeout', '3s', true)} used across the codebase
 * ({@code BookingRepository#acquireClientAdvisoryLockWithTimeout}, {@code SalonRepository#findByIdForUpdate}).
 * Without it, a wait runs into the 5 s {@code statement_timeout} instead and surfaces as a 503 rather than the
 * {@code 55P03} → {@code CannotAcquireLockException} → 409 contract.
 *
 * <p><strong>Why a separate statement for JPQL finders.</strong> Native lock queries fuse the call into the same
 * round trip ({@code CROSS JOIN (SELECT set_config(...)) lock_cfg}). A JPQL query cannot, and Hibernate 6's
 * {@code PostgreSQLDialect} ignores a positive {@code jakarta.persistence.lock.timeout} hint. So each such finder
 * is a {@code default} method that calls {@link #applyTransactionLockTimeout()} first and then runs the unchanged
 * JPQL lock query — same SQL, same lock strength, same flush behaviour.
 *
 * <p>The setting is transaction-scoped (reset at commit/rollback), so it also bounds every later lock wait in the
 * same transaction — identical to the fused native form. It must run inside the caller's transaction, which every
 * pessimistic finder already requires.
 */
public interface TransactionLockTimeout {

    /**
     * Sets {@code lock_timeout = 3s} for the rest of the current transaction. The query space is a name no entity
     * maps to, so Hibernate never auto-flushes the persistence context for this statement; the lock query that
     * follows flushes exactly as it did before.
     *
     * @return the new setting ({@code "3s"}); callers ignore it
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_NATIVE_SPACES, value = "tx_lock_timeout"))
    @Query(value = "SELECT set_config('lock_timeout', '3s', true)", nativeQuery = true)
    String applyTransactionLockTimeout();
}
