package com.beautica.master.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.master.entity.DiscreteTime;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.OverrideDiscreteTime;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionInterval;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeeklySchedule;
import com.beautica.master.entity.WorkingInterval;
import com.beautica.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 15.1 — schema + entity-mapping guard for the master schedule model
 * (V69 weekly_schedules, V70 working_intervals, V71 schedule_exceptions custom-hours).
 *
 * <p>Verifies the CHECK / UNIQUE / FK-CASCADE contract of the new tables and that the
 * JPA entities round-trip through a real PostgreSQL container. Service logic, endpoints,
 * non-overlap enforcement and the V72 backfill assertion are out of scope here and are
 * covered (or deferred) by Phase 15.4 / 15.7 — see the class-level note in the QA audit.
 */
class WeeklyScheduleSchemaIT extends AbstractDataJpaTest {

    @Autowired
    private WeeklyScheduleRepository weeklyScheduleRepository;

    @Autowired
    private WorkingIntervalRepository workingIntervalRepository;

    @Autowired
    private ScheduleExceptionRepository scheduleExceptionRepository;

    @Autowired
    private TestEntityManager em;

    private UUID masterId;

    @BeforeEach
    void setUp() {
        masterId = persistMaster().getId();
    }

    private Master persistMaster() {
        User user = new User(
                "master" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.INDEPENDENT_MASTER,
                "Test",
                "Master",
                "+380501234567"
        );
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
        return master;
    }

    private WeeklySchedule persistSchedule(LocalDate validFrom, LocalDate validTo) {
        WeeklySchedule schedule = WeeklySchedule.builder()
                .master(em.find(Master.class, masterId))
                .validFrom(validFrom)
                .validTo(validTo)
                .build();
        em.persist(schedule);
        em.flush();
        return schedule;
    }

    private WorkingInterval interval(WeeklySchedule schedule, int dayOfWeek, LocalTime start, LocalTime end) {
        return WorkingInterval.builder()
                .schedule(schedule)
                .dayOfWeek(dayOfWeek)
                .startTime(start)
                .endTime(end)
                .build();
    }

    private DiscreteTime discreteTime(WeeklySchedule schedule, int dayOfWeek, LocalTime slot) {
        return DiscreteTime.builder()
                .schedule(schedule)
                .dayOfWeek(dayOfWeek)
                .slotTime(slot)
                .build();
    }

    // ── 1. Persist + round-trip (multi-interval, LAZY @OneToMany) ────────────────

    @Nested
    @DisplayName("WeeklySchedule round-trip")
    class RoundTrip {

        @Test
        @DisplayName("persists a schedule with multiple working intervals and re-reads each field via LAZY @OneToMany")
        void should_roundTripScheduleWithMultipleIntervals_when_persistedAndReloaded() {
            // Arrange
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            // Monday split shift (morning + afternoon, break in between) + Tuesday single window.
            em.persist(interval(schedule, 1, LocalTime.of(9, 0), LocalTime.of(13, 0)));
            em.persist(interval(schedule, 1, LocalTime.of(14, 0), LocalTime.of(18, 0)));
            em.persist(interval(schedule, 2, LocalTime.of(10, 0), LocalTime.of(16, 0)));
            em.flush();
            em.clear(); // force a real DB reload + LAZY init of the collection

            // Act
            WeeklySchedule reloaded = weeklyScheduleRepository.findById(schedule.getId()).orElseThrow();

            // Assert
            assertThat(reloaded.getValidFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(reloaded.getValidTo()).as("open-ended window stays NULL").isNull();
            assertThat(reloaded.getMaster().getId())
                    .as("LAZY @ManyToOne master resolves").isEqualTo(masterId);
            assertThat(reloaded.getIntervals())
                    .as("LAZY @OneToMany intervals load")
                    .hasSize(3)
                    .extracting(WorkingInterval::getDayOfWeek, WorkingInterval::getStartTime, WorkingInterval::getEndTime)
                    .containsExactlyInAnyOrder(
                            tuple(1, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                            tuple(1, LocalTime.of(14, 0), LocalTime.of(18, 0)),
                            tuple(2, LocalTime.of(10, 0), LocalTime.of(16, 0)));
        }

        @Test
        @DisplayName("findByScheduleIdAndDayOfWeek returns only that day's intervals")
        void should_returnOnlyMatchingDay_when_findByScheduleIdAndDayOfWeek() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 1, LocalTime.of(9, 0), LocalTime.of(13, 0)));
            em.persist(interval(schedule, 1, LocalTime.of(14, 0), LocalTime.of(18, 0)));
            em.persist(interval(schedule, 2, LocalTime.of(10, 0), LocalTime.of(16, 0)));
            em.flush();

