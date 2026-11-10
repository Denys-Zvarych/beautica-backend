package com.beautica.service.service;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.repository.ActiveDuplicateProjection;
import com.beautica.service.repository.ServiceRepository;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cold-path lookup of the COMMITTED copy that won a V233 race
 * ({@code ux_service_def_owner_type_duration_name_active}), so the flush-time
 * {@code DUPLICATE_SERVICE} 409 can carry {@code serviceName} + {@code existingServiceDefId}
 * like every pre-check 409 does.
 *
 * <p><b>Why a separate {@code REQUIRES_NEW} transaction.</b> The unique violation aborts the
 * caller's Postgres transaction, so no further statement can run on its connection; a new,
 * read-only transaction on a second pooled connection can. The violation is raised only after the
 * winning transaction committed (the loser's INSERT blocks on the index until then), so the new
 * transaction sees the winner's row. Runs only on the rare failing path; the success path never
 * pays for it.
 *
 * <p><b>Never waits on the pool while holding the aborted connection.</b> The caller still holds
 * its first connection, so a blocking acquisition here is hold-and-wait: with enough concurrent race
 * losers every pooled connection is held by a loser waiting for a second one, and each parks for the
 * full Hikari {@code connection-timeout} (10 s) — which the transaction timeout below does not cover,
 * because it starts only once a connection is in hand. So the lookup runs only when Hikari would hand
 * over a connection without waiting — one is idle right now, or the pool is below its maximum size
 * and would open a new one; on a fully saturated pool it returns no rows and the caller answers with
 * the plain {@code DUPLICATE_SERVICE} 409 (null {@code existingServiceDefId}) instead of a late one.
 * The check is a best-effort snapshot: the connection it saw may be taken by any other request before
 * this acquisition, so the worst case is THIS request waiting up to the pool's
 * {@code connection-timeout} and then degrading to the plain 409 — one slow request, never a
 * pool-wide stall, because a loser that finds the pool saturated does not queue for it.
 * A non-Hikari {@link DataSource} (none today) skips the check.
 *
 * <p><b>Internal — no authorization of its own.</b> It reads any owner's active copies by the
 * {@code ownerType}/{@code ownerId} it is handed. Callers MUST already have verified the caller's
 * write access to that owner (today only {@link ServiceCatalogService}, after its owner gate);
 * hence package-private, so no other feature package can reach it.
 *
 * <p><b>Bounded.</b> {@value #LOOKUP_TIMEOUT_SECONDS}s transaction timeout (applied by Spring as
 * the JPA query timeout) so a slow lookup cannot hold the race loser's request; any failure,
 * including a transaction that cannot begin, degrades to the pre-lookup 409 in the caller.
 */
@Component
class DuplicateServiceCopyLookup {

    static final int LOOKUP_TIMEOUT_SECONDS = 2;

    private final ServiceRepository serviceRepository;
    private final TransactionTemplate lookupTx;
    @Nullable
    private final HikariDataSource hikariDataSource;

    DuplicateServiceCopyLookup(ServiceRepository serviceRepository,
            PlatformTransactionManager transactionManager, DataSource dataSource) {
        this.serviceRepository = serviceRepository;
        // Programmatic (not @Transactional) so the idle check runs BEFORE the REQUIRES_NEW
        // transaction acquires its connection — a proxied method would acquire first.
        this.lookupTx = new TransactionTemplate(transactionManager);
        this.lookupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lookupTx.setReadOnly(true);
        this.lookupTx.setTimeout(LOOKUP_TIMEOUT_SECONDS);
        this.hikariDataSource = hikariOf(dataSource);
    }

    /**
     * {@link ServiceRepository#findActiveDuplicateCopies} in its own read-only transaction — the
     * same zipped (type, duration, {@code lower(btrim(name))}) match the bulk pre-check uses.
     * Returns an empty list without touching the pool when the pool is saturated (see class doc).
     */
    public List<ActiveDuplicateProjection> findCommittedCopies(OwnerType ownerType, UUID ownerId,
            UUID[] typeIds, Integer[] durations, String[] names) {
        if (!canAcquireWithoutWaiting()) {
            return List.of();
        }
        List<ActiveDuplicateProjection> rows = lookupTx.execute(status ->
                serviceRepository.findActiveDuplicateCopies(ownerType.name(), ownerId, typeIds, durations, names));
        return Objects.requireNonNullElse(rows, List.of());
    }

    private boolean canAcquireWithoutWaiting() {
        if (hikariDataSource == null) {
            return true;
        }
        HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
        // null = pool not started yet; impossible here (the caller's own transaction started it).
        if (pool == null || pool.getIdleConnections() > 0) {
            return true;
        }
        // No idle connection, but below max Hikari opens a new one instead of queueing.
        HikariConfigMXBean config = hikariDataSource.getHikariConfigMXBean();
        return pool.getTotalConnections() < config.getMaximumPoolSize();
    }

    @Nullable
    private static HikariDataSource hikariOf(DataSource dataSource) {
        try {
            return dataSource.isWrapperFor(HikariDataSource.class)
                    ? dataSource.unwrap(HikariDataSource.class)
                    : null;
        } catch (SQLException notUnwrappable) {
            return null;
        }
    }
}
