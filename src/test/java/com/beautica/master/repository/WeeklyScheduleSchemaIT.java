package com.beautica.master.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
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
}