            List<WorkingInterval> monday =
                    workingIntervalRepository.findByScheduleIdAndDayOfWeek(schedule.getId(), 1);

            assertThat(monday)
                    .as("two Monday intervals, no Tuesday row")
                    .hasSize(2)
                    .allSatisfy(i -> assertThat(i.getDayOfWeek()).isEqualTo(1));
        }
    }

    // ── 2. findCoveringDate (validity window + NULL valid_to) ────────────────────

    @Nested
    @DisplayName("findCoveringDate")
    class CoveringDate {

        @Test
        @DisplayName("open-ended schedule (valid_to NULL) covers a far-future date")
        void should_cover_when_openEndedAndFutureDate() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2020, 1, 1), null);

            List<WeeklySchedule> result =
                    weeklyScheduleRepository.findCoveringDate(masterId, LocalDate.of(2099, 12, 31));

            assertThat(result).extracting(WeeklySchedule::getId).containsExactly(schedule.getId());
        }

        @Test
        @DisplayName("bounded window includes both boundary dates (valid_from and valid_to are inclusive)")
        void should_cover_when_dateOnInclusiveBoundary() {
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            WeeklySchedule schedule = persistSchedule(from, to);

            assertThat(weeklyScheduleRepository.findCoveringDate(masterId, from))
                    .as("valid_from boundary is inclusive")
                    .extracting(WeeklySchedule::getId).containsExactly(schedule.getId());
            assertThat(weeklyScheduleRepository.findCoveringDate(masterId, to))
                    .as("valid_to boundary is inclusive")
                    .extracting(WeeklySchedule::getId).containsExactly(schedule.getId());
            assertThat(weeklyScheduleRepository.findCoveringDate(masterId, LocalDate.of(2026, 3, 15)))
                    .as("date inside the window")
                    .extracting(WeeklySchedule::getId).containsExactly(schedule.getId());
        }

        @Test
        @DisplayName("bounded window excludes the day before valid_from and the day after valid_to")
        void should_notCover_when_dateOutsideBoundedWindow() {
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            persistSchedule(from, to);

            assertThat(weeklyScheduleRepository.findCoveringDate(masterId, from.minusDays(1)))
                    .as("day before valid_from is excluded").isEmpty();
            assertThat(weeklyScheduleRepository.findCoveringDate(masterId, to.plusDays(1)))
                    .as("day after valid_to is excluded").isEmpty();
        }

        @Test
        @DisplayName("returns empty when the master has no schedule covering the date")
        void should_returnEmpty_when_noScheduleCoversDate() {
            // No schedule persisted for this master at all.
            List<WeeklySchedule> result =
                    weeklyScheduleRepository.findCoveringDate(masterId, LocalDate.of(2026, 6, 4));

            assertThat(result).isEmpty();
        }
    }

    // ── 3. CHECK / UNIQUE constraint enforcement ─────────────────────────────────

    @Nested
    @DisplayName("Constraint enforcement")
    class Constraints {

        @Test
        @DisplayName("interval with end_time == start_time is rejected by chk_interval_order")
        void should_reject_when_intervalEndEqualsStart() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 1, LocalTime.of(9, 0), LocalTime.of(9, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("interval with end_time < start_time (midnight cross) is rejected by chk_interval_order")
        void should_reject_when_intervalEndBeforeStart() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 1, LocalTime.of(22, 0), LocalTime.of(2, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("day_of_week = 0 is rejected (below ISO range 1..7)")
        void should_reject_when_dayOfWeekZero() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 0, LocalTime.of(9, 0), LocalTime.of(18, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("day_of_week = 8 is rejected (above ISO range 1..7)")
        void should_reject_when_dayOfWeekEight() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 8, LocalTime.of(9, 0), LocalTime.of(18, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("valid_to one day before valid_from is rejected by chk_validity_order")
        void should_reject_when_validToBeforeValidFrom() {
            WeeklySchedule bad = WeeklySchedule.builder()
                    .master(em.find(Master.class, masterId))
                    .validFrom(LocalDate.of(2026, 3, 10))
                    .validTo(LocalDate.of(2026, 3, 9))
                    .build();
            em.persist(bad);

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("valid_to == valid_from (single-day window) is allowed by chk_validity_order")
        void should_allow_when_validToEqualsValidFrom() {
            LocalDate day = LocalDate.of(2026, 3, 10);
            WeeklySchedule schedule = persistSchedule(day, day);

            assertThat(weeklyScheduleRepository.findById(schedule.getId())).isPresent();
            assertThat(weeklyScheduleRepository.findCoveringDate(masterId, day))
                    .extracting(WeeklySchedule::getId).containsExactly(schedule.getId());
        }

        @Test
        @DisplayName("duplicate (schedule_id, day_of_week, start_time, end_time) is rejected by uq_working_intervals_no_dup")
        void should_reject_when_duplicateInterval() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 1, LocalTime.of(9, 0), LocalTime.of(18, 0)));
            em.flush();
            em.persist(interval(schedule, 1, LocalTime.of(9, 0), LocalTime.of(18, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }
    }

    // ── 4. ScheduleException discriminator (kind / intervals) — V83: reason + note DROPPED ───────

    @Nested
    @DisplayName("ScheduleException kind discriminator (V83 — no reason/note columns)")
    class ExceptionKind {

        @Test
        @DisplayName("V83 dropped the reason + note columns from schedule_exceptions")
        void should_haveDroppedReasonAndNoteColumns() {
            // The strongest schema-layer guard for V83: query the live information_schema. A missed V83
            // (or a partial rollback) would leave either column present and fail this assertion. Running
            // against the real Testcontainers Postgres — a mock could never surface a bad migration.
            @SuppressWarnings("unchecked")
            List<String> columns = em.getEntityManager().createNativeQuery(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name = 'schedule_exceptions'")
                    .getResultList();

            assertThat(columns)
                    .as("V83 must keep the kind discriminator")
                    .contains("kind")
                    .as("V83 physically dropped reason + note")
                    .doesNotContain("reason", "note");
        }

        @Test
        @DisplayName("DAY_OFF with zero intervals persists and round-trips (no reason/note needed, V83)")
        void should_roundTrip_when_dayOff() {
            ScheduleException ex = ScheduleException.builder()
                    .master(em.find(Master.class, masterId))
                    .date(LocalDate.of(2026, 5, 1))
                    .kind(ScheduleExceptionKind.DAY_OFF)
                    .build();
            em.persist(ex);
            em.flush();
            em.clear();

            ScheduleException reloaded = scheduleExceptionRepository.findById(ex.getId()).orElseThrow();

            assertThat(reloaded.getKind()).isEqualTo(ScheduleExceptionKind.DAY_OFF);
            assertThat(reloaded.getIntervals()).as("a DAY_OFF carries no intervals").isEmpty();
        }

        @Test
        @DisplayName("CUSTOM_HOURS with intervals persists and round-trips")
        void should_roundTrip_when_customHoursWithIntervals() {
            ScheduleException ex = ScheduleException.builder()
                    .master(em.find(Master.class, masterId))
                    .date(LocalDate.of(2026, 5, 3))
                    .kind(ScheduleExceptionKind.CUSTOM_HOURS)
                    .build();
            ex.getIntervals().add(ScheduleExceptionInterval.builder()
                    .exception(ex).startTime(LocalTime.of(11, 0)).endTime(LocalTime.of(15, 0)).build());
            ex.getIntervals().add(ScheduleExceptionInterval.builder()
                    .exception(ex).startTime(LocalTime.of(16, 0)).endTime(LocalTime.of(19, 0)).build());
            em.persist(ex);
            em.flush();
            em.clear();

            ScheduleException reloaded = scheduleExceptionRepository.findById(ex.getId()).orElseThrow();

            assertThat(reloaded.getKind()).isEqualTo(ScheduleExceptionKind.CUSTOM_HOURS);
            assertThat(reloaded.getIntervals())
                    .as("LAZY @OneToMany exception intervals load")
                    .hasSize(2)
                    .extracting(ScheduleExceptionInterval::getStartTime, ScheduleExceptionInterval::getEndTime)
                    .containsExactlyInAnyOrder(
                            tuple(LocalTime.of(11, 0), LocalTime.of(15, 0)),
                            tuple(LocalTime.of(16, 0), LocalTime.of(19, 0)));
        }

        @Test
        @DisplayName("schedule_exception_interval with end_time <= start_time is rejected by chk_exc_interval_order")
        void should_reject_when_exceptionIntervalEndNotAfterStart() {
            ScheduleException ex = ScheduleException.builder()
                    .master(em.find(Master.class, masterId))
                    .date(LocalDate.of(2026, 5, 5))
                    .kind(ScheduleExceptionKind.CUSTOM_HOURS)
                    .build();
            ex.getIntervals().add(ScheduleExceptionInterval.builder()
                    .exception(ex).startTime(LocalTime.of(12, 0)).endTime(LocalTime.of(12, 0)).build());
            em.persist(ex);

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }
    }

    // ── 5. ON DELETE CASCADE (FK) ────────────────────────────────────────────────

    @Nested
    @DisplayName("ON DELETE CASCADE")
    class Cascade {

        @Test
        @DisplayName("deleting a master removes its weekly_schedules and working_intervals")
        void should_cascadeToSchedulesAndIntervals_when_masterDeleted() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 1, LocalTime.of(9, 0), LocalTime.of(18, 0)));
            em.persist(interval(schedule, 2, LocalTime.of(9, 0), LocalTime.of(18, 0)));
            em.flush();
            UUID scheduleId = schedule.getId();
            em.clear();

            // Native delete so the DB FK ON DELETE CASCADE fires (not JPA orphan removal).
            em.getEntityManager().createNativeQuery("DELETE FROM masters WHERE id = :id")
                    .setParameter("id", masterId)
                    .executeUpdate();
            em.flush();
            em.clear();

            assertThat(weeklyScheduleRepository.findById(scheduleId))
                    .as("weekly_schedule cascade-deleted with its master").isEmpty();
            assertThat(workingIntervalRepository.findByScheduleId(scheduleId))
                    .as("working_intervals cascade-deleted with the schedule").isEmpty();
        }

        @Test
        @DisplayName("deleting a weekly_schedule removes its working_intervals")
        void should_cascadeToIntervals_when_scheduleDeleted() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(interval(schedule, 1, LocalTime.of(9, 0), LocalTime.of(18, 0)));
            em.persist(interval(schedule, 3, LocalTime.of(9, 0), LocalTime.of(18, 0)));
            em.flush();
            UUID scheduleId = schedule.getId();
            em.clear();

            em.getEntityManager().createNativeQuery("DELETE FROM weekly_schedules WHERE id = :id")
                    .setParameter("id", scheduleId)
                    .executeUpdate();
            em.flush();
            em.clear();

            assertThat(workingIntervalRepository.findByScheduleId(scheduleId))
                    .as("working_intervals cascade-deleted with their schedule").isEmpty();
        }
    }

    // ── 6. Phase 15.8 — working_interval_times (V84, EXPLICIT_TIMES discrete slots) ───────

    @Nested
    @DisplayName("V84 working_interval_times (Phase 15.8 discrete EXPLICIT_TIMES slots)")
    class DiscreteTimes {

        @Test
        @DisplayName("V84 created the working_interval_times table")
        void should_haveCreatedWorkingIntervalTimesTable() {
            // Information-schema guard for V84: a missed/partial migration would not surface the table.
            // Running against the real Testcontainers Postgres — a mock could never catch a bad migration.
            @SuppressWarnings("unchecked")
            List<String> columns = em.getEntityManager().createNativeQuery(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name = 'working_interval_times'")
                    .getResultList();

            assertThat(columns)
                    .as("V84 must create working_interval_times with its discrete-slot columns")
                    .contains("id", "schedule_id", "day_of_week", "slot_time");
        }

        @Test
        @DisplayName("persists discrete times for a schedule and round-trips them via the LAZY @OneToMany Set")
        void should_roundTripDiscreteTimes_when_persistedAndReloaded() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(discreteTime(schedule, 1, LocalTime.of(9, 0)));
            em.persist(discreteTime(schedule, 1, LocalTime.of(11, 30)));
            em.persist(discreteTime(schedule, 4, LocalTime.of(15, 0)));
            em.flush();
            em.clear(); // force a real DB reload + LAZY init of the discreteTimes Set

            WeeklySchedule reloaded = weeklyScheduleRepository.findById(schedule.getId()).orElseThrow();

            assertThat(reloaded.getDiscreteTimes())
                    .as("LAZY @OneToMany discreteTimes load")
                    .hasSize(3)
                    .extracting(DiscreteTime::getDayOfWeek, DiscreteTime::getSlotTime)
                    .containsExactlyInAnyOrder(
                            tuple(1, LocalTime.of(9, 0)),
                            tuple(1, LocalTime.of(11, 30)),
                            tuple(4, LocalTime.of(15, 0)));
        }

        @Test
        @DisplayName("duplicate (schedule_id, day_of_week, slot_time) is rejected by uq_working_interval_times_no_dup")
        void should_reject_when_duplicateDiscreteTime() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(discreteTime(schedule, 1, LocalTime.of(9, 0)));
            em.flush();
            em.persist(discreteTime(schedule, 1, LocalTime.of(9, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("same slot_time on a different day_of_week is allowed (uniqueness is per (schedule, day, time))")
        void should_allow_when_sameSlotTimeDifferentDay() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(discreteTime(schedule, 1, LocalTime.of(9, 0)));
            em.persist(discreteTime(schedule, 2, LocalTime.of(9, 0)));
            em.flush();
            em.clear();

            WeeklySchedule reloaded = weeklyScheduleRepository.findById(schedule.getId()).orElseThrow();

            assertThat(reloaded.getDiscreteTimes())
                    .as("the same wall-clock time on Mon and Tue is two distinct rows")
                    .hasSize(2);
        }

        @Test
        @DisplayName("day_of_week = 0 is rejected (below ISO range 1..7)")
        void should_reject_when_discreteDayOfWeekZero() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(discreteTime(schedule, 0, LocalTime.of(9, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("day_of_week = 8 is rejected (above ISO range 1..7)")
        void should_reject_when_discreteDayOfWeekEight() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(discreteTime(schedule, 8, LocalTime.of(9, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("deleting a weekly_schedule cascades to its working_interval_times (FK ON DELETE CASCADE)")
        void should_cascadeToDiscreteTimes_when_scheduleDeleted() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(discreteTime(schedule, 1, LocalTime.of(9, 0)));
            em.persist(discreteTime(schedule, 1, LocalTime.of(10, 0)));
            em.flush();
            UUID scheduleId = schedule.getId();
            em.clear();

            // Native delete so the DB FK ON DELETE CASCADE fires (not JPA orphan removal).
            em.getEntityManager().createNativeQuery("DELETE FROM weekly_schedules WHERE id = :id")
                    .setParameter("id", scheduleId)
                    .executeUpdate();
            em.flush();
            em.clear();

            @SuppressWarnings("unchecked")
            List<UUID> remaining = em.getEntityManager().createNativeQuery(
                    "SELECT id FROM working_interval_times WHERE schedule_id = :sid")
                    .setParameter("sid", scheduleId)
                    .getResultList();

            assertThat(remaining)
                    .as("working_interval_times cascade-deleted with their schedule").isEmpty();
        }

        @Test
        @DisplayName("deleting a master cascades through weekly_schedules to working_interval_times")
        void should_cascadeToDiscreteTimes_when_masterDeleted() {
            WeeklySchedule schedule = persistSchedule(LocalDate.of(2026, 1, 1), null);
            em.persist(discreteTime(schedule, 3, LocalTime.of(13, 0)));
            em.flush();
            UUID scheduleId = schedule.getId();
            em.clear();

            em.getEntityManager().createNativeQuery("DELETE FROM masters WHERE id = :id")
                    .setParameter("id", masterId)
                    .executeUpdate();
            em.flush();
            em.clear();

            @SuppressWarnings("unchecked")
            List<UUID> remaining = em.getEntityManager().createNativeQuery(
                    "SELECT id FROM working_interval_times WHERE schedule_id = :sid")
                    .setParameter("sid", scheduleId)
                    .getResultList();

            assertThat(weeklyScheduleRepository.findById(scheduleId))
                    .as("weekly_schedule cascade-deleted with its master").isEmpty();
            assertThat(remaining)
                    .as("working_interval_times cascade-deleted transitively with the master").isEmpty();
        }
    }

    // ── 7. Phase 15.9 — schedule_exception_times (V85, EXPLICIT_TIMES per-date override slots) ───────

    @Nested
    @DisplayName("V85 schedule_exception_times (Phase 15.9 per-date override discrete slots)")
    class OverrideDiscreteTimes {

        private ScheduleException persistCustomHoursException(LocalDate date) {
            ScheduleException ex = ScheduleException.builder()
                    .master(em.find(Master.class, masterId))
                    .date(date)
                    .kind(ScheduleExceptionKind.CUSTOM_HOURS)
                    .build();
            em.persist(ex);
            em.flush();
            return ex;
        }

        private OverrideDiscreteTime overrideTime(ScheduleException ex, LocalTime slot) {
            return OverrideDiscreteTime.builder().exception(ex).slotTime(slot).build();
        }

        @Test
        @DisplayName("V85 created the schedule_exception_times table with its discrete-slot columns")
        void should_haveCreatedScheduleExceptionTimesTable() {
            // Information-schema guard for V85: a missed/partial migration would not surface the table.
            // Running against the real Testcontainers Postgres — a mock could never catch a bad migration.
            @SuppressWarnings("unchecked")
            List<String> columns = em.getEntityManager().createNativeQuery(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name = 'schedule_exception_times'")
                    .getResultList();

            assertThat(columns)
                    .as("V85 must create schedule_exception_times with its discrete-slot columns "
                            + "(no day_of_week — the override is already date-scoped)")
                    .contains("id", "exception_id", "slot_time")
                    .doesNotContain("day_of_week");
        }

        @Test
        @DisplayName("persists override discrete times for an exception and round-trips them via the LAZY @OneToMany Set")
        void should_roundTripOverrideDiscreteTimes_when_persistedAndReloaded() {
            ScheduleException ex = persistCustomHoursException(LocalDate.of(2026, 5, 11));
            em.persist(overrideTime(ex, LocalTime.of(9, 0)));
            em.persist(overrideTime(ex, LocalTime.of(11, 30)));
            em.persist(overrideTime(ex, LocalTime.of(15, 0)));
            em.flush();
            em.clear(); // force a real DB reload + LAZY init of the discreteTimes Set

            ScheduleException reloaded = scheduleExceptionRepository.findById(ex.getId()).orElseThrow();

            assertThat(reloaded.getDiscreteTimes())
                    .as("LAZY @OneToMany override discreteTimes load")
                    .hasSize(3)
                    .extracting(OverrideDiscreteTime::getSlotTime)
                    .containsExactlyInAnyOrder(LocalTime.of(9, 0), LocalTime.of(11, 30), LocalTime.of(15, 0));
        }

        @Test
        @DisplayName("duplicate (exception_id, slot_time) is rejected by uq_schedule_exception_times_no_dup")
        void should_reject_when_duplicateOverrideDiscreteTime() {
            ScheduleException ex = persistCustomHoursException(LocalDate.of(2026, 5, 12));
            em.persist(overrideTime(ex, LocalTime.of(9, 0)));
            em.flush();
            em.persist(overrideTime(ex, LocalTime.of(9, 0)));

            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
        }

        @Test
        @DisplayName("the same slot_time on a different exception (date) is allowed (uniqueness is per (exception, time))")
        void should_allow_when_sameSlotTimeDifferentException() {
            ScheduleException mon = persistCustomHoursException(LocalDate.of(2026, 5, 13));
            ScheduleException tue = persistCustomHoursException(LocalDate.of(2026, 5, 14));
            em.persist(overrideTime(mon, LocalTime.of(9, 0)));
            em.persist(overrideTime(tue, LocalTime.of(9, 0)));
            em.flush();
            em.clear();

            assertThat(scheduleExceptionRepository.findById(mon.getId()).orElseThrow().getDiscreteTimes())
                    .as("09:00 on a different override date is a distinct row").hasSize(1);
            assertThat(scheduleExceptionRepository.findById(tue.getId()).orElseThrow().getDiscreteTimes())
                    .hasSize(1);
        }

        @Test
        @DisplayName("deleting a schedule_exception cascades to its schedule_exception_times (FK ON DELETE CASCADE)")
        void should_cascadeToOverrideTimes_when_exceptionDeleted() {
            ScheduleException ex = persistCustomHoursException(LocalDate.of(2026, 5, 15));
            em.persist(overrideTime(ex, LocalTime.of(9, 0)));
            em.persist(overrideTime(ex, LocalTime.of(10, 0)));
            em.flush();
            UUID exceptionId = ex.getId();
            em.clear();

            // Native delete so the DB FK ON DELETE CASCADE fires (not JPA orphan removal).
            em.getEntityManager().createNativeQuery("DELETE FROM schedule_exceptions WHERE id = :id")
                    .setParameter("id", exceptionId)
                    .executeUpdate();
            em.flush();
            em.clear();

            @SuppressWarnings("unchecked")
            List<UUID> remaining = em.getEntityManager().createNativeQuery(
                    "SELECT id FROM schedule_exception_times WHERE exception_id = :eid")
                    .setParameter("eid", exceptionId)
                    .getResultList();

            assertThat(remaining)
                    .as("schedule_exception_times cascade-deleted with their schedule_exception").isEmpty();
        }

        @Test
        @DisplayName("deleting a master cascades through schedule_exceptions to schedule_exception_times")
        void should_cascadeToOverrideTimes_when_masterDeleted() {
            ScheduleException ex = persistCustomHoursException(LocalDate.of(2026, 5, 16));
            em.persist(overrideTime(ex, LocalTime.of(13, 0)));
            em.flush();
            UUID exceptionId = ex.getId();
            em.clear();

            em.getEntityManager().createNativeQuery("DELETE FROM masters WHERE id = :id")
                    .setParameter("id", masterId)
                    .executeUpdate();
            em.flush();
            em.clear();

            @SuppressWarnings("unchecked")
            List<UUID> remaining = em.getEntityManager().createNativeQuery(
                    "SELECT id FROM schedule_exception_times WHERE exception_id = :eid")
                    .setParameter("eid", exceptionId)
                    .getResultList();

            assertThat(scheduleExceptionRepository.findById(exceptionId))
                    .as("schedule_exception cascade-deleted with its master").isEmpty();
            assertThat(remaining)
                    .as("schedule_exception_times cascade-deleted transitively with the master").isEmpty();
        }
    }
}
