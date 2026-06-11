package com.beautica.master.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.ScheduleExceptionReason;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WeeklyScheduleRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 15.4 — full-stack guard for {@link MasterScheduleService} (the security-critical schedule
 * layer) + {@link ScheduleMapper}, driven directly against the autowired service over a real
 * Testcontainers Postgres.
 *
 * <h2>Why this test exists</h2>
 * The 15.1 schema IT proved the table contract; the service IT proves the BUSINESS contract that the
 * repository finders deliberately do NOT enforce:
 * <ul>
 *   <li><b>Ownership / IDOR</b> — the unscoped finders trust the caller's {@code masterId}; this
 *       service is the only gate. Pinned on every write path + the foreign-{@code scheduleId} edit
 *       path (must 404, not leak existence).</li>
 *   <li><b>Invariants</b> — no past edit, far-future cap, per-master window overlap, intra-day
 *       interval overlap/ordering, seconds zeroing.</li>
 *   <li><b>Effective-availability resolver</b> — override beats template beats gap; multi-week range
 *       folds correctly without N+1 / lazy-init.</li>
 *   <li><b>Data exposure</b> — {@code EffectiveDayResponse} never carries the free-text {@code note}.</li>
 * </ul>
 *
 * <p>Dates are chosen relative to {@code LocalDate.now()} with a safe margin so the suite is
 * deterministic without overriding the {@code Clock} bean (which would fragment the context cache).
 * Deeper edge matrix (DST, leap-year boundary resolution, exhaustive OQ cases) is deferred to 15.7.
 */
@DisplayName("MasterScheduleService — ownership, invariants, resolver (full stack, real Postgres)")
class MasterScheduleServiceIT extends AbstractIntegrationTest {

    @Autowired
    private MasterScheduleService scheduleService;

    @Autowired
    private WeeklyScheduleRepository weeklyScheduleRepository;

    @Autowired
    private ScheduleExceptionRepository scheduleExceptionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    // Safe-margin future dates: well clear of "today" so no-past-edit never trips by accident.
    private static final LocalDate FUTURE_FROM = LocalDate.now().plusDays(30);

    // ── fixtures: seed an INDEPENDENT_MASTER (owns itself) + a SALON_OWNER salon/master ──

