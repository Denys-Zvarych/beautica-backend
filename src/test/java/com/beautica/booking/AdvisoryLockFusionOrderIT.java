package com.beautica.booking;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.beautica.AbstractIntegrationTest;
import com.beautica.salon.repository.SalonRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored regression guard (security LOW, cycle-2 audit, 2026-08-22) — pins the ONE Postgres
 * executor property that every fused {@code lock_timeout} ceiling in this codebase silently rests on.
 *
 * <p><b>What breaks if this class goes RED.</b> Thirteen lock statements fuse
 * {@code set_config('lock_timeout', '3s', true)} into the SAME statement as the lock they are meant
 * to bound, so the GUC is set and the lock is taken in a single round trip. Each one depends on the
 * {@code set_config} expression being evaluated BEFORE the sibling expression that blocks. If that
 * evaluation order ever stopped holding, all thirteen ceilings would go inert at once — the timeout
 * would be applied only AFTER the wait it was supposed to bound, i.e. never — and every lock wait
 * would revert to Postgres' {@code lock_timeout} default of {@code 0} (wait forever). That is the
 * exact advisory-lock DoS the fused ceilings were added to close: a flood of contending requests
 * parks Hikari connections (pool size 10) for the full pool connection-timeout instead of failing
 * fast with {@code 55P03 lock_not_available} → {@code CannotAcquireLockException} → a clean 409.
 * Nothing else in the suite would notice: no existing test asserts a lock wait is bounded, so the
 * regression would ship fully green.
 *
 * <p><b>The call sites this guards</b> (by method name — line numbers rot; grep {@code set_config}
 * in {@code src/main} before adding a fourteenth, and add it to the matching list below).
 *
 * <p><i>Target-list shape (8)</i> — {@code set_config} beside the blocking expression in a SELECT
 * target list; pinned by {@link #should_applyLockTimeoutBeforeSiblingTargetListEntry()}:
 * <ul>
 *   <li>{@code BookingRepository#acquireClientAdvisoryLockWithTimeout} (salt 1, per-client)</li>
 *   <li>{@code BookingRepository#acquireWalkInPhoneLock} (salt 3, per-phone walk-in SMS budget)</li>
 *   <li>{@code BookingRepository#acquireAdvisoryLockWithTimeout} (salt 0, per-master)</li>
 *   <li>{@code MasterServiceRepository#acquireBulkSetupLockWithTimeout} (salt 2, bulk service setup)</li>
 *   <li>{@code AppointmentRepository#lockHeaderIfConfirmed} (header row lock, {@code FOR UPDATE})</li>
 *   <li>{@code AppointmentRepository#lockHeaderRegardlessOfStatus} (header row lock, {@code FOR UPDATE})</li>
 *   <li>{@code AppointmentRepository#lockHeadersIfConfirmedBulk} (header row locks, {@code FOR UPDATE})</li>
 *   <li>{@code SalonRepository#lockBookingSlugCandidates} (Phase 376, salt 4, salon slug candidates) — the
 *       same shape wrapped in {@code SELECT count(*) FROM (... ORDER BY c)}; that exact wrapper is pinned
 *       separately by {@link #should_applyLockTimeoutBeforeFirstLock_when_countOrderByWrapperShape()} and
 *       {@link #should_applyLockTimeoutAndTakeEveryLock_when_lockBookingSlugCandidatesRuns()}; end-to-end
 *       ceiling pinned by {@code BookingLinkIT} / {@code SalonCreateCapConcurrencyIT}
 *       {@code #should_return409_when_slugCandidateLockHeldBeyondTimeout}</li>
 * </ul>
 *
 * <p><i>Join-source shape (5)</i> — the GUC smuggled in through a one-row
 * {@code (SELECT set_config(...)) lock_cfg} join source; pinned by
 * {@link #should_applyLockTimeoutBeforeOuterQueryWhenFusedAsJoinSource()}:
 * <ul>
 *   <li>{@code AppointmentRepository#consumeCancelTokenHash} (implicit {@code UPDATE} row lock)</li>
 *   <li>{@code SalonRepository#findByIdForUpdate} (Phase 343 salon logo/cover, {@code FOR NO KEY UPDATE OF s});
 *       end-to-end ceiling pinned by
 *       {@code SalonImageIT#should_return409AndDiscardBlob_when_salonLockHeldPastLockTimeout}</li>
 *   <li>{@code SalonRepository#lockIsActiveForUpdate}</li>
 *   <li>{@code MasterRepository#lockIdForUpdate}</li>
 *   <li>{@code SalonRepository#lockSalonIdsWithoutBookingSlug} (Phase 376) — end-to-end ceiling pinned by
 *       {@code BookingLinkIT#should_return409_when_salonRowLockHeldBeyondTimeout}</li>
 * </ul>
 *
 * <p><b>Two distinct fusion shapes, one test each</b>, so a change affecting only one shape cannot
 * hide behind the other: a {@code UPDATE} has no target-list slot, and a {@code FOR [NO KEY] UPDATE}
 * scan locks ABOVE the join, which is why the join-source shape exists at all.
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
@DisplayName("Fused lock_timeout — evaluation-order guard for all thirteen set_config lock statements")
@Transactional
class AdvisoryLockFusionOrderIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private SalonRepository salonRepository;

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
                        + "left-to-right, so all eight target-list-fused lock ceilings (see the "
                        + "class Javadoc list) now set lock_timeout AFTER the lock they bound — "
                        + "every lock wait is unbounded again. actual=%s", row[1])
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
                        + "the five join-source-fused ceilings (e.g. "
                        + "AppointmentRepository#consumeCancelTokenHash, "
                        + "SalonRepository#lockSalonIdsWithoutBookingSlug) take their row locks with "
                        + "lock_timeout still disabled — a contending request parks a Hikari "
                        + "connection indefinitely. actual=%s", observed)
                .isEqualTo("3s");
    }

    /**
     * Phase 376 INFO — {@code SalonRepository#lockBookingSlugCandidates} wraps the target-list shape in
     * {@code SELECT count(*) FROM (... FROM unnest(...) AS c ORDER BY c) AS l}. Two things could differ
     * from the bare shape: the outer {@code count(*)} references NEITHER column (subquery-output
     * pruning), and the {@code ORDER BY} adds a Sort node beneath the projection. This runs that exact
     * wrapper with the lock replaced by an observer of the GUC (in the lock's slot), and asserts the
     * FIRST row in lock order — i.e. the first lock — already sees {@code 3s}.
     *
     * <p>The observer MUST be volatile, like {@code pg_advisory_xact_lock}: with an {@code ORDER BY},
     * the planner ({@code make_sort_input_target}) evaluates non-volatile target entries BELOW the Sort
     * and postpones only volatile ones above it. A bare {@code current_setting} (STABLE) would therefore
     * run before {@code set_config} and read {@code '0'} (observed while authoring this test); the
     * {@code random()} guard makes it volatile so it is postponed exactly as the real lock is.
     */
    @Test
    @DisplayName("count(*) + ORDER BY wrapper (lockBookingSlugCandidates shape): set_config applied before the FIRST lock")
    void should_applyLockTimeoutBeforeFirstLock_when_countOrderByWrapperShape() {
        assertLockTimeoutDefaultIsDisabled();

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT count(*), (array_agg(l.b))[1], bool_and(l.b = '3s')
                  FROM (SELECT set_config('lock_timeout', '3s', true),
                               CASE WHEN random() >= 0 THEN current_setting('lock_timeout') END AS b
                          FROM unnest(CAST(ARRAY['zz-cand-ab12', 'aa-cand-ab12', 'mm-cand-ab12'] AS text[])) AS c
                         ORDER BY c) AS l
                """).getSingleResult();

        assertThat(((Number) row[0]).longValue()).as("one row per candidate").isEqualTo(3L);
        assertThat(row[1])
                .as("the first candidate's lock slot must observe 3s. '0' means the count(*)/ORDER BY "
                        + "wrapper evaluates the lock before set_config — lockBookingSlugCandidates "
                        + "would wait forever on its first contended candidate. actual=%s", row[1])
                .isEqualTo("3s");
        assertThat(row[2]).as("every candidate's lock slot observes 3s, actual=%s", row[2]).isEqualTo(true);
    }

    /**
     * Phase 376 INFO — the production statement itself: under the {@code count(*)} wrapper neither
     * volatile entry is pruned, so {@code set_config} runs (GUC is {@code 3s} afterwards) and every
     * distinct candidate's advisory lock is held by THIS transaction.
     */
    @Test
    @DisplayName("lockBookingSlugCandidates: set_config not pruned by count(*), one advisory lock held per candidate")
    void should_applyLockTimeoutAndTakeEveryLock_when_lockBookingSlugCandidatesRuns() {
        assertLockTimeoutDefaultIsDisabled();

        long locked = salonRepository.lockBookingSlugCandidates(new String[] {"zz-fusion-ab12", "aa-fusion-ab12"});

        Object timeout = em.createNativeQuery("SELECT current_setting('lock_timeout')").getSingleResult();
        Object held = em.createNativeQuery("""
                SELECT count(*) FROM pg_locks
                 WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND granted
                """).getSingleResult();
        assertThat(locked).as("count(*) = distinct candidates").isEqualTo(2L);
        assertThat(timeout).as("set_config survived the count(*) wrapper, actual=%s", timeout).isEqualTo("3s");
        assertThat(((Number) held).longValue()).as("both candidate locks held to commit").isEqualTo(2L);
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
