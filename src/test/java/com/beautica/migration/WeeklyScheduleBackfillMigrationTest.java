package com.beautica.migration;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 15.7 — backfill correctness for {@code V72__backfill_weekly_schedules.sql}.
 *
 * <h2>Why this test (and how it differs from {@code WeeklyScheduleSchemaIT})</h2>
 * {@code WeeklyScheduleSchemaIT} (Phase 15.1) proves the table CHECK/UNIQUE/FK-CASCADE contract and the
 * entity round-trip but explicitly defers the V72 backfill assertion to 15.7. This test closes that gap:
 * it seeds legacy {@code working_hours} rows, replays the exact V72 backfill projection, and asserts the
 * resulting {@code weekly_schedules} (one open-ended window per master) and {@code working_intervals}
 * (one per ACTIVE legacy row) — plus the interval CHECK constraint that the backfill output must satisfy.
 *
 * <p>The V72 migration has already executed once against the shared container, so re-seeding
 * {@code working_hours} for a fresh master and re-running the same INSERT projection is the faithful way
 * to assert the backfill semantics deterministically per test (slice rollback isolates each case).
 */
@DisplayName("WeeklyScheduleBackfillMigrationTest — V72 legacy working_hours → weekly_schedules/working_intervals")
class WeeklyScheduleBackfillMigrationTest extends AbstractDataJpaTest {

    @Autowired
    private TestEntityManager em;

    /** The V72 step-1 projection: one open-ended legacy weekly_schedule per master with any working_hours. */
    private static final String BACKFILL_SCHEDULES = """
            INSERT INTO weekly_schedules (master_id, valid_from, valid_to)
            SELECT DISTINCT wh.master_id, DATE '2020-01-01', NULL::date
            FROM working_hours wh
            WHERE wh.master_id = :masterId
            """;

    /** The V72 step-2 projection: one working_interval per ACTIVE legacy row into that master's schedule. */
    private static final String BACKFILL_INTERVALS = """
            INSERT INTO working_intervals (schedule_id, day_of_week, start_time, end_time)
            SELECT ws.id, wh.day_of_week, wh.start_time, wh.end_time
            FROM working_hours wh
            JOIN weekly_schedules ws ON ws.master_id = wh.master_id
            WHERE wh.is_active = TRUE AND wh.master_id = :masterId
            """;

