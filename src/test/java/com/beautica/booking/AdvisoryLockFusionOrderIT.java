package com.beautica.booking;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.beautica.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored regression guard (security LOW, cycle-2 audit, 2026-08-22) — pins the ONE Postgres
 * executor property that every fused {@code lock_timeout} ceiling in this codebase silently rests on.
 *
 * <p><b>What breaks if this class goes RED.</b> Seven lock statements fuse
 * {@code set_config('lock_timeout', '3s', true)} into the SAME statement as the lock they are meant
 * to bound, so the GUC is set and the lock is taken in a single round trip. Each one depends on the
 * {@code set_config} expression being evaluated BEFORE the sibling expression that blocks. If that
 * evaluation order ever stopped holding, all seven ceilings would go inert at once — the timeout
 * would be applied only AFTER the wait it was supposed to bound, i.e. never — and every lock wait
 * would revert to Postgres' {@code lock_timeout} default of {@code 0} (wait forever). That is the
 * exact advisory-lock DoS the fused ceilings were added to close: a flood of contending requests
 * parks Hikari connections (pool size 10) for the full pool connection-timeout instead of failing
 * fast with {@code 55P03 lock_not_available} → {@code CannotAcquireLockException} → a clean 409.
 * Nothing else in the suite would notice: no existing test asserts a lock wait is bounded, so the
 * regression would ship fully green.
 *
 * <p><b>The call sites this guards</b> (grep {@code set_config} in {@code src/main} before adding
 * an eighth):
 * <ul>
 *   <li>{@code BookingRepository#acquireClientAdvisoryLockWithTimeout} — BookingRepository.java:1048
 *       (salt 1, per-client)</li>
 *   <li>{@code BookingRepository#acquireWalkInPhoneLock} — BookingRepository.java:1341
 *       (salt 3, per-phone walk-in SMS budget)</li>
 *   <li>{@code BookingRepository#acquireAdvisoryLockWithTimeout} — BookingRepository.java:1378
 *       (salt 0, per-master)</li>
 *   <li>{@code MasterServiceRepository#acquireBulkSetupLockWithTimeout} —
 *       MasterServiceRepository.java:310 (salt 2, bulk service setup)</li>
 *   <li>{@code AppointmentRepository#lockHeaderIfConfirmed} — AppointmentRepository.java:145
 *       (header row lock, {@code FOR UPDATE})</li>
 *   <li>{@code AppointmentRepository#lockHeaderRegardlessOfStatus} — AppointmentRepository.java:192
 *       (header row lock, {@code FOR UPDATE})</li>
 *   <li>{@code AppointmentRepository#consumeCancelToken} — AppointmentRepository.java:78
 *       (implicit {@code UPDATE} row lock)</li>
 * </ul>
 *
 * <p><b>Two distinct fusion shapes, one test each.</b> Six of the seven put {@code set_config} in a
 * SELECT target list beside the blocking expression ({@code pg_advisory_xact_lock(...)}, or the
 * {@code id} column of a {@code FOR UPDATE} scan) — {@link
 * #should_applyLockTimeoutBeforeSiblingTargetListEntry()} pins that. The seventh is an
 * {@code UPDATE}, which has no target-list slot, so it smuggles the GUC in through a one-row
 * {@code FROM (SELECT set_config(...)) AS lock_cfg} join source — {@link
 * #should_applyLockTimeoutBeforeOuterQueryWhenFusedAsJoinSource()} pins that separate property, so
 * a change affecting only one shape cannot hide behind the other.
 *
 * <p><b>Why these assertions are sound rather than accidental.</b> {@code current_setting} is
 * {@code STABLE}, so it is NOT constant-folded at plan time and must actually execute in the
 * executor; the sibling {@code set_config} is {@code VOLATILE}, which makes
 * {@code is_simple_subquery()} refuse subquery pull-up and keeps the target list unpruned. The
 * observation is therefore of real executor behaviour ({@code ExecBuildProjectionInfo} emits
 * projection steps in {@code resno} order into a sequential step machine), not of a planner
 * shortcut. Deliberately sleep-free and single-threaded: it asserts the ORDERING property the
 * ceilings rest on, never the 3-second duration itself, so it cannot flake under load.
 *
 * <p>Both tests open with a {@code SHOW lock_timeout} guard asserting the session default is still
 * {@code 0}. Without it, a pooled {@code connection-init-sql} or a global default drifting in would
 * make the assertion pass for the wrong reason.
 */
@DisplayName("Fused lock_timeout — evaluation-order guard for all seven set_config lock statements")
@Transactional
class AdvisoryLockFusionOrderIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("set_config in a SELECT target list is applied before the sibling entry beside it")
    void should_applyLockTimeoutBeforeSiblingTargetListEntry() {
        assertLockTimeoutDefaultIsDisabled();

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT * FROM (
                    SELECT set_config('lock_timeout', '3s', true) AS a,
                           current_setting('lock_timeout')        AS b
                ) sub
                """).getSingleResult();

        assertThat(row[0])
                .as("sanity: set_config must report the value it wrote, actual=%s", row[0])
                .isEqualTo("3s");
        assertThat(row[1])
                .as("the SECOND target-list entry must observe the FIRST entry's GUC write. '0' here "
                        + "means Postgres no longer evaluates sibling target-list entries "
                        + "left-to-right, so all six target-list-fused lock ceilings "
                        + "(BookingRepository:1048/1341/1378, MasterServiceRepository:310, "
                        + "AppointmentRepository:145/192) now set lock_timeout AFTER the lock they "
                        + "bound — every lock wait is unbounded again. actual=%s", row[1])
                .isEqualTo("3s");
    }

    @Test
    @DisplayName("set_config fused as a FROM join source is applied before the outer query runs")
    void should_applyLockTimeoutBeforeOuterQueryWhenFusedAsJoinSource() {
        assertLockTimeoutDefaultIsDisabled();

        Object observed = em.createNativeQuery("""
                SELECT current_setting('lock_timeout') AS b
                  FROM (SELECT set_config('lock_timeout', '3s', true)) AS lock_cfg
                """).getSingleResult();

        assertThat(observed)
                .as("the outer query must observe the join source's GUC write. '0' here means "
                        + "AppointmentRepository#consumeCancelToken (AppointmentRepository.java:78) "
                        + "takes its implicit UPDATE row lock with lock_timeout still disabled — a "
                        + "guest cancel-link click contending with a provider transition parks a "
                        + "Hikari connection indefinitely. actual=%s", observed)
                .isEqualTo("3s");
    }

    /**
     * Fails loudly if the session default is no longer Postgres' {@code 0} (disabled). A pooled
     * {@code connection-init-sql}, a role-level {@code ALTER ROLE ... SET lock_timeout}, or a
     * database-wide default would otherwise let both assertions above pass without proving anything
     * about evaluation order.
     */
    private void assertLockTimeoutDefaultIsDisabled() {
        Object sessionDefault = em.createNativeQuery("SHOW lock_timeout").getSingleResult();
        assertThat(sessionDefault)
                .as("session default lock_timeout must still be Postgres' '0' (disabled) — a "
                        + "non-zero default here would mask the evaluation-order assertion that "
                        + "follows, actual=%s", sessionDefault)
                .isEqualTo("0");
    }
}
