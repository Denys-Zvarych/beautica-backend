package com.beautica.master.repository;

import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionReason;
import com.beautica.master.entity.WorkingHours;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import jakarta.persistence.PersistenceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MasterScheduleRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WorkingHoursRepository workingHoursRepository;

    @Autowired
    private ScheduleExceptionRepository scheduleExceptionRepository;

    @Autowired
    private TestEntityManager em;

    private UUID masterId;

    @BeforeEach
    void setUp() {
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
                .avgRating(java.math.BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master);

        em.flush();
        masterId = master.getId();
    }

    // ── WorkingHours ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_findWorkingHours_when_masterAndDayOfWeekMatch")
    void should_findWorkingHours_when_masterAndDayOfWeekMatch() {
        Master master = em.find(Master.class, masterId);
        WorkingHours wh = WorkingHours.builder()
                .master(master)
                .dayOfWeek(1)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .isActive(true)
                .build();
        em.persist(wh);
        em.flush();

        var result = workingHoursRepository.findByMasterIdAndDayOfWeek(masterId, 1);

        assertThat(result).isPresent();
        assertThat(result.get().getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(result.get().getEndTime()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    @DisplayName("should_returnEmpty_when_noWorkingHoursForDay")
    void should_returnEmpty_when_noWorkingHoursForDay() {
        var result = workingHoursRepository.findByMasterIdAndDayOfWeek(masterId, 3);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_findActiveOnly_when_masterHasMixedActiveAndInactive")
    void should_findActiveOnly_when_masterHasMixedActiveAndInactive() {
        Master master = em.find(Master.class, masterId);

        WorkingHours activeWh = WorkingHours.builder()
                .master(master)
                .dayOfWeek(2)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .isActive(true)
                .build();
        WorkingHours inactiveWh = WorkingHours.builder()
                .master(master)
                .dayOfWeek(3)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(16, 0))
                .isActive(false)
                .build();
        em.persist(activeWh);
        em.persist(inactiveWh);
        em.flush();

        List<WorkingHours> results = workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDayOfWeek()).isEqualTo(2);
    }

    @Test
    @DisplayName("should_throwConstraintViolation_when_duplicateDayOfWeekInserted")
    void should_throwConstraintViolation_when_duplicateDayOfWeekInserted() {
        Master master = em.find(Master.class, masterId);

        WorkingHours first = WorkingHours.builder()
                .master(master)
                .dayOfWeek(4)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .isActive(true)
                .build();
        em.persist(first);
        em.flush();

        WorkingHours duplicate = WorkingHours.builder()
                .master(master)
                .dayOfWeek(4)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(17, 0))
                .isActive(true)
                .build();
        em.persist(duplicate);

        assertThatThrownBy(() -> em.flush())
                .isInstanceOf(PersistenceException.class);
    }

    // ── ScheduleException ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_findExceptions_when_dateIsWithinRange")
    void should_findExceptions_when_dateIsWithinRange() {
        LocalDate today = LocalDate.now();
        Master master = em.find(Master.class, masterId);

        ScheduleException exception = ScheduleException.builder()
                .master(master)
                .date(today)
                .reason(ScheduleExceptionReason.VACATION)
                .createdAt(OffsetDateTime.now())
                .build();
        em.persist(exception);
        em.flush();

        List<ScheduleException> results = scheduleExceptionRepository
                .findByMasterIdAndDateBetween(masterId, today.minusDays(1), today.plusDays(1));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("should_notFindExceptions_when_dateIsOutsideRange")
    void should_notFindExceptions_when_dateIsOutsideRange() {
        LocalDate today = LocalDate.now();
        Master master = em.find(Master.class, masterId);

        ScheduleException exception = ScheduleException.builder()
                .master(master)
                .date(today)
                .reason(ScheduleExceptionReason.SICK_DAY)
                .createdAt(OffsetDateTime.now())
                .build();
        em.persist(exception);
        em.flush();

        List<ScheduleException> results = scheduleExceptionRepository
                .findByMasterIdAndDateBetween(masterId, today.plusDays(1), today.plusDays(2));

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("should_throwConstraintViolation_when_duplicateDateInserted")
    void should_throwConstraintViolation_when_duplicateDateInserted() {
        LocalDate today = LocalDate.now();
        Master master = em.find(Master.class, masterId);

        ScheduleException first = ScheduleException.builder()
                .master(master)
                .date(today)
                .reason(ScheduleExceptionReason.HOLIDAY)
                .createdAt(OffsetDateTime.now())
                .build();
        em.persist(first);
        em.flush();

        ScheduleException duplicate = ScheduleException.builder()
                .master(master)
                .date(today)
                .reason(ScheduleExceptionReason.OTHER)
                .createdAt(OffsetDateTime.now())
                .build();
        em.persist(duplicate);

        assertThatThrownBy(() -> em.flush())
                .isInstanceOf(PersistenceException.class);
    }
}