    private UUID persistMaster() {
        User user = new User(
                "master" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.INDEPENDENT_MASTER, "Test", "Master", "+380501234567");
        em.persist(user);
        Master master = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master);
        em.flush();
        return master.getId();
    }

    private void seedWorkingHours(UUID masterId, int dow, LocalTime start, LocalTime end, boolean active) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO working_hours (master_id, day_of_week, start_time, end_time, is_active) "
                                + "VALUES (:mid, :dow, :start, :end, :active)")
                .setParameter("mid", masterId)
                .setParameter("dow", dow)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("active", active)
                .executeUpdate();
    }

    private int runBackfill(UUID masterId) {
        EntityManager raw = em.getEntityManager();
        raw.createNativeQuery(BACKFILL_SCHEDULES).setParameter("masterId", masterId).executeUpdate();
        return raw.createNativeQuery(BACKFILL_INTERVALS).setParameter("masterId", masterId).executeUpdate();
    }

    private long count(String table, UUID masterIdViaSchedule, boolean viaSchedule) {
        String sql = viaSchedule
                ? "SELECT COUNT(*) FROM working_intervals wi "
                + "JOIN weekly_schedules ws ON ws.id = wi.schedule_id WHERE ws.master_id = :mid"
                : "SELECT COUNT(*) FROM " + table + " WHERE master_id = :mid";
        Object n = em.getEntityManager().createNativeQuery(sql)
                .setParameter("mid", masterIdViaSchedule).getSingleResult();
        return ((Number) n).longValue();
    }

    @Test
    @DisplayName("backfill creates exactly one open-ended weekly_schedule per master that has working_hours")
    void should_createSingleOpenEndedSchedule_when_masterHasWorkingHours() {
        UUID masterId = persistMaster();
        seedWorkingHours(masterId, 1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);
        seedWorkingHours(masterId, 2, LocalTime.of(10, 0), LocalTime.of(16, 0), true);
        em.flush();

        runBackfill(masterId);
        em.flush();
        em.clear();

        Object[] schedule = (Object[]) em.getEntityManager().createNativeQuery(
                        "SELECT valid_from, valid_to FROM weekly_schedules WHERE master_id = :mid")
                .setParameter("mid", masterId).getSingleResult();

        assertThat(count("weekly_schedules", masterId, false))
                .as("exactly one schedule row per master").isEqualTo(1L);
        assertThat(((java.sql.Date) schedule[0]).toLocalDate())
                .as("valid_from pinned to the documented 2020-01-01 epoch")
                .isEqualTo(java.time.LocalDate.of(2020, 1, 1));
        assertThat(schedule[1]).as("valid_to NULL = open-ended legacy window").isNull();
    }

    @Test
    @DisplayName("backfill maps each ACTIVE working_hours row 1:1 into a working_interval")
    void should_backfillOneIntervalPerActiveRow_when_allActive() {
        UUID masterId = persistMaster();
        seedWorkingHours(masterId, 1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);
        seedWorkingHours(masterId, 3, LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        seedWorkingHours(masterId, 5, LocalTime.of(13, 0), LocalTime.of(19, 0), true);
        em.flush();

        int inserted = runBackfill(masterId);
        em.flush();
        em.clear();

        assertThat(inserted).as("step-2 inserts one interval per active row").isEqualTo(3);
        assertThat(count("working_intervals", masterId, true))
                .as("three active days → three working_intervals").isEqualTo(3L);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.getEntityManager().createNativeQuery(
                        "SELECT wi.day_of_week, wi.start_time, wi.end_time FROM working_intervals wi "
                                + "JOIN weekly_schedules ws ON ws.id = wi.schedule_id "
                                + "WHERE ws.master_id = :mid ORDER BY wi.day_of_week")
                .setParameter("mid", masterId).getResultList();

        assertThat(rows).extracting(r -> ((Number) r[0]).intValue())
                .as("the three active ISO weekdays are backfilled exactly")
                .containsExactly(1, 3, 5);
        assertThat(((java.sql.Time) rows.get(0)[1]).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(((java.sql.Time) rows.get(0)[2]).toLocalTime()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("backfill skips INACTIVE working_hours rows (is_active = false produces no interval)")
    void should_skipInactiveRows_when_backfilling() {
        UUID masterId = persistMaster();
        seedWorkingHours(masterId, 1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);   // kept
        seedWorkingHours(masterId, 2, LocalTime.of(9, 0), LocalTime.of(17, 0), false);  // skipped
        seedWorkingHours(masterId, 4, LocalTime.of(9, 0), LocalTime.of(17, 0), false);  // skipped
        em.flush();

        int inserted = runBackfill(masterId);
        em.flush();
        em.clear();

        assertThat(inserted).as("only the single active row is backfilled").isEqualTo(1);
        assertThat(count("working_intervals", masterId, true))
                .as("inactive legacy days produce no interval row").isEqualTo(1L);

        Integer dow = (Integer) em.getEntityManager().createNativeQuery(
                        "SELECT wi.day_of_week FROM working_intervals wi "
                                + "JOIN weekly_schedules ws ON ws.id = wi.schedule_id WHERE ws.master_id = :mid")
                .setParameter("mid", masterId).getSingleResult();
        assertThat(dow).as("the surviving interval is the active Monday (ISO 1)").isEqualTo(1);
    }

    @Test
    @DisplayName("a master with NO working_hours gets no backfilled schedule (DISTINCT over an empty set)")
    void should_createNothing_when_masterHasNoWorkingHours() {
        UUID masterId = persistMaster();
        em.flush();

        int inserted = runBackfill(masterId);
        em.flush();

        assertThat(inserted).isZero();
        assertThat(count("weekly_schedules", masterId, false))
                .as("no working_hours → no backfilled weekly_schedule").isZero();
    }

    @Test
    @DisplayName("the backfilled interval shape must satisfy chk_interval_order — a zero-length legacy row would be rejected")
    void should_enforceIntervalOrderConstraint_onBackfilledShape() {
        UUID masterId = persistMaster();
        em.flush();
        // Create the open-ended legacy schedule first (step 1) so we have a schedule_id to target.
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO weekly_schedules (master_id, valid_from, valid_to) "
                                + "VALUES (:mid, DATE '2020-01-01', NULL)")
                .setParameter("mid", masterId).executeUpdate();
        em.flush();
        UUID scheduleId = (UUID) em.getEntityManager().createNativeQuery(
                        "SELECT id FROM weekly_schedules WHERE master_id = :mid")
                .setParameter("mid", masterId).getSingleResult();

        // A degenerate (end <= start) interval — chk_interval_order on working_intervals must reject it,
        // proving the backfill target table enforces the wall-clock ordering invariant on every inserted row.
        assertThatThrownBy(() -> {
            em.getEntityManager().createNativeQuery(
                            "INSERT INTO working_intervals (schedule_id, day_of_week, start_time, end_time) "
                                    + "VALUES (:sid, 1, TIME '12:00', TIME '12:00')")
                    .setParameter("sid", scheduleId).executeUpdate();
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }
}