    /** A solo master + its user; {@code actorId == userId} is the rightful owner. */
    private SeededMaster seedIndependentMaster() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', "
                        + "'Ind', 'Master', true, true)",
                userId, "ind-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return new SeededMaster(masterId, userId, null);
    }

    /** A salon owner + a SALON_OWNER-type master in that salon; the owner is the rightful actor. */
    private SeededMaster seedSalonOwnerMaster() {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'SALON_OWNER', 'Own', 'Er', true, true)",
                ownerId, "owner-" + ownerId + "@beautica.test");
        UUID salonId = UUID.randomUUID();
        jdbc.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, salon_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'SALON_OWNER', 0, true, NOW(), NOW())",
                masterId, ownerId, salonId);
        return new SeededMaster(masterId, ownerId, salonId);
    }

    private record SeededMaster(UUID masterId, UUID actorId, UUID salonId) {
    }

    private static WeeklyScheduleRequest weekly(LocalDate from, LocalDate to, WeeklyScheduleDayRequest... days) {
        return new WeeklyScheduleRequest(from, to, List.of(days));
    }

    private static WeeklyScheduleDayRequest day(int dow, WorkIntervalDto... intervals) {
        return new WeeklyScheduleDayRequest(dow, List.of(intervals));
    }

    private static WorkIntervalDto iv(int startHour, int endHour) {
        return new WorkIntervalDto(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 1. Ownership / IDOR — the closed 15.1 risk, regression-pinned on every write path
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Ownership / IDOR")
    class Ownership {

        @Test
        @DisplayName("upsertWeeklySchedule (create) — ForbiddenException when an unrelated master is the actor")
        void should_rejectCreate_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    attacker.actorId(), victim.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17)))))
                    .as("attacker editing the victim's schedule must be forbidden")
                    .isInstanceOf(ForbiddenException.class);

            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(victim.masterId()))
                    .as("no schedule may be persisted for the victim").isEmpty();
        }

        @Test
        @DisplayName("upsertOverride — ForbiddenException when an unrelated actor writes a day-off")
        void should_rejectOverride_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    attacker.actorId(), victim.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.VACATION, "secret", null)))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(victim.masterId(), FUTURE_FROM))
                    .as("no override may be persisted for the victim").isEmpty();
        }

        @Test
        @DisplayName("clearOverride — ForbiddenException when an unrelated actor clears the victim's override")
        void should_rejectClear_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();
            // Victim legitimately owns a day-off override.
            scheduleService.upsertOverride(victim.actorId(), victim.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.SICK_DAY, null, null));

            assertThatThrownBy(() -> scheduleService.clearOverride(
                    attacker.actorId(), victim.masterId(), FUTURE_FROM))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(victim.masterId(), FUTURE_FROM))
                    .as("the victim's override must survive the rejected clear").isPresent();
        }

        @Test
        @DisplayName("edit with a foreign scheduleId → NotFoundException (no existence oracle) even when actor owns masterId")
        void should_return404_when_editingAnotherMastersScheduleId() {
            SeededMaster a = seedIndependentMaster();
            SeededMaster b = seedIndependentMaster();
            // 'a' creates a real schedule; capture its id from the DB.
            scheduleService.upsertWeeklySchedule(a.actorId(), a.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            UUID foreignScheduleId = weeklyScheduleRepository
                    .findByMasterIdOrderByValidFromAsc(a.masterId()).get(0).getId();

            // 'b' owns its own masterId but supplies 'a'-owned scheduleId on the edit path.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    b.actorId(), b.masterId(), foreignScheduleId,
                    weekly(FUTURE_FROM, null, day(2, iv(10, 16)))))
                    .as("cross-master edit must surface as 404, not 403, to avoid an existence oracle")
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("happy path — INDEPENDENT_MASTER manages its own schedule; SALON_OWNER manages their salon's master")
        void should_succeed_when_rightfulOwner() {
            SeededMaster solo = seedIndependentMaster();
            WeeklyScheduleResponse soloResp = scheduleService.upsertWeeklySchedule(
                    solo.actorId(), solo.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            assertThat(soloResp.validFrom()).isEqualTo(FUTURE_FROM);

            SeededMaster salon = seedSalonOwnerMaster();
            WeeklyScheduleResponse salonResp = scheduleService.upsertWeeklySchedule(
                    salon.actorId(), salon.masterId(), null,
                    weekly(FUTURE_FROM, null, day(3, iv(8, 12))));
            assertThat(salonResp.days())
                    .as("the salon owner's write must persist the Wednesday window")
                    .singleElement()
                    .extracting(d -> d.dayOfWeek()).isEqualTo(3);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 2. Service invariants
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Invariants — temporal bounds")
    class TemporalBounds {

        @Test
        @DisplayName("weekly template with a past validFrom is rejected")
        void should_reject_when_weeklyValidFromInPast() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(LocalDate.now().minusDays(1), null, day(1, iv(9, 17)))))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("override for a past date is rejected")
        void should_reject_when_overrideDateInPast() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(LocalDate.now().minusDays(1),
                            ScheduleExceptionKind.DAY_OFF, ScheduleExceptionReason.OTHER, null, null)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("clearOverride for a past date is rejected")
        void should_reject_when_clearPastDate() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.clearOverride(
                    m.actorId(), m.masterId(), LocalDate.now().minusDays(1)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("validTo beyond the today+2y cap is rejected; null validTo persists as open-ended")
        void should_rejectFarFuture_butAllowOpenEnded() {
            SeededMaster m = seedIndependentMaster();

            // +2 days (not +1): the service computes cap() from LocalDate.now(kyivClock) while this
            // test uses the default system clock (UTC on CI). Kyiv is always UTC+2/+3 ahead, so in the
            // UTC-evening window the two clocks land on different calendar days and a +1-day validTo
            // equals the cap exactly — the strict isAfter check then does not reject and the test flakes.
            // A 2-day margin guarantees validTo is strictly past the cap regardless of the 1-day skew.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, LocalDate.now().plusYears(2).plusDays(2), day(1, iv(9, 17)))))
                    .as("validTo past today+2y must be rejected")
                    .isInstanceOf(BusinessException.class);

            WeeklyScheduleResponse open = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            assertThat(open.validTo()).as("null validTo persists as open-ended").isNull();
        }
    }

    @Nested
    @DisplayName("Invariants — window overlap")
    class WindowOverlap {

        @Test
        @DisplayName("two weekly windows that overlap for the same master are rejected")
        void should_reject_when_windowsOverlap() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, FUTURE_FROM.plusDays(30), day(1, iv(9, 17))));

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM.plusDays(20), FUTURE_FROM.plusDays(50), day(2, iv(9, 17)))))
                    .as("the second window overlaps days 20..30 of the first")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("adjacent (non-overlapping) windows are both allowed")
        void should_allow_when_windowsAdjacent() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, FUTURE_FROM.plusDays(30), day(1, iv(9, 17))));
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM.plusDays(31), FUTURE_FROM.plusDays(60), day(2, iv(9, 17))));

            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(m.masterId()))
                    .as("both adjacent windows coexist").hasSize(2);
        }

        @Test
        @DisplayName("an open-ended window blocks any later overlapping window (validTo=null = +infinity)")
        void should_reject_when_openEndedWindowOverlapsLater() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM.plusDays(100), FUTURE_FROM.plusDays(130), day(2, iv(9, 17)))))
                    .as("the open-ended window extends to +infinity and overlaps the later window")
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("Invariants — intra-day intervals")
    class IntraDayIntervals {

        @Test
        @DisplayName("overlapping intervals within the same weekday are rejected")
        void should_reject_when_intraDayIntervalsOverlap() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 13), iv(12, 18)))))
                    .as("09-13 and 12-18 overlap at 12-13")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("non-overlapping split-shift intervals are accepted regardless of input order")
        void should_accept_when_splitShiftOutOfOrder() {
            SeededMaster m = seedIndependentMaster();
            // Afternoon listed before morning — the service sorts before checking.
            WeeklyScheduleResponse resp = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(14, 18), iv(9, 13))));

            assertThat(resp.days())
                    .singleElement()
                    .extracting(d -> d.intervals())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(WorkIntervalDto.class))
                    .as("intervals are emitted ordered by start time")
                    .extracting(WorkIntervalDto::startTime)
                    .containsExactly(LocalTime.of(9, 0), LocalTime.of(14, 0));
        }

        @Test
        @DisplayName("seconds on persisted interval times are zeroed")
        void should_zeroSeconds_when_persistingIntervals() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    new WeeklyScheduleRequest(FUTURE_FROM, null, List.of(
                            new WeeklyScheduleDayRequest(1, List.of(
                                    new WorkIntervalDto(LocalTime.of(9, 0, 45), LocalTime.of(17, 30, 59)))))));

            EffectiveDayResponse day = scheduleService.resolveEffectiveDay(
                    m.masterId(), nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY));
            assertThat(day.intervals())
                    .singleElement()
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .as("seconds and nanos are stripped on persist")
                    .containsExactly(LocalTime.of(9, 0), LocalTime.of(17, 30));
        }

        @Test
        @DisplayName("CUSTOM_HOURS override with overlapping intervals is rejected")
        void should_reject_when_overrideIntervalsOverlap() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            null, null, List.of(iv(9, 13), iv(12, 15)))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3. Effective-availability resolver
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Effective-availability resolver")
    class Resolver {

        @Test
        @DisplayName("range folds TEMPLATE, OVERRIDE_CUSTOM, OVERRIDE_DAY_OFF, NO_SCHEDULE — override beats template")
        void should_foldAllSources_when_resolvingRange() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            // Template covers [monday .. monday+6]; Mon+Tue working, the rest implicitly off.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, monday.plusDays(6),
                            day(1, iv(9, 17)),   // Monday
                            day(2, iv(10, 16)))); // Tuesday

            // Custom-hours override on Monday → wins over the template.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.CUSTOM_HOURS,
                            null, null, List.of(iv(12, 14))));
            // Day-off override on Tuesday → wins over the template.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(1), ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.HOLIDAY, null, null));

            // Resolve [monday-1 .. monday+7]: monday-1 and monday+7 are outside the template = NO_SCHEDULE.
            List<EffectiveDayResponse> days = scheduleService.resolveEffectiveRange(
                    m.masterId(), monday.minusDays(1), monday.plusDays(7));

            assertThat(days).hasSize(9);
            assertThat(byDate(days, monday.minusDays(1)).source())
                    .as("uncovered date before the window").isEqualTo(EffectiveDaySource.NO_SCHEDULE);
            // Monday: override beats template.
            EffectiveDayResponse mon = byDate(days, monday);
            assertThat(mon.source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(mon.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(12, 0), LocalTime.of(14, 0)));
            // Tuesday: day-off override beats template, carries its reason, empty intervals.
            EffectiveDayResponse tue = byDate(days, monday.plusDays(1));
            assertThat(tue.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(tue.reason()).isEqualTo(ScheduleExceptionReason.HOLIDAY);
            assertThat(tue.intervals()).isEmpty();
            // Wednesday: covered by template, no working interval that weekday → TEMPLATE, empty intervals.
            EffectiveDayResponse wed = byDate(days, monday.plusDays(2));
            assertThat(wed.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(wed.intervals()).as("Wednesday has no template interval").isEmpty();
            // monday+7 is past validTo (monday+6) = NO_SCHEDULE.
            assertThat(byDate(days, monday.plusDays(7)).source())
                    .as("uncovered date after the window").isEqualTo(EffectiveDaySource.NO_SCHEDULE);
        }

        @Test
        @DisplayName("resolveEffectiveDay returns the covering template's intervals for that weekday")
        void should_resolveSingleDay_fromTemplate() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17))));

            EffectiveDayResponse resp = scheduleService.resolveEffectiveDay(m.masterId(), monday);

            assertThat(resp.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(resp.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        }

        @Test
        @DisplayName("multi-week span resolves correctly with a bounded query count (no N+1 / no lazy-init)")
        void should_resolveMultiWeekSpan_withBoundedQueries() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            // Open-ended template + a scattering of overrides across the span.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17)), day(3, iv(10, 14))));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(10), ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.VACATION, null, null));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(20), ScheduleExceptionKind.CUSTOM_HOURS,
                            null, null, List.of(iv(8, 9))));

            Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            boolean wasEnabled = stats.isStatisticsEnabled();
            stats.setStatisticsEnabled(true);
            stats.clear();

            // A 28-day span across four weeks.
            List<EffectiveDayResponse> days = scheduleService.resolveEffectiveRange(
                    m.masterId(), monday, monday.plusDays(27));

            long queries = stats.getPrepareStatementCount();
            stats.setStatisticsEnabled(wasEnabled);

            assertThat(days).as("one entry per inclusive date").hasSize(28);
            assertThat(byDate(days, monday).source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(byDate(days, monday.plusDays(10)).source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(byDate(days, monday.plusDays(20)).source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(queries)
                    .as("resolver must bulk-load (overrides + templates) — no per-date N+1 across a 28-day span")
                    .isLessThanOrEqualTo(4);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3b. Read identity — WeeklyScheduleResponse.id round-trips (regression: missing id)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Read identity — WeeklyScheduleResponse.id round-trip")
    class ReadIdentity {

        /**
         * Regression for the missing-{@code id} gap: {@code WeeklyScheduleResponse} previously had no
         * {@code id}, so a client reloading the list could not target {@code PUT
         * /weekly-schedules/{scheduleId}} and re-POSTed a duplicate window — tripping the overlap guard.
         * The listed response's {@code id} must be non-null AND equal the persisted entity's id, so an
         * editor can round-trip it straight onto the update path.
         */
        @Test
        @DisplayName("listWeeklySchedules exposes a non-null id equal to the persisted entity's id")
        void should_exposePersistedId_when_listingWeeklySchedules() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            UUID persistedId = weeklyScheduleRepository
                    .findByMasterIdOrderByValidFromAsc(m.masterId()).get(0).getId();

            List<WeeklyScheduleResponse> listed = scheduleService.listWeeklySchedules(m.masterId());

            assertThat(listed).singleElement()
                    .extracting(WeeklyScheduleResponse::id)
                    .as("the listed window must carry the persisted id so editors can target PUT, "
                            + "not re-POST a duplicate that trips the overlap guard")
                    .isNotNull()
                    .isEqualTo(persistedId);
        }

        /**
         * End-to-end reproduction of the original failure mode at the service layer: create a window,
         * reload the list, then upsert using the id from the response. It must UPDATE in place (same id,
         * intervals replaced) with no {@code BusinessException} overlap — proving the round-trip id closes
         * the duplicate-window bug.
         */
        @Test
        @DisplayName("re-upsert using the listed id updates in place — no overlap, same id, intervals replaced")
        void should_updateInPlace_when_reUpsertingWithListedId() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));

            UUID listedId = scheduleService.listWeeklySchedules(m.masterId()).get(0).id();

            // Re-upsert the SAME window (overlapping itself) targeting the listed id — the original bug
            // was a re-POST (scheduleId == null) here, which tripped assertNoWindowOverlap.
            WeeklyScheduleResponse updated = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), listedId,
                    weekly(FUTURE_FROM, null, day(2, iv(10, 16))));

            assertThat(updated.id())
                    .as("the update must reuse the same window id, not create a second one")
                    .isEqualTo(listedId);
            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(m.masterId()))
                    .as("the master must still have exactly one window — updated in place, not duplicated")
                    .singleElement()
                    .extracting(ws -> ws.getId()).isEqualTo(listedId);
            assertThat(updated.days())
                    .as("the targeted update replaced the intervals (Monday → Tuesday)")
                    .singleElement()
                    .extracting(d -> d.dayOfWeek()).isEqualTo(2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3c. Re-upsert duplicate-key collision — orphan DELETE must run before re-INSERT
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Regression for the working-intervals duplicate-key bug. Editing a day's working hours by adding a
     * pause that splits the day re-sent at least one interval whose
     * {@code (schedule_id, day_of_week, start_time, end_time)} (override: {@code (exception_id, start, end)})
     * was byte-identical to a row already persisted from the previous save. With
     * {@code hibernate.order_inserts=true} the {@code ActionQueue} runs every INSERT before every orphan
     * DELETE, so the re-sent identical interval collided with {@code uq_working_intervals_no_dup} /
     * {@code uq_exception_intervals_no_dup} and the write blew up with a
     * {@link org.springframework.dao.DataIntegrityViolationException} (SQLState 23505).
     *
     * <p>The fix is the {@code repository.flush()} in {@code replaceIntervals} /
     * {@code replaceOverrideIntervals} right after {@code getIntervals().clear()}, forcing the orphan
     * DELETEs to the DB <b>before</b> the re-INSERTs. These tests must FAIL with 23505 without that flush
     * and PASS with it. The collision only manifests against the live unique index — hence a real-DB IT.
     */
    @Nested
    @DisplayName("Re-upsert duplicate-key collision — delete-before-insert (23505 regression)")
    class ReUpsertDuplicateKey {

        @Test
        @DisplayName("weekly: re-upsert a day adding a pause (morning block unchanged) succeeds — no 23505")
        void should_notCollide_when_reUpsertingWeeklyDayWithIdenticalMorningBlock() {
            SeededMaster m = seedIndependentMaster();
            // First save: a single morning block on day 2 (Tuesday).
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(2, iv(9, 13))));
            UUID listedId = scheduleService.listWeeklySchedules(m.masterId()).get(0).id();

            // Edit = "add a pause": keep the byte-identical 09:00-13:00 morning, add an afternoon block.
            // The re-sent 09:00-13:00 row equals the already-persisted one → would collide on the unique
            // index unless the orphan DELETE is flushed before the re-INSERT.
            WeeklyScheduleResponse updated = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), listedId,
                    weekly(FUTURE_FROM, null, day(2, iv(9, 13), iv(14, 18))));

            assertThat(updated.id())
                    .as("the edit must update the same window in place").isEqualTo(listedId);

            // The persisted day 2 must hold exactly the two expected intervals — proving the re-INSERT
            // landed (the bug aborted the whole write before any interval could be written/replaced).
            EffectiveDayResponse tue = scheduleService.resolveEffectiveDay(
                    m.masterId(), nextDateForDow(FUTURE_FROM, DayOfWeek.TUESDAY));
            assertThat(tue.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(tue.intervals())
                    .as("day 2 must persist exactly the unchanged morning + the new afternoon block")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(
                            tuple(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                            tuple(LocalTime.of(14, 0), LocalTime.of(18, 0)));
        }

        @Test
        @DisplayName("override: re-upsert a CUSTOM_HOURS date adding a pause (morning block unchanged) succeeds — no 23505")
        void should_notCollide_when_reUpsertingOverrideWithIdenticalMorningBlock() {
            SeededMaster m = seedIndependentMaster();
            // First save: a CUSTOM_HOURS override on FUTURE_FROM with a single morning block.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            null, null, List.of(iv(9, 13))));

            // Edit = "add a pause": keep the byte-identical 09:00-13:00 morning, add an afternoon block.
            // The re-sent 09:00-13:00 row equals the already-persisted exception interval → would collide
            // unless replaceOverrideIntervals flushes the orphan DELETE before the re-INSERT.
            ScheduleOverrideResponse updated = scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            null, null, List.of(iv(9, 13), iv(14, 18))));

            assertThat(updated.kind()).isEqualTo(ScheduleExceptionKind.CUSTOM_HOURS);

            EffectiveDayResponse day = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);
            assertThat(day.source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(day.intervals())
                    .as("the override must persist exactly the unchanged morning + the new afternoon block")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(
                            tuple(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                            tuple(LocalTime.of(14, 0), LocalTime.of(18, 0)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3d. V82 — DAY_OFF reason is OPTIONAL; null reason persists against the relaxed CHECK
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Regression for V82 ({@code chk_exc_reason} relaxed to
     * {@code (kind = 'DAY_OFF' OR (kind = 'CUSTOM_HOURS' AND reason IS NULL))}).
     *
     * <p>The highest-value guard for the contract change: only a real INSERT against the live Postgres
     * CHECK proves the migration actually loosened the constraint. Under the old V71 CHECK
     * ({@code kind = 'DAY_OFF' AND reason IS NOT NULL}) a null-reason DAY_OFF row would be rejected with
     * SQLState 23514 and the {@code upsertOverride} write would blow up — so these tests FAIL on the old
     * migration and PASS on V82. The mirrored CUSTOM_HOURS-with-reason rejection confirms V82 left the
     * other side of the CHECK byte-for-byte intact (a reason on CUSTOM_HOURS is still forbidden).
     */
    @Nested
    @DisplayName("V82 — DAY_OFF reason optional (relaxed chk_exc_reason)")
    class DayOffReasonOptional {

        @Test
        @DisplayName("persists a DAY_OFF override with a NULL reason and resolves it to OVERRIDE_DAY_OFF")
        void should_persistAndResolve_when_dayOffReasonNull() {
            SeededMaster m = seedIndependentMaster();

            // No reason supplied: the V71 CHECK would 23514-reject this INSERT; V82 accepts it.
            ScheduleOverrideResponse mgmt = scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            null, null, null));

            assertThat(mgmt.kind()).isEqualTo(ScheduleExceptionKind.DAY_OFF);
            assertThat(mgmt.reason())
                    .as("a reason-less DAY_OFF round-trips with a null reason")
                    .isNull();

            // The row really landed against the live CHECK — verify the persisted reason column is NULL.
            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(m.masterId(), FUTURE_FROM))
                    .as("the null-reason DAY_OFF override is persisted (V82 CHECK allows it)")
                    .isPresent()
                    .get()
                    .extracting(e -> e.getReason())
                    .isNull();

            // And it resolves through the public effective-day projection as a day-off with no reason.
            EffectiveDayResponse pub = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);
            assertThat(pub.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(pub.reason()).as("no reason was set, so none resolves").isNull();
            assertThat(pub.intervals()).as("a day-off carries no working intervals").isEmpty();
        }

        @Test
        @DisplayName("still persists a DAY_OFF override WITH a reason (backward compat against V82)")
        void should_stillPersist_when_dayOffReasonPresent() {
            SeededMaster m = seedIndependentMaster();

            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.VACATION, null, null));

            EffectiveDayResponse pub = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);
            assertThat(pub.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(pub.reason())
                    .as("an existing reason still persists and resolves under V82")
                    .isEqualTo(ScheduleExceptionReason.VACATION);
        }

        @Test
        @DisplayName("CUSTOM_HOURS with a reason is still rejected — V82 left the other side of the CHECK intact")
        void should_reject_when_customHoursCarriesReason() {
            SeededMaster m = seedIndependentMaster();

            // The DTO @AssertTrue guard rejects this first; the assertion pins the contract is still RED on
            // a CUSTOM_HOURS reason (V82 only loosened the DAY_OFF side, never the CUSTOM_HOURS side).
            assertThatThrownBy(() -> scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            ScheduleExceptionReason.OTHER, null, List.of(iv(10, 14)))))
                    .as("a CUSTOM_HOURS override carrying a reason must still be rejected")
                    .isInstanceOf(BusinessException.class);

            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(m.masterId(), FUTURE_FROM))
                    .as("no CUSTOM_HOURS-with-reason override may be persisted").isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 4. Data exposure — note never leaks through the effective-day projection
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Data exposure")
    class DataExposure {

        @Test
        @DisplayName("EffectiveDayResponse carries reason + intervals but NOT the free-text note; management response does")
        void should_notExposeNote_inEffectiveDay_butExposeIt_inOverrideResponse() {
            SeededMaster m = seedIndependentMaster();
            String secretNote = "Surgery at Clinic X — private";
            ScheduleOverrideResponse mgmt = scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.SICK_DAY, secretNote, null));

            // The management projection is allowed to carry the note.
            assertThat(mgmt.note()).as("management override response exposes the note to the owner")
                    .isEqualTo(secretNote);

            // The public effective-day projection must NOT — and structurally cannot — carry it.
            EffectiveDayResponse pub = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);
            assertThat(pub.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(pub.reason()).isEqualTo(ScheduleExceptionReason.SICK_DAY);
            assertThat(EffectiveDayResponse.class.getRecordComponents())
                    .as("EffectiveDayResponse must not have a note component (private free-text never leaks)")
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("note");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 5. Phase 15.6 performance guards — bounded query count (P1) + scoped eviction (P3)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Performance guards (15.6)")
    class PerformanceGuards {

        /**
         * P1 — extends the 28-day bounded-query assertion to a full ~90-day quarter span. The resolver
         * must still issue a constant number of statements (bulk-load overrides + templates, then fold
         * in memory) — no per-date N+1 as the span grows 3×.
         */
        @Test
        @DisplayName("P1 — a 90-day resolveEffectiveRange issues a constant query count (no N+1 as the span grows)")
        void should_resolve90DaySpan_withConstantQueryCount() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17)), day(3, iv(10, 14))));
            // Scatter overrides across the quarter so the fold exercises both override + template branches.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(15), ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.VACATION, null, null));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(60), ScheduleExceptionKind.CUSTOM_HOURS,
                            null, null, List.of(iv(8, 9))));

            Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            boolean wasEnabled = stats.isStatisticsEnabled();
            stats.setStatisticsEnabled(true);
            stats.clear();

            // 90 inclusive days (monday .. monday+89).
            List<EffectiveDayResponse> days = scheduleService.resolveEffectiveRange(
                    m.masterId(), monday, monday.plusDays(89));

            long queries = stats.getPrepareStatementCount();
            stats.setStatisticsEnabled(wasEnabled);

            assertThat(days).as("one entry per inclusive date across the quarter").hasSize(90);
            assertThat(byDate(days, monday.plusDays(15)).source())
                    .isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(byDate(days, monday.plusDays(60)).source())
                    .isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            // Identical bound to the 28-day case: the statement count is span-independent (2 bulk loads,
            // tolerance for read-only tx bookkeeping), proving the fold is in-memory.
            assertThat(queries)
                    .as("90-day fold must issue the same bounded query count as the 28-day fold — actual=%s", queries)
                    .isLessThanOrEqualTo(4);
        }

        /**
         * P3 — a schedule write evicts ONLY the writing master's available-slots keys (keyed by the
         * master prefix), never a blanket {@code cache.clear()}. A second, unrelated master's cached
         * slot entry must survive a write by the first master.
         */
        @Test
        @DisplayName("P3 — a write evicts only the affected master's slot-cache keys, leaving other masters' entries intact")
        void should_evictOnlyAffectedMasterSlots_onWrite() {
            SeededMaster writer = seedIndependentMaster();
            SeededMaster bystander = seedIndependentMaster();

            Cache slots = cacheManager.getCache("available-slots");
            assertThat(slots).as("available-slots cache must be configured").isNotNull();
            slots.clear();

            // Seed cache entries under the exact SimpleKey shape the resolver uses: {masterId, date, serviceId}.
            UUID svc = UUID.randomUUID();
            LocalDate date = FUTURE_FROM;
            var writerKey = new org.springframework.cache.interceptor.SimpleKey(writer.masterId(), date, svc);
            var bystanderKey = new org.springframework.cache.interceptor.SimpleKey(bystander.masterId(), date, svc);
            slots.put(writerKey, List.of());
            slots.put(bystanderKey, List.of());

            // A real schedule write by the writer — eviction fires in the afterCommit synchronization.
            scheduleService.upsertWeeklySchedule(writer.actorId(), writer.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));

            assertThat(slots.get(writerKey))
                    .as("the writing master's slot key must be evicted after the schedule write")
                    .isNull();
            assertThat(slots.get(bystanderKey))
                    .as("an unrelated master's slot key must survive — eviction is master-scoped, not a full clear")
                    .isNotNull();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static EffectiveDayResponse byDate(List<EffectiveDayResponse> days, LocalDate date) {
        return days.stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
    }

    /** First date on/after {@code from} that falls on {@code dow}. */
    private static LocalDate nextDateForDow(LocalDate from, DayOfWeek dow) {
        return from.with(TemporalAdjusters.nextOrSame(dow));
    }
}
