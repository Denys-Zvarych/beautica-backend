package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.common.TimeZones;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.service.repository.MasterServiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15.7 — drives the real {@link SlotCalculationService} end-to-end through the Phase 15.4
 * effective-availability resolver ({@link MasterScheduleService}) and {@link TimeSlotCalculator},
 * over a real Testcontainers Postgres, with the multi-interval / DST / custom-hours / day-off / gap
 * surface the slot layer is most likely to break on.
 *
 * <h2>Why a hand-built service per test</h2>
 * The slot service forbids dates outside {@code [today, today+180d]}; the two Kyiv DST transitions in a
 * year are ~210 days apart and cannot both fit one 180-day window. Rather than fragment the context with
 * two clock beans, each test constructs a {@link SlotCalculationService} with a <b>per-test fixed Kyiv
 * clock</b> while wiring the <b>real autowired</b> collaborators (resolver, calculator, repositories) —
 * so the test still exercises the genuine resolver→calculator pipeline against real persisted schedules.
 * Cache annotations ({@code @Cacheable}/{@code @CacheEvict}) are inert on the hand-built instance; cache
 * behaviour is covered by {@code SlotCalculationServiceCacheTest} and the 15.6 eviction IT and is out of
 * scope here.
 *
 * <p><b>tzdata pin (Europe/Kyiv).</b> Transitions are not hard-coded as "Ukraine observes DST" — each
 * test asserts the transition exists via {@link ZoneRules#nextTransition} and derives its gap/overlap
 * from the live tz database, then pins the expected literal date in a comment. At authoring time
 * (tzdata 2024a+): spring-forward gap {@code 2026-03-29 03:00→04:00} (Kyiv day = 23h), fall-back overlap
 * {@code 2026-10-25 04:00→03:00} (Kyiv day = 25h).
 */
@DisplayName("SlotCalculationScheduleIT — multi-interval / DST / custom / day-off / gap through the resolver (real Postgres)")
@Import(SlotCalculationScheduleIT.FrozenKyivClockConfig.class)
class SlotCalculationScheduleIT extends AbstractIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    /**
     * Freezes "today" at 2024-01-01 Kyiv so the autowired {@link MasterScheduleService} write path
     * (past-edit guard + today+2y cap) accepts every schedule fixture below. All schedule dates the
     * tests write — 2024-06 working days and the 2024 Kyiv DST dates — fall inside {@code [today, cap]}.
     * The slot now-filter is driven by a separate per-test clock in {@link #slotServiceWithTodayBefore}.
     */
    @TestConfiguration
    static class FrozenKyivClockConfig {
        @Bean
        Clock systemClock() {
            // Noon Kyiv winter (+02:00) on 2024-01-01 = 10:00Z.
            return Clock.fixed(Instant.parse("2024-01-01T10:00:00Z"), TimeZones.KYIV);
        }
    }

    @Autowired
    private MasterScheduleService masterScheduleService;

    @Autowired
    private TimeSlotCalculator timeSlotCalculator;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private MasterServiceRepository masterServiceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private record Seed(UUID masterId, UUID actorId, UUID masterServiceId) {
    }

    /**
     * Seeds an INDEPENDENT_MASTER, a service definition (durationMinutes, 0 buffer) and an active
     * master_services assignment. Returns the ids needed to drive {@code getAvailableSlots}.
     */
    private Seed seedMasterWithService(int durationMinutes) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', "
                        + "'Ind', 'Master', true, true)",
                userId, "ind-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        UUID serviceDefId = UUID.randomUUID();
        jdbc.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) VALUES (?, 'INDEPENDENT_MASTER', ?, 'Svc', ?, ?, ?, 0, "
                        + "true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId(), durationMinutes, PRICE);
        UUID masterServiceId = UUID.randomUUID();
        jdbc.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return new Seed(masterId, userId, masterServiceId);
    }

    /**
     * Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL).
     */
    private UUID resolveServiceTypeId() {
        return jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    /**
     * A slot service whose clock is fixed so {@code today} is well before {@code date} (so neither the
     * past guard nor the now-filter trims slots), wired to the real autowired collaborators.
     */
    private SlotCalculationService slotServiceWithTodayBefore(LocalDate date) {
        // Pin "now" to 00:00 Kyiv on the date's start of the prior week → date is in the future and the
        // whole working day is after "now", so TimeSlotCalculator's now-filter never trims a slot.
        Instant now = date.minusDays(7).atStartOfDay(TimeZones.KYIV).toInstant();
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
        return new SlotCalculationService(
                bookingRepository, masterServiceRepository, masterScheduleService, timeSlotCalculator, fixed);
    }

    private static WeeklyScheduleRequest weekly(LocalDate from, LocalDate to, WeeklyScheduleDayRequest... days) {
        return new WeeklyScheduleRequest(from, to, List.of(days));
    }

    private static WeeklyScheduleDayRequest day(int dow, WorkIntervalDto... intervals) {
        return new WeeklyScheduleDayRequest(dow, List.of(intervals));
    }

    private static WorkIntervalDto iv(LocalTime start, LocalTime end) {
        return new WorkIntervalDto(start, end);
    }

    private static List<LocalTime> startWallClocks(List<AvailableSlotResponse> slots) {
        return slots.stream().map(s -> s.startsAt().toLocalTime()).sorted().toList();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 1. Multi-interval days — lunch gap, custom hours, day-off, schedule gap
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-interval & override effects on slots")
    class MultiIntervalAndOverrides {

        @Test
        @DisplayName("split-shift template (morning + afternoon) yields NO slot inside the lunch gap")
        void should_produceNoSlotInLunchGap_when_templateHasSplitShift() {
            Seed s = seedMasterWithService(60); // 60-min service, 30-min step
            // 2024-06-03 is a Monday (ISO 1). Morning 09:00–12:00, lunch 12:00–14:00, afternoon 14:00–17:00.
            LocalDate monday = LocalDate.of(2024, 6, 3);
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(monday, null, day(1,
                            iv(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                            iv(LocalTime.of(14, 0), LocalTime.of(17, 0)))));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(monday)
                    .getAvailableSlots(s.masterId(), monday, s.masterServiceId());

            List<LocalTime> starts = startWallClocks(slots);
            // Morning slots: 09:00,09:30,10:00,10:30,11:00 (last 60-min slot fits before 12:00).
            // Afternoon slots: 14:00,14:30,15:00,15:30,16:00.
            assertThat(starts).containsExactly(
                    LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(10, 30),
                    LocalTime.of(11, 0),
                    LocalTime.of(14, 0), LocalTime.of(14, 30), LocalTime.of(15, 0), LocalTime.of(15, 30),
                    LocalTime.of(16, 0));
            assertThat(starts)
                    .as("no slot starts inside the 12:00–14:00 lunch gap")
                    .doesNotContain(LocalTime.of(12, 0), LocalTime.of(12, 30), LocalTime.of(13, 0),
                            LocalTime.of(13, 30));
        }

        @Test
        @DisplayName("CUSTOM_HOURS override replaces the template hours for that date")
        void should_useOverrideHours_when_customHoursOverridePresent() {
            Seed s = seedMasterWithService(60);
            LocalDate monday = LocalDate.of(2024, 6, 3); // ISO 1
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(monday, null, day(1, iv(LocalTime.of(9, 0), LocalTime.of(17, 0)))));
            // Override that Monday to a narrow 10:00–12:00 custom window.
            masterScheduleService.upsertOverride(s.actorId(), s.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(LocalTime.of(10, 0), LocalTime.of(12, 0)))));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(monday)
                    .getAvailableSlots(s.masterId(), monday, s.masterServiceId());

            assertThat(startWallClocks(slots))
                    .as("only the override's 10:00–12:00 window produces slots (10:00, 10:30, 11:00)")
                    .containsExactly(LocalTime.of(10, 0), LocalTime.of(10, 30), LocalTime.of(11, 0));
        }

        @Test
        @DisplayName("DAY_OFF override produces zero slots even though the template covers the date")
        void should_produceNoSlots_when_dayOffOverridePresent() {
            Seed s = seedMasterWithService(60);
            LocalDate monday = LocalDate.of(2024, 6, 3);
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(monday, null, day(1, iv(LocalTime.of(9, 0), LocalTime.of(17, 0)))));
            masterScheduleService.upsertOverride(s.actorId(), s.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.DAY_OFF, null));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(monday)
                    .getAvailableSlots(s.masterId(), monday, s.masterServiceId());

            assertThat(slots).as("a day-off closes the date — no slots").isEmpty();
        }

        @Test
        @DisplayName("schedule gap (no covering window) produces zero slots")
        void should_produceNoSlots_when_dateFallsInScheduleGap() {
            Seed s = seedMasterWithService(60);
            // Template only covers June; query a July date that no window covers.
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30),
                            day(1, iv(LocalTime.of(9, 0), LocalTime.of(17, 0)))));
            LocalDate gapDate = LocalDate.of(2024, 7, 8); // a Monday outside the window

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(gapDate)
                    .getAvailableSlots(s.masterId(), gapDate, s.masterServiceId());

            assertThat(slots).as("a gap date resolves to NO_SCHEDULE — zero slots").isEmpty();
        }

        @Test
        @DisplayName("legacy-backfill regression — an open-ended legacy-style window still produces slots for a normal day")
        void should_produceSlots_when_openEndedLegacyStyleWindow() {
            // The V72 backfill creates exactly this shape: one open-ended (valid_to NULL) weekly_schedule
            // per master, valid_from pinned to a far-past epoch. Reproduce it via the service and confirm
            // the resolver→slot pipeline still emits slots for an ordinary working day.
            Seed s = seedMasterWithService(60);
            LocalDate workday = LocalDate.of(2024, 6, 4); // Tuesday (ISO 2)
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(LocalDate.of(2024, 1, 1), null, day(2, iv(LocalTime.of(9, 0), LocalTime.of(11, 0)))));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(workday)
                    .getAvailableSlots(s.masterId(), workday, s.masterServiceId());

            assertThat(startWallClocks(slots))
                    .as("open-ended legacy-style window yields the expected Tuesday slots")
                    .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0));
        }

        @Test
        @DisplayName("an existing booking removes the overlapping slot from a custom-hours day")
        void should_excludeBookedSlot_when_bookingOverlapsCustomHours() {
            Seed s = seedMasterWithService(60);
            LocalDate monday = LocalDate.of(2024, 6, 3);
            masterScheduleService.upsertOverride(s.actorId(), s.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(LocalTime.of(9, 0), LocalTime.of(12, 0)))));
            // Book 10:00–11:00 Kyiv on that date (PENDING).
            insertBooking(s, monday, LocalTime.of(10, 0), LocalTime.of(11, 0));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(monday)
                    .getAvailableSlots(s.masterId(), monday, s.masterServiceId());

            List<LocalTime> starts = startWallClocks(slots);
            assertThat(starts)
                    .as("slots whose 60-min window overlaps the 10:00–11:00 booking are removed")
                    .doesNotContain(LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(10, 30));
            assertThat(starts).contains(LocalTime.of(9, 0), LocalTime.of(11, 0));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 1b. Night shift as TWO single-day rows on adjacent ISO weekdays — post-midnight
    //     booking subtraction (MEDIUM-2). The model FORBIDS cross-midnight intervals
    //     (endTime <= startTime) at four layers; a night shift is the late part of one
    //     ISO day plus the early part of the next, never one wrapping row.
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Night shift split across two ISO days — post-midnight bookings are subtracted")
    class CrossMidnightBookingSubtraction {

        @Test
        @DisplayName("a booking on the early-Tuesday tail is subtracted; the late-Monday head is unaffected (no double-book)")
        void should_subtractPostMidnightBooking_when_nightShiftSpansTwoIsoDays() {
            Seed s = seedMasterWithService(60); // 60-min service, 30-min step
            // 2024-06-03 is a Monday (ISO 1), 2024-06-04 a Tuesday (ISO 2). The night shift is modelled
            // contract-correctly as TWO single-calendar-day intervals in one weekly template: the late
            // part of Monday (22:00–23:59) and the early part of Tuesday (00:00–02:00). Each row obeys
            // endTime > startTime — no wrapping interval.
            LocalDate monday = LocalDate.of(2024, 6, 3);
            LocalDate tuesday = monday.plusDays(1);
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(monday, null,
                            day(1, iv(LocalTime.of(22, 0), LocalTime.of(23, 59))),
                            day(2, iv(LocalTime.of(0, 0), LocalTime.of(2, 0)))));

            // getAvailableSlots resolves ONE calendar date, so the night shift is verified across two
            // queries — one per ISO day the shift touches.

            // (a) Monday query — the late-Monday head produces its slots, unaffected by any Tuesday booking.
            List<AvailableSlotResponse> mondaySlots = slotServiceWithTodayBefore(monday)
                    .getAvailableSlots(s.masterId(), monday, s.masterServiceId());

            assertThat(startWallClocks(mondaySlots))
                    .as("late-Monday head 22:00–23:59 yields exactly the 22:00 / 22:30 slots "
                            + "(a 60-min slot at 23:00 would end 00:00, past the 23:59 interval end)")
                    .containsExactly(LocalTime.of(22, 0), LocalTime.of(22, 30));

            // (b) Tuesday query — book 00:00–01:00 inside the early-Tuesday tail and confirm subtraction.
            insertBooking(s, tuesday, LocalTime.of(0, 0), LocalTime.of(1, 0));

            List<AvailableSlotResponse> tuesdaySlots = slotServiceWithTodayBefore(tuesday)
                    .getAvailableSlots(s.masterId(), tuesday, s.masterServiceId());

            List<LocalTime> tuesdayStarts = startWallClocks(tuesdaySlots);
            // 60-min slots whose window overlaps the 00:00–01:00 booking are removed: 00:00–01:00 occupied,
            // 00:30–01:30 overlaps → both excluded. 01:00–02:00 is clear and stays bookable.
            assertThat(tuesdayStarts)
                    .as("post-midnight booking is subtracted from the early-Tuesday tail")
                    .doesNotContain(LocalTime.of(0, 0), LocalTime.of(0, 30));
            assertThat(tuesdayStarts)
                    .as("the slot clear of the booking remains bookable")
                    .contains(LocalTime.of(1, 0));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 2. DST — spring-forward and fall-back, derived from Europe/Kyiv tz rules
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DST (Europe/Kyiv) — slot effects through the pipeline")
    class Dst {

        @Test
        @DisplayName("spring-forward — a multi-interval day straddling the skipped hour produces NO slot inside the gap")
        void should_produceNoSlotInSkippedHour_when_springForward() {
            // Derive the spring-forward transition from tz rules rather than assuming it.
            // tzdata pin: Europe/Kyiv 2024 spring-forward = gap 2024-03-31 03:00→04:00 local.
            LocalDate dstDate = LocalDate.of(2024, 3, 31);
            assertThat(isSpringForwardDay(dstDate))
                    .as("Europe/Kyiv tz rules report a gap (spring-forward) on %s", dstDate).isTrue();
            assertThat(kyivDayLengthHours(dstDate))
                    .as("a spring-forward Kyiv day is 23 hours long").isEqualTo(23);

            Seed s = seedMasterWithService(30); // 30-min service so a single skipped hour matters
            // 2024-03-31 is ISO weekday 7 (Sunday). Window 01:00–06:00 straddles the 03:00→04:00 gap.
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(LocalDate.of(2024, 3, 1), null,
                            day(7, iv(LocalTime.of(1, 0), LocalTime.of(6, 0)))));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(dstDate)
                    .getAvailableSlots(s.masterId(), dstDate, s.masterServiceId());

            List<LocalTime> starts = startWallClocks(slots);
            // The local wall-clock times 03:00 and 03:30 do not exist on this date — no slot may start there.
            assertThat(starts)
                    .as("no slot starts in the skipped wall-clock hour [03:00, 04:00)")
                    .doesNotContain(LocalTime.of(3, 0), LocalTime.of(3, 30));
            // Real wall-clock slots before and after the gap are still offered.
            assertThat(starts).contains(LocalTime.of(1, 0), LocalTime.of(2, 30), LocalTime.of(4, 0),
                    LocalTime.of(5, 0));
            // Every emitted slot is exactly 30 minutes of real elapsed time (no slot spans the gap as if
            // it were a normal hour — the instant math collapses the missing hour).
            assertThat(slots)
                    .allSatisfy(slot -> assertThat(Duration.between(slot.startsAt(), slot.endsAt()))
                            .isEqualTo(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("fall-back — the duplicated hour produces no duplicate/overlapping slot starts; the day is 25h")
        void should_produceNoDuplicateSlots_when_fallBack() {
            // tzdata pin: Europe/Kyiv 2024 fall-back = overlap 2024-10-27 04:00→03:00 local (25h day).
            LocalDate dstDate = LocalDate.of(2024, 10, 27);
            assertThat(isFallBackDay(dstDate))
                    .as("Europe/Kyiv tz rules report an overlap (fall-back) on %s", dstDate).isTrue();
            assertThat(kyivDayLengthHours(dstDate))
                    .as("a fall-back Kyiv day is 25 hours long").isEqualTo(25);

            Seed s = seedMasterWithService(30);
            // 2024-10-27 is ISO weekday 7 (Sunday). Window 01:00–06:00 spans the 03:00↔04:00 overlap.
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(LocalDate.of(2024, 10, 1), null,
                            day(7, iv(LocalTime.of(1, 0), LocalTime.of(6, 0)))));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(dstDate)
                    .getAvailableSlots(s.masterId(), dstDate, s.masterServiceId());

            // No two slots may share the same start INSTANT (the duplicated wall-clock hour must not
            // produce two distinct bookable instants colliding).
            List<Instant> startInstants = slots.stream().map(x -> x.startsAt().toInstant()).toList();
            assertThat(startInstants)
                    .as("every slot start is a distinct instant — no duplicate from the repeated hour")
                    .doesNotHaveDuplicates();
            // No two slots overlap in instant time (the calculator steps by 30 real minutes monotonically).
            List<Instant> sorted = startInstants.stream().sorted().toList();
            for (int i = 1; i < sorted.size(); i++) {
                assertThat(Duration.between(sorted.get(i - 1), sorted.get(i)))
                        .as("consecutive slot starts are >= one real step apart, never overlapping")
                        .isGreaterThanOrEqualTo(Duration.ofMinutes(30));
            }
            // The 25h day's extra real hour means MORE bookable instants than a normal 23/24h window:
            // a 01:00–06:00 wall window covers 6 real hours on the fall-back day → 12 thirty-minute starts.
            assertThat(slots)
                    .as("the repeated hour adds real bookable time — 6 real hours → 12 slots of 30 min")
                    .hasSize(12);
        }

        @Test
        @DisplayName("a 09:00–18:00 window on the spring-forward day yields wall-clock-correct first and last slots")
        void should_keepWallClockEndpoints_when_windowOnDstChangeDay() {
            LocalDate dstDate = LocalDate.of(2024, 3, 31); // spring-forward; 09:00–18:00 is clear of the gap
            assertThat(isSpringForwardDay(dstDate)).isTrue();

            Seed s = seedMasterWithService(60);
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(LocalDate.of(2024, 3, 1), null,
                            day(7, iv(LocalTime.of(9, 0), LocalTime.of(18, 0)))));

            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(dstDate)
                    .getAvailableSlots(s.masterId(), dstDate, s.masterServiceId());

            List<LocalTime> starts = startWallClocks(slots);
            assertThat(starts).first()
                    .as("first slot starts at the wall-clock window open 09:00").isEqualTo(LocalTime.of(9, 0));
            assertThat(starts).last()
                    .as("last 60-min slot starts at 17:00 (ends at the 18:00 wall-clock close)")
                    .isEqualTo(LocalTime.of(17, 0));
            // Endpoints are correct in wall-clock time even though the day is only 23 real hours; the gap
            // sits at 03:00–04:00, far from this 09:00–18:00 window, so the slot count is the normal 17.
            assertThat(slots).hasSize(17);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3. Working-days / slots consistency (Phase 15.11) — the CLIENT-safe boolean signal
    //    from MasterScheduleService.getClientWorkingDays must agree with what the CLIENT
    //    can actually book via GET /{masterId}/slots for the same date. Neither endpoint
    //    reuses the other's full code path (getClientWorkingDays only maps
    //    resolveEffectiveRange; getAvailableSlots additionally runs the slot calculator and
    //    booking subtraction), so a drift between them would silently mislead the mobile
    //    calendar — greying out a bookable day, or showing an open day with nothing to book.
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("working-days boolean agrees with the real /slots pipeline (Phase 15.11)")
    class WorkingDaysSlotsConsistency {

        @Test
        @DisplayName("working:true on a template day correlates with at least one real bookable slot")
        void should_correlateWorkingTrue_withNonEmptySlots_when_templateCoversDate() {
            Seed s = seedMasterWithService(60);
            LocalDate monday = LocalDate.of(2024, 6, 3); // ISO 1
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(monday, null, day(1, iv(LocalTime.of(9, 0), LocalTime.of(17, 0)))));

            List<MasterWorkingDayResponse> workingDays =
                    masterScheduleService.getClientWorkingDays(s.masterId(), monday, monday);
            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(monday)
                    .getAvailableSlots(s.masterId(), monday, s.masterServiceId());

            assertThat(workingDays).containsExactly(new MasterWorkingDayResponse(monday, true));
            assertThat(slots)
                    .as("a day the CLIENT-safe endpoint reports working:true must actually offer "
                            + "bookable slots via /slots — otherwise the mobile calendar shows the day "
                            + "as open with nothing to book")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("working:false on a DAY_OFF override correlates with zero real bookable slots")
        void should_correlateWorkingFalse_withEmptySlots_when_dayOffOverridePresent() {
            Seed s = seedMasterWithService(60);
            LocalDate monday = LocalDate.of(2024, 6, 3); // ISO 1
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(monday, null, day(1, iv(LocalTime.of(9, 0), LocalTime.of(17, 0)))));
            masterScheduleService.upsertOverride(s.actorId(), s.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.DAY_OFF, null));

            List<MasterWorkingDayResponse> workingDays =
                    masterScheduleService.getClientWorkingDays(s.masterId(), monday, monday);
            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(monday)
                    .getAvailableSlots(s.masterId(), monday, s.masterServiceId());

            assertThat(workingDays).containsExactly(new MasterWorkingDayResponse(monday, false));
            assertThat(slots)
                    .as("a day the CLIENT-safe endpoint reports working:false must never offer a "
                            + "bookable slot via /slots — otherwise a CLIENT could book a day the "
                            + "calendar greys out as non-working")
                    .isEmpty();
        }

        @Test
        @DisplayName("working:false on a schedule gap correlates with zero real bookable slots")
        void should_correlateWorkingFalse_withEmptySlots_when_dateFallsInScheduleGap() {
            Seed s = seedMasterWithService(60);
            // Template only covers June; query a July date no window covers.
            masterScheduleService.upsertWeeklySchedule(s.actorId(), s.masterId(), null,
                    weekly(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30),
                            day(1, iv(LocalTime.of(9, 0), LocalTime.of(17, 0)))));
            LocalDate gapDate = LocalDate.of(2024, 7, 8); // a Monday outside the window

            List<MasterWorkingDayResponse> workingDays =
                    masterScheduleService.getClientWorkingDays(s.masterId(), gapDate, gapDate);
            List<AvailableSlotResponse> slots = slotServiceWithTodayBefore(gapDate)
                    .getAvailableSlots(s.masterId(), gapDate, s.masterServiceId());

            assertThat(workingDays).containsExactly(new MasterWorkingDayResponse(gapDate, false));
            assertThat(slots)
                    .as("a schedule gap resolves to NO_SCHEDULE on both endpoints — working:false and "
                            + "zero bookable slots must agree")
                    .isEmpty();
        }
    }

    // ── tz helpers (derive transitions from the live tz database) ────────────────────

    private static boolean isSpringForwardDay(LocalDate date) {
        var t = transitionOn(date);
        return t != null && t.isGap();
    }

    private static boolean isFallBackDay(LocalDate date) {
        var t = transitionOn(date);
        return t != null && t.isOverlap();
    }

    private static java.time.zone.ZoneOffsetTransition transitionOn(LocalDate date) {
        ZoneRules rules = TimeZones.KYIV.getRules();
        var next = rules.nextTransition(date.minusDays(1).atStartOfDay(TimeZones.KYIV).toInstant());
        if (next == null) {
            return null;
        }
        LocalDate transitionDate = next.getInstant().atZone(TimeZones.KYIV).toLocalDate();
        return transitionDate.equals(date) ? next : null;
    }

    private static int kyivDayLengthHours(LocalDate date) {
        return (int) Duration.between(
                date.atStartOfDay(TimeZones.KYIV),
                date.plusDays(1).atStartOfDay(TimeZones.KYIV)).toHours();
    }

    // ── booking seed helper ──────────────────────────────────────────────────────

    private void insertBooking(Seed s, LocalDate date, LocalTime start, LocalTime end) {
        var startsAt = date.atTime(start).atZone(TimeZones.KYIV).toOffsetDateTime();
        var endsAt = date.atTime(end).atZone(TimeZones.KYIV).toOffsetDateTime();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbc.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, 0, NOW(), NOW())",
                UUID.randomUUID(), s.actorId(), s.masterId(), s.masterServiceId(),
                startsAt, endsAt, PRICE, minutes);
    }
}
