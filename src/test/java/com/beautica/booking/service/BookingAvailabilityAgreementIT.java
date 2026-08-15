package com.beautica.booking.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.AppointmentCancelRequest;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.master.service.MasterScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 23.x — the booking-availability <b>agreement</b> integration test: it drives the REAL
 * {@link SlotCalculationService} (both the {@code serviceId}-aware {@code getBookableWorkingDays} day
 * projection AND the {@code getAvailableSlots} slot list), the REAL {@link MasterScheduleService}
 * schedule-shape mode, and the REAL {@link BookingStartsAtValidator} booking-create guard — all off ONE
 * shared clock bean, against a real Testcontainers Postgres seeded with genuine schedule + booking rows.
 *
 * <h2>The bug this locks down (why it exists)</h2>
 * The mobile booking calendar decided a day was selectable from the SCHEDULE-SHAPE working-days boolean
 * ({@link MasterScheduleService#getClientWorkingDays}: "does the master carry intervals that day?"), while
 * the slot screen ({@code getAvailableSlots}) additionally subtracts PENDING/CONFIRMED bookings, requires
 * the whole service duration to fit, and drops slots below the {@code now + 15 min} lead-time cutoff. So a
 * day showed as selectable and then yielded zero slots — "Немає вільного часу". The fix adds a
 * {@code serviceId}-aware working-days mode that shares ONE free-range computation and ONE cutoff with
 * {@code /slots}, so the two endpoints can never disagree.
 *
 * <h2>Why these are integration tests, not (only) unit tests</h2>
 * {@code SlotCalculationServiceTest} already pins {@code getBookableWorkingDays} with a MOCKED
 * {@link com.beautica.common.util.TimeSlotCalculator} — it proves the service's own logic but NOT that the
 * two endpoints agree once the REAL resolver + calculator + booking subtraction run end-to-end. The gate is
 * precisely that agreement, so every case here calls BOTH endpoints through the genuine pipeline and asserts
 * they return the same verdict.
 *
 * <h2>Clock &amp; seeding</h2>
 * "Now" is pinned to {@code 2026-06-15 17:20 Europe/Kyiv} via {@link FrozenKyivClockConfig} — a single bean
 * shared by {@code SlotCalculationService}, {@code MasterScheduleService} and the create-guard, so no two
 * consumers can key off a different "now". Schedules, services and bookings are seeded via raw JDBC (as
 * {@code BookingMasterServiceIT} / {@code SlotCalculationScheduleIT} do) so a date can be pinned exactly.
 */
@DisplayName("BookingAvailabilityAgreementIT — working-days(serviceId) ⇔ /slots ⇔ booking-create agree (real Postgres, fixed clock)")
@Import(BookingAvailabilityAgreementIT.FrozenKyivClockConfig.class)
class BookingAvailabilityAgreementIT extends AbstractIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    /** 2026-06-15 is inside Kyiv summer time (EEST, UTC+3); the horizon [today, today+180] is DST-stable. */
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final LocalTime NOW_LOCAL = LocalTime.of(17, 20); // cutoff = 17:35 Kyiv

    /**
     * Pins "now" to {@code TODAY 17:20 Kyiv} for the whole context. Named {@code systemClock} to OVERRIDE
     * the application's {@code ClockConfig#systemClock} bean (test profile enables bean overriding), so the
     * autowired {@link SlotCalculationService}, {@link MasterScheduleService} and {@link BookingStartsAtValidator}
     * all read the same frozen instant — the agreement cannot be an artefact of two clocks.
     */
    @TestConfiguration
    static class FrozenKyivClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(
                    TODAY.atTime(NOW_LOCAL).atZone(TimeZones.KYIV).toInstant(),
                    TimeZones.KYIV);
        }
    }

    @Autowired
    private SlotCalculationService slotCalculationService;

    @Autowired
    private MasterScheduleService masterScheduleService;

    @Autowired
    private Clock kyivClock;

    /** Cases 11–16 only — the REAL booking-write path, so the afterCommit eviction hook fires. */
    @Autowired
    private BookingService bookingService;

    /** Case 17 only — the appointment (multi-service visit) analogue of the booking-write path. */
    @Autowired
    private AppointmentTransitionService appointmentTransitionService;

    /** Case 18 only — the GUEST (LINK) cancel-by-token path, a third writer into the same sweep. */
    @Autowired
    private BookingCancellationService bookingCancellationService;

    /** Cases 11–18 only — read-only inspection of the live {@code available-slots} Caffeine cache. */
    @Autowired
    private CacheManager cacheManager;

    // ── the two endpoints, called through the genuine pipeline ──────────────────────────────

    /** {@code GET /masters/{id}/working-days?serviceId=…} verdict for a single date. */
    private boolean bookableDay(UUID masterId, LocalDate date, UUID masterServiceId) {
        List<MasterWorkingDayResponse> days =
                slotCalculationService.getBookableWorkingDays(masterId, date, date, masterServiceId);
        assertThat(days).as("single-date projection returns exactly one day").hasSize(1);
        assertThat(days.get(0).date()).isEqualTo(date);
        return days.get(0).working();
    }

    /** {@code GET /masters/{id}/slots} start wall-clocks for a single date. */
    private List<LocalTime> slotStarts(UUID masterId, LocalDate date, UUID masterServiceId) {
        return slotCalculationService.getAvailableSlots(masterId, date, masterServiceId).stream()
                .map(s -> s.startsAt().toLocalTime())
                .sorted()
                .toList();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 1 — THE regression: today past the cutoff shows as non-working AND yields zero slots
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 1 — anti-drift: at 17:20 a 09:00–18:00 master with a 60-min service reports today working=FALSE and /slots EMPTY (both agree)")
    void should_agreeTodayUnavailable_when_lastFittingSlotIsBelowCutoff() {
        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        // Full working day today; the ONLY reason the day is unbookable is the 17:35 cutoff eating the tail.
        seedInterval(m.masterId(), TODAY, null, TODAY.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(18, 0));

        boolean working = bookableDay(m.masterId(), TODAY, svc);
        List<LocalTime> slots = slotStarts(m.masterId(), TODAY, svc);

        assertThat(working)
                .as("the last 60-min slot that fits is 17:00; at 17:20 the cutoff is 17:35, so no slot "
                        + "at/after the cutoff survives → the serviceId-aware day MUST be non-working "
                        + "(the schedule-shape mode would still say true — that was the bug)")
                .isFalse();
        assertThat(slots)
                .as("/slots must be empty for the exact same today/service")
                .isEmpty();
        assertThat(working)
                .as("agreement invariant: working ⇔ at least one bookable slot")
                .isEqualTo(!slots.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 2 — the service DURATION must fit a remaining free range (the user's explicit example)
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 2 — a 3h service on a 16:00–18:00 free gap → day FALSE (16:00+3h>18:00); a 1h service on the same day/booking → day TRUE")
    void should_gateDayOnDurationFit_when_onlyAShortFreeRangeRemains() {
        // Future day so the now-cutoff is not the deciding factor — this isolates the duration-fit rule.
        LocalDate day = TODAY.plusDays(7);

        Master m = seedIndependentMaster();
        UUID svc3h = addService(m, 180, 0);
        UUID svc1h = addService(m, 60, 0);
        seedInterval(m.masterId(), day, day, day.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(18, 0));
        // A booking occupies 09:00–16:00, leaving a single free gap 16:00–18:00 (2 hours).
        UUID client = seedClient();
        insertBooking(m.masterId(), svc1h, client, day, LocalTime.of(9, 0), LocalTime.of(16, 0), "CONFIRMED");

        // 3-hour service: 16:00 + 3h = 19:00 > 18:00 → does not fit the 2-hour gap.
        assertThat(bookableDay(m.masterId(), day, svc3h))
                .as("a 3h service cannot fit the 16:00–18:00 free gap → day unavailable")
                .isFalse();
        assertThat(slotStarts(m.masterId(), day, svc3h))
                .as("/slots agrees — no 3h start fits")
                .isEmpty();

        // 1-hour service: fits at 16:00, 16:30, 17:00.
        assertThat(bookableDay(m.masterId(), day, svc1h))
                .as("a 1h service fits the 16:00–18:00 gap → day available")
                .isTrue();
        assertThat(slotStarts(m.masterId(), day, svc1h))
                .as("/slots agrees — the 1h starts fill the free gap exactly")
                .containsExactly(LocalTime.of(16, 0), LocalTime.of(16, 30), LocalTime.of(17, 0));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 3 — a FUTURE day whose every slot is booked out (proves it is not just a today/past fix)
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 3 — a future day fully covered by two CONFIRMED bookings → day FALSE and /slots EMPTY (multiple bookings tile and fully subtract)")
    void should_agreeDayUnavailable_when_futureDayFullyBookedByTwoConfirmedBookings() {
        // Track 24.x retired PENDING (bookings are now auto-confirmed at creation); this case used
        // to mix a CONFIRMED + a PENDING booking to prove BOTH active statuses subtract availability.
        // With a single active pre-terminal status, the scenario is re-expressed as two DISTINCT
        // CONFIRMED bookings that together tile the whole interval — still proving that availability
        // subtraction aggregates correctly across multiple bookings, not just a single one.
        LocalDate day = TODAY.plusDays(10);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        seedInterval(m.masterId(), day, day, day.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
        UUID client = seedClient();
        // The two bookings tile the whole 09:00–17:00 interval.
        insertBooking(m.masterId(), svc, client, day, LocalTime.of(9, 0), LocalTime.of(13, 0), "CONFIRMED");
        insertBooking(m.masterId(), svc, client, day, LocalTime.of(13, 0), LocalTime.of(17, 0), "CONFIRMED");

        boolean working = bookableDay(m.masterId(), day, svc);
        List<LocalTime> slots = slotStarts(m.masterId(), day, svc);

        assertThat(working)
                .as("every slot is taken by a CONFIRMED booking → non-working, even "
                        + "though the schedule shape has intervals that day")
                .isFalse();
        assertThat(slots).as("/slots agrees — nothing left to book").isEmpty();
        assertThat(working).as("agreement invariant").isEqualTo(!slots.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 4 — the lead-time cutoff is ONE shared floor across /slots, the day projection, AND create
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 4 — cutoff alignment: a sub-cutoff (17:30) start is offered by NEITHER /slots NOR create; the at-cutoff (18:00) start is offered by BOTH")
    void should_alignSlotsProjectionAndCreate_onBookableCutoff() {
        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        // Interval today 17:00–19:00. now=17:20, cutoff=17:35. 60-min candidates: 17:00, 17:30, 18:00
        // (18:30 would end 19:30 > 19:00). The cutoff drops 17:00 and 17:30; only 18:00 survives.
        seedInterval(m.masterId(), TODAY, null, TODAY.getDayOfWeek().getValue(),
                LocalTime.of(17, 0), LocalTime.of(19, 0));

        List<LocalTime> slots = slotStarts(m.masterId(), TODAY, svc);
        assertThat(slots)
                .as("/slots offers only the at/after-cutoff 18:00 start; the sub-cutoff 17:00 and 17:30 "
                        + "candidates are dropped by the same BookingWindow.bookableCutoff")
                .containsExactly(LocalTime.of(18, 0));
        assertThat(bookableDay(m.masterId(), TODAY, svc))
                .as("the day projection agrees the day is bookable (18:00 survives the cutoff)")
                .isTrue();

        // The booking-create guard must reject the SAME 17:30 start that /slots refused to offer, and
        // accept the 18:00 it did offer — all keyed off BookingWindow.bookableCutoff(kyivClock).
        OffsetDateTime subCutoff = TODAY.atTime(17, 30).atZone(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime atCutoff = TODAY.atTime(18, 0).atZone(TimeZones.KYIV).toOffsetDateTime();
        assertThatThrownBy(() -> BookingStartsAtValidator.validate(subCutoff, kyivClock))
                .as("booking-create rejects the sub-cutoff 17:30 that /slots and the projection also dropped")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("15 minutes");
        assertThatCode(() -> BookingStartsAtValidator.validate(atCutoff, kyivClock))
                .as("booking-create accepts the 18:00 start that /slots offered")
                .doesNotThrowAnyException();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 5 — the serviceId-ABSENT schedule-shape contract is UNCHANGED (regression guard)
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 5 — serviceId ABSENT still reports working=TRUE for a fully-booked day (schedule-shape UI unchanged) while serviceId PRESENT reports FALSE")
    void should_keepScheduleShapeContract_when_serviceIdAbsent() {
        LocalDate day = TODAY.plusDays(10);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        seedInterval(m.masterId(), day, day, day.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
        UUID client = seedClient();
        insertBooking(m.masterId(), svc, client, day, LocalTime.of(9, 0), LocalTime.of(17, 0), "CONFIRMED");

        // serviceId ABSENT — the master's own schedule UI: bookings are irrelevant, intervals exist → true.
        assertThat(masterScheduleService.getClientWorkingDays(m.masterId(), day, day))
                .as("the schedule-shape mode must NOT change — it never subtracts bookings")
                .containsExactly(new MasterWorkingDayResponse(day, true));

        // serviceId PRESENT — availability-aware: the full-day booking removes every slot → false.
        assertThat(slotCalculationService.getBookableWorkingDays(m.masterId(), day, day, svc))
                .as("the availability-aware mode subtracts the booking → false")
                .containsExactly(new MasterWorkingDayResponse(day, false));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 6 — duration-fit is an EXACT boundary: a service filling the free range fits; +1 min does not
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 6 — a 120-min service on a 16:00–18:00 interval fits EXACTLY (day TRUE, one slot); a 121-min service does not (day FALSE, empty)")
    void should_gateOnExactDurationFit_atTheBoundary() {
        LocalDate day = TODAY.plusDays(7); // future so the cutoff is not the deciding factor

        Master m = seedIndependentMaster();
        UUID svc120 = addService(m, 120, 0);
        UUID svc121 = addService(m, 121, 0);
        // A single 2-hour interval, no bookings — the free range is exactly 16:00–18:00.
        seedInterval(m.masterId(), day, day, day.getDayOfWeek().getValue(),
                LocalTime.of(16, 0), LocalTime.of(18, 0));

        // 120 min: 16:00 + 2h = 18:00 == interval end → fits exactly, once.
        assertThat(bookableDay(m.masterId(), day, svc120))
                .as("a 120-min service exactly fills the 2h interval → day bookable")
                .isTrue();
        assertThat(slotStarts(m.masterId(), day, svc120))
                .as("/slots agrees — the one 16:00 start that exactly fits")
                .containsExactly(LocalTime.of(16, 0));

        // 121 min: 16:00 + 2h1m = 18:01 > 18:00 → does not fit.
        assertThat(bookableDay(m.masterId(), day, svc121))
                .as("one minute longer than the interval cannot fit → day unavailable")
                .isFalse();
        assertThat(slotStarts(m.masterId(), day, svc121))
                .as("/slots agrees — nothing fits")
                .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 7 — CUSTOM_HOURS multi-interval day: only the interval long enough fits; override wins
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 7 — CUSTOM_HOURS override [09:00–11:00 + 14:00–18:00]; a 3h service fits ONLY the afternoon (day TRUE, slots only afternoon)")
    void should_reflectCustomHoursOverride_andGateMultiIntervalDayOnDurationFit() {
        LocalDate day = TODAY.plusDays(9);

        Master m = seedIndependentMaster();
        UUID svc3h = addService(m, 180, 0);
        // No weekly template — a pure CUSTOM_HOURS override with two disjoint intervals. The availability
        // mode must reflect the OVERRIDE (not a template, which does not exist), and gate on duration-fit
        // across the two intervals: 09:00–11:00 (2h) cannot hold 3h; 14:00–18:00 (4h) can.
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(11, 0)),
                                new WorkIntervalDto(LocalTime.of(14, 0), LocalTime.of(18, 0)))));

        assertThat(bookableDay(m.masterId(), day, svc3h))
                .as("a 3h service fits the afternoon interval of the override → day bookable")
                .isTrue();
        assertThat(slotStarts(m.masterId(), day, svc3h))
                .as("/slots agrees — no morning start (2h interval too short); only 14:00/14:30/15:00 in "
                        + "the afternoon (last 3h slot ends 18:00)")
                .containsExactly(LocalTime.of(14, 0), LocalTime.of(14, 30), LocalTime.of(15, 0));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 7b — EXPLICIT_TIMES day: the DECLARED times ARE the slot set (2026-08-11 HIGH-1 fix)
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 7b — an EXPLICIT_TIMES override declaring 13:00 and 15:00 offers EXACTLY those two "
            + "starts for a 60-min service — no fabricated 13:30/14:00, and 15:00 is NOT swallowed")
    void should_offerExactlyTheDeclaredTimes_when_dayIsExplicitTimes() {
        LocalDate day = TODAY.plusDays(11);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        // The resolver projects this day as the DERIVED window [13:00..15:00] (a display artifact) plus
        // the declared times. Striding that window on the 30-min grid used to offer 13:00/13:30/14:00 —
        // two starts the master never declared — while 15:00, which they DID declare, could never be
        // offered at all: it is the window END, so nothing can start there and still fit.
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null,
                        List.of(LocalTime.of(13, 0), LocalTime.of(15, 0))));

        assertThat(slotStarts(m.masterId(), day, svc))
                .as("the declared times ARE the slot set — never a grid across the derived window")
                .containsExactly(LocalTime.of(13, 0), LocalTime.of(15, 0));
        assertThat(bookableDay(m.masterId(), day, svc))
                .as("working-days agrees: the day has declared times that survive every filter")
                .isTrue();

        // A day declaring exactly ONE time projects a DEGENERATE derived window (min == max), which the
        // interval walk rejected outright (workEnd == workStart → no slots), so such a day could not be
        // booked at all. Its own master/date, so the assertion is not served from a warm cache entry.
        Master solo = seedIndependentMaster();
        UUID soloSvc = addService(solo, 60, 0);
        masterScheduleService.upsertOverride(solo.userId(), solo.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null, List.of(LocalTime.of(13, 0))));

        assertThat(slotStarts(solo.masterId(), day, soloSvc))
                .as("a single declared time is bookable — its derived window is a zero-length point")
                .containsExactly(LocalTime.of(13, 0));
    }

    /**
     * <b>Scope note (mutation-tested 2026-08-11).</b> This is an AGREEMENT pin, not a fix-regression pin:
     * with the {@code isExplicitTimes} switch reverted it stays GREEN, because a lone declared time
     * projects a degenerate {@code [13:00..13:00]} derived window from which the interval walk also
     * yields nothing. That is fine for what it asserts — day gate ⇔ slot list on a negative day — but do
     * not read it as protecting the declared-times routing. Cases 7b/7d/7f do that, and all three go red
     * against that revert.
     */
    @Test
    @DisplayName("case 7c — day-gate agreement on an EXPLICIT_TIMES day whose ONLY declared time is "
            + "already booked: working=FALSE and /slots EMPTY (both agree)")
    void should_agreeDayUnavailable_when_theOnlyDeclaredTimeIsTaken() {
        LocalDate day = TODAY.plusDays(12);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        // A single declared time — the case whose derived window is DEGENERATE (min == max), which the
        // interval walk rejected outright, so such a day could never be booked at all.
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null, List.of(LocalTime.of(13, 0))));

        // NB: no read before the booking is inserted — the first slotStarts call would populate the
        // `available-slots` cache, and a raw JDBC insert evicts nothing. The "a lone declared time is
        // bookable" half of this contract is asserted in case 7b on its own master.
        insertBooking(m.masterId(), svc, seedClient(), day, LocalTime.of(13, 0), LocalTime.of(14, 0), "CONFIRMED");

        assertThat(slotStarts(m.masterId(), day, svc))
                .as("/slots — the only declared time is taken, so nothing is offered")
                .isEmpty();
        assertThat(bookableDay(m.masterId(), day, svc))
                .as("working-days MUST agree: a day whose declared times all fail the filters is "
                        + "non-working — the day gate and the slot list share one predicate")
                .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 7f — PARTIALLY booked EXPLICIT_TIMES day: the survivors are still DECLARED times
    // ════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The positive half of the day-gate/slot-list agreement on an EXPLICIT_TIMES day, and the case
     * case 7c cannot pin. 7c seeds a day with ONE declared time and books it, then asserts empty +
     * {@code working = false} — a verdict the PRE-FIX interval walk also returns, because a lone
     * declared time projects a degenerate {@code [13:00..13:00]} window that yields nothing either way.
     * 7c is a sound agreement pin, but it is green against the bug and cannot detect a regression of it.
     *
     * <p>Here two times are declared and only the first is booked. The fix offers the remaining DECLARED
     * time (15:00); the interval walk offers 14:00 — a start the master never declared, produced by
     * striding the derived window across the freed grid. Same day, same booking, opposite answers, so
     * the assertion discriminates.
     */
    @Test
    @DisplayName("case 7f — booking ONE of two declared times leaves the OTHER declared time (15:00) on "
            + "offer, never a grid start (14:00) the master never declared")
    void should_offerTheRemainingDeclaredTime_when_oneOfTwoDeclaredTimesIsBooked() {
        LocalDate day = TODAY.plusDays(15);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null,
                        List.of(LocalTime.of(13, 0), LocalTime.of(15, 0))));
        // NB: seeded BEFORE any read — a raw JDBC insert evicts no `available-slots` cache entry, so a
        // read here would poison the assertions below (same trap case 7c documents).
        insertBooking(m.masterId(), svc, seedClient(), day,
                LocalTime.of(13, 0), LocalTime.of(14, 0), "CONFIRMED");

        assertThat(slotStarts(m.masterId(), day, svc))
                .as("13:00 is taken; 15:00 is the only other DECLARED time and it survives. 14:00 is "
                        + "what the derived-window grid would fabricate once 13:00–14:00 frees up — it "
                        + "must not appear, because the master never offered it.")
                .containsExactly(LocalTime.of(15, 0));
        assertThat(bookableDay(m.masterId(), day, svc))
                .as("working-days agrees — a declared time still survives every filter")
                .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 7g/7h/7i — EXPLICIT_TIMES: a booking that RUNS THROUGH a later declared time eats it
    // ════════════════════════════════════════════════════════════════════════════════════════
    //
    // An EXPLICIT_TIMES day emits exactly the declared times, and its ONLY end bound is the same Kyiv
    // civil day — there is deliberately no rule that a service must finish before the next declared time
    // (locked 2026-08-11: an unreachable declared time is the master's own responsibility, and there is no
    // warning at declaration time by product decision). So a master who declares 10:00 and 11:00 and then
    // sells a 4-hour service at 10:00 has, without being told, closed the whole day.
    //
    // Case 7f already books ONE of two declared times, but with a 60-min service against declared
    // 13:00/15:00 — the booking ends at 14:00 and touches nothing. These three cases are the first
    // anywhere to seed a booking whose half-open [starts_at, ends_at) span strictly CONTAINS a later
    // declared time, which is the interior branch of the declared walk's overlap test.

    @Test
    @DisplayName("case 7g — a 4-hour booking at the 10:00 declared time swallows the 11:00 one: /slots "
            + "EMPTY and working=FALSE, for a 30-min request as well as a 4-hour one")
    void should_agreeDayUnavailable_when_aLongBookingSwallowsTheOtherDeclaredTime() {
        LocalDate day = TODAY.plusDays(16);

        Master m = seedIndependentMaster();
        UUID svc4h = addService(m, 240, 0);
        UUID svc30 = addService(m, 30, 0);
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null,
                        List.of(LocalTime.of(10, 0), LocalTime.of(11, 0))));
        // Seeded BEFORE any read — a raw JDBC insert evicts no `available-slots` entry (see case 7c).
        insertBooking(m.masterId(), svc4h, seedClient(), day,
                LocalTime.of(10, 0), LocalTime.of(14, 0), "CONFIRMED");

        // A 30-min request is the strongest form of the assertion: the NEXT client's own duration cannot
        // rescue a start that already lies inside an occupied range, because a candidate's end is always
        // after its start, which is already after the booking's start. A day gate that tested only "does
        // a booking START on this declared time?" would still offer 11:00 here.
        assertThat(slotStarts(m.masterId(), day, svc30))
                .as("11:00 is strictly inside the booked [10:00,14:00) — a 30-min request does not fit "
                        + "'between' anything, because the declared walk offers declared times only")
                .isEmpty();
        assertThat(bookableDay(m.masterId(), day, svc30))
                .as("working-days MUST agree — both declared times are consumed")
                .isFalse();

        assertThat(slotStarts(m.masterId(), day, svc4h))
                .as("the same verdict for the 4-hour service the booking itself used")
                .isEmpty();
        assertThat(bookableDay(m.masterId(), day, svc4h))
                .as("agreement invariant on the second service too")
                .isFalse();
    }

    @Test
    @DisplayName("case 7h — THRESHOLD: a 60-min booking at 10:00 ends exactly on the 11:00 declared time "
            + "(still offered, day TRUE); a 61-min one overruns it (empty, day FALSE)")
    void should_gateTheLaterDeclaredTimeOnExactOverrun_atTheOneMinuteBoundary() {
        LocalDate fits = TODAY.plusDays(17);
        LocalDate overruns = TODAY.plusDays(18);

        Master m = seedIndependentMaster();
        UUID svc60 = addService(m, 60, 0);   // what the NEXT client asks for, on both days
        UUID svc61 = addService(m, 61, 0);   // the one-minute-longer service booked on the second day
        UUID client = seedClient();
        for (LocalDate day : List.of(fits, overruns)) {
            masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                    new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                            WeekdayMode.EXPLICIT_TIMES, null,
                            List.of(LocalTime.of(10, 0), LocalTime.of(11, 0))));
        }
        insertBooking(m.masterId(), svc60, client, fits,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "CONFIRMED");
        insertBooking(m.masterId(), svc61, client, overruns,
                LocalTime.of(10, 0), LocalTime.of(11, 1), "CONFIRMED");

        assertThat(slotStarts(m.masterId(), fits, svc60))
                .as("the booking ends exactly where the 11:00 declared time starts; the overlap test is "
                        + "strict on both sides, so 11:00 survives (10:00 itself is taken)")
                .containsExactly(LocalTime.of(11, 0));
        assertThat(bookableDay(m.masterId(), fits, svc60))
                .as("working-days agrees — one declared time is still reachable")
                .isTrue();

        assertThat(slotStarts(m.masterId(), overruns, svc60))
                .as("ONE minute of overrun puts 11:00 strictly inside [10:00,11:01) and kills the whole "
                        + "day. This pair is what separates a strict overlap test from an inclusive one.")
                .isEmpty();
        assertThat(bookableDay(m.masterId(), overruns, svc60))
                .as("working-days agrees — nothing left to reach")
                .isFalse();
    }

    @Test
    @DisplayName("case 7i — bufferMinutesAfter counts toward the overrun: a 60-min service with a 10-min "
            + "buffer booked at 10:00 ends 11:10 and consumes the 11:00 declared time")
    void should_countBufferTowardTheOverrun_when_aDeclaredTimeFollowsABufferedBooking() {
        LocalDate day = TODAY.plusDays(19);

        Master m = seedIndependentMaster();
        UUID svcPlain = addService(m, 60, 0);        // what the next client asks for
        UUID svcBuffered = addService(m, 60, 10);    // 60 min of work + a 10-min buffer after it
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null,
                        List.of(LocalTime.of(10, 0), LocalTime.of(11, 0))));
        // Persisted exactly as BookingService does it: ends_at = starts_at + duration + buffer.
        insertBufferedBooking(m.masterId(), svcBuffered, seedClient(), day, LocalTime.of(10, 0), 60, 10);

        assertThat(slotStarts(m.masterId(), day, svcPlain))
                .as("the SAME 60-min service that leaves 11:00 bookable with no buffer (case 7h) consumes "
                        + "it once its 10-min buffer is folded into ends_at — 11:10 > 11:00")
                .isEmpty();
        assertThat(bookableDay(m.masterId(), day, svcPlain))
                .as("working-days agrees — the buffer closed the day")
                .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 7d/7e — EXPLICIT_TIMES END BOUND: a declared time must FINISH inside its own Kyiv day
    // ════════════════════════════════════════════════════════════════════════════════════════
    //
    // The end bound is the one filter with no counterpart on an interval day: an interval carries a real
    // endTime, an EXPLICIT_TIMES day does not, so the walk falls back to "ends by this date's Kyiv
    // midnight". It is asserted at the unit tier (TimeSlotCalculatorTest, incl. both DST days), but the
    // unit tier cannot see whether SlotCalculationService actually ROUTES an explicit-times day here —
    // the same day also carries a DERIVED [min..max] window that the interval walk would happily consume
    // and answer differently.
    //
    // What each case actually pins, end to end on real Postgres (mutation-tested 2026-08-11):
    //   * 7d pins the ROUTING **and** the bound. Its two declared times project a real [23:00..23:30]
    //     window that the reverted interval walk consumes differently, so 7d goes RED against the
    //     `isExplicitTimes` revert; the withheld 23:30 is the end bound itself.
    //   * 7e pins the END BOUND ONLY — on BOTH endpoints, which is its job. Its lone declared time
    //     projects a DEGENERATE [23:30..23:30] window from which the interval walk also yields nothing,
    //     so 7e stays GREEN against that revert and does NOT discriminate the routing switch. Same
    //     limitation, same cause, as case 7c's scope note above records for itself. It keeps its place
    //     as the negative-day agreement pin for the bound: working=FALSE and /slots EMPTY must agree
    //     when the only declared time cannot finish inside its own civil day.
    // Routing regressions are covered by cases 7b/7d/7f — all three go red against that revert.

    @Test
    @DisplayName("case 7d — an EXPLICIT_TIMES day declaring 23:00 and 23:30 offers only 23:00 for a "
            + "60-min service: 23:30 cannot finish inside its own Kyiv day")
    void should_withholdDeclaredTimeThatCannotFinishInsideTheKyivDay() {
        LocalDate day = TODAY.plusDays(13);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null,
                        List.of(LocalTime.of(23, 0), LocalTime.of(23, 30))));

        assertThat(slotStarts(m.masterId(), day, svc))
                .as("23:00 + 60 min ends exactly at midnight and is kept; 23:30 would run into the next "
                        + "civil day and is withheld. The schedule model forbids cross-midnight ranges, "
                        + "so this is the strictest bound the domain asserts.")
                .containsExactly(LocalTime.of(23, 0));
        assertThat(bookableDay(m.masterId(), day, svc))
                .as("working-days agrees — one declared time survives every filter")
                .isTrue();
    }

    @Test
    @DisplayName("case 7e — an EXPLICIT_TIMES day whose ONLY declared time cannot finish inside the Kyiv "
            + "day: working=FALSE and /slots EMPTY (both agree)")
    void should_agreeDayUnavailable_when_theOnlyDeclaredTimeOverrunsTheKyivDay() {
        LocalDate day = TODAY.plusDays(14);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null, List.of(LocalTime.of(23, 30))));

        assertThat(slotStarts(m.masterId(), day, svc))
                .as("/slots — the single declared time cannot fit a 60-min service before midnight")
                .isEmpty();
        assertThat(bookableDay(m.masterId(), day, svc))
                .as("working-days MUST agree: hasDeclaredSlot applies the SAME day-end bound the slot "
                        + "list does, so the day gate cannot advertise a day that yields nothing")
                .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 8 — DAY_OFF override closes the date in the availability mode too (day FALSE, empty)
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 8 — a DAY_OFF override over a templated future day → serviceId-mode day FALSE and /slots EMPTY")
    void should_closeDay_when_dayOffOverridesTemplate() {
        LocalDate day = TODAY.plusDays(8);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        // A template that WOULD make the day working…
        seedInterval(m.masterId(), day, day, day.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
        // …then a DAY_OFF override that closes it.
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.DAY_OFF, null));

        assertThat(bookableDay(m.masterId(), day, svc))
                .as("a DAY_OFF override beats the template → the availability-aware day is non-working")
                .isFalse();
        assertThat(slotStarts(m.masterId(), day, svc))
                .as("/slots agrees — a closed day offers nothing")
                .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 9 — inactive MASTER: every day reports FALSE (not an error), through the real pipeline
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 9 — a deactivated master reports serviceId-mode day FALSE and /slots EMPTY for a would-be working day (not a 404)")
    void should_reportAllDaysFalse_when_masterIsInactive() {
        LocalDate day = TODAY.plusDays(7);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        seedInterval(m.masterId(), day, day, day.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
        // Deactivate the master out-of-band (deactivateOwnerMaster leaves master_services intact).
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", m.masterId());

        assertThat(bookableDay(m.masterId(), day, svc))
                .as("an inactive master exposes no bookable days — false, never an exception")
                .isFalse();
        assertThat(slotStarts(m.masterId(), day, svc))
                .as("/slots agrees — an inactive master offers no slots")
                .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 10 — the two endpoints agree across a Europe/Kyiv DST fall-back date within the horizon
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("case 10 — on the 2026-10-25 Kyiv DST fall-back day the serviceId-mode day matches /slots (projection ⇔ slots across the transition)")
    void should_agreeAcrossDstFallBackDay() {
        // 2026-10-25 is the Kyiv autumn fall-back (25-hour civil day) and lies inside [today, today+180].
        LocalDate dstDay = LocalDate.of(2026, 10, 25);
        assertThat(dstDay).as("guard: the DST day is within the 180-day horizon from the frozen today")
                .isAfterOrEqualTo(TODAY).isBeforeOrEqualTo(TODAY.plusDays(180));

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        seedInterval(m.masterId(), dstDay, dstDay, dstDay.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));

        boolean working = bookableDay(m.masterId(), dstDay, svc);
        List<LocalTime> slots = slotStarts(m.masterId(), dstDay, svc);

        assertThat(working)
                .as("a full daytime schedule on the DST-transition day is bookable on both endpoints")
                .isTrue();
        assertThat(slots).as("/slots offers the day's starts").isNotEmpty();
        assertThat(working)
                .as("agreement invariant holds across the DST boundary: working ⇔ ≥1 bookable slot")
                .isEqualTo(!slots.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Case 11/12 — CACHE: the consumed declared time must disappear for EVERY service, at once
    // ════════════════════════════════════════════════════════════════════════════════════════
    //
    // Cases 7g/7h/7i prove the CALCULATION withholds a consumed declared time. They seed their
    // bookings with raw JDBC and read `/slots` once, so they never touch the `available-slots`
    // cache — a correct calculator behind a stale cache still ships the bug the user reported.
    //
    // These two cases are the only ones anywhere that drive a REAL booking write (BookingService,
    // real transaction, real afterCommit hook) and then re-read `/slots` through the REAL Caffeine
    // cache with NO sleep and NO TTL wait. What they pin:
    //
    //   * case 11 — CROSS-SERVICE eviction. `available-slots` is keyed {masterId, date,
    //     masterServiceId}, so booking service A leaves service B's entry untouched unless the
    //     write sweeps the whole master prefix. Before the fix the write evicted only the BOOKED
    //     service's key, so for up to the 60-second TTL a second client browsing service B was
    //     still offered the time service A had just consumed.
    //   * case 12 — eviction on CONFIRMED -> NOT_COMPLETED, a transition that FREES a slot (the
    //     occupancy predicate is `status = 'CONFIRMED'` only) and evicted nothing at all before
    //     the fix. No-show carries no temporal guard, so the booking here is deliberately FUTURE:
    //     its slot must return to the picker immediately.
    //
    // Both assert the OUTCOME (which wall-clocks `/slots` offers) and, as the mechanism guard, that
    // the warm read really did populate the cache — without that a green could mean "nothing was
    // ever cached" rather than "the eviction reached it".

    @Test
    @DisplayName("case 11 — a booking on service A that runs through the 12:00 declared time removes "
            + "12:00 from service B's ALREADY-CACHED slot list on the very next read (no TTL wait)")
    void should_evictASecondServicesCachedSlots_when_aBookingOnServiceAConsumesADeclaredTime() {
        LocalDate day = TODAY.plusDays(20);

        Master m = seedIndependentMaster();
        UUID svcA = addService(m, 120, 0);  // booked at 11:00 → runs to 13:00, swallowing 12:00
        UUID svcB = addService(m, 30, 0);   // the OTHER service, whose cached list must move too
        UUID client = seedClient();
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null,
                        List.of(LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0))));

        // 1) Warm service B's cache entry — this is the list a second client is holding.
        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("an unbooked EXPLICIT_TIMES day offers all three declared times to service B")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — the read above really was cached, so a STALE second read is "
                        + "possible and this test cannot pass vacuously")
                .isEqualTo(1);

        // 2) A REAL booking on service A, through BookingService's own transaction, so the
        //    afterCommit eviction hook fires exactly as it does in production.
        bookingService.createBooking(client, null, new CreateBookingRequest(
                m.masterId(), svcA, day.atTime(11, 0).atZone(TimeZones.KYIV), null, null));

        // 3) The outcome the user asked for — immediately, with no sleep and no TTL expiry.
        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("12:00 is strictly inside the booked [11:00,13:00) and 11:00 is its start, so both "
                        + "vanish from service B at once; 15:00 is untouched, which proves this is a "
                        + "recomputation and not a blanket cache wipe or an empty-list failure")
                .containsExactly(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("case 12 — marking a FUTURE booking NOT_COMPLETED returns its 11:00 slot to the "
            + "already-cached slot list on the very next read (no TTL wait)")
    void should_restoreTheFreedSlotImmediately_when_aFutureBookingIsMarkedNotCompleted() {
        LocalDate day = TODAY.plusDays(21);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        UUID client = seedClient();
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null,
                        List.of(LocalTime.of(11, 0), LocalTime.of(15, 0))));

        UUID bookingId = bookingService.createBooking(client, null, new CreateBookingRequest(
                m.masterId(), svc, day.atTime(11, 0).atZone(TimeZones.KYIV), null, null)).id();
        // Isolation, so this case fails for exactly ONE reason. The create above ran the schedule-fit
        // gate, which populated this master's `available-slots` key with the PRE-booking list; clearing
        // here means the warm read below is unambiguously fresh and case 12 pins the no-show eviction
        // alone, never the create-path eviction that case 11 owns.
        clearSlotCache();

        // 1) Warm the cache with the POST-booking picture: 11:00 is taken.
        assertThat(slotStarts(m.masterId(), day, svc))
                .as("the CONFIRMED booking occupies 11:00")
                .containsExactly(LocalTime.of(15, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — the taken-slot list above is genuinely cached, so failing to "
                        + "evict below would serve it again")
                .isEqualTo(1);

        // 2) CONFIRMED -> NOT_COMPLETED drops the row out of the `status = 'CONFIRMED'` occupancy
        //    predicate, i.e. it FREES 11:00. The booking is still in the future — no-show has no
        //    temporal guard — so the slot is genuinely re-bookable.
        bookingService.notCompleteBooking(m.userId(), bookingId,
                new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, null));

        assertThat(slotStarts(m.masterId(), day, svc))
                .as("11:00 is bookable again on the very next read — not 60 seconds later")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("case 13 — completing an IN-PROGRESS booking early returns its unused 17:00–19:00 tail "
            + "to the already-cached slot list on the very next read (no TTL wait)")
    void should_returnTheUnusedTailImmediately_when_anInProgressBookingIsCompletedEarly() {
        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        UUID client = seedClient();
        seedInterval(m.masterId(), TODAY, TODAY, TODAY.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(20, 0));
        // Started at 17:00, "now" is 17:20, ends 19:00 — genuinely IN PROGRESS. Raw JDBC because no
        // create path can produce an already-started booking. assertElapsedForComplete admits it
        // (it tests `now >= startsAt`, never `now >= endsAt`), so the provider may close it early.
        UUID bookingId = insertBookingReturningId(m.masterId(), svc, client, TODAY,
                LocalTime.of(17, 0), LocalTime.of(19, 0));

        // 1) Warm the cache: everything up to 19:00 is occupied, and the 17:35 cutoff has eaten the
        //    rest of the pre-19:00 grid, so 19:00 is the only start a 60-min service can take.
        assertThat(slotStarts(m.masterId(), TODAY, svc))
                .as("the in-progress booking occupies the calendar until 19:00")
                .containsExactly(LocalTime.of(19, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — that list is genuinely cached, so failing to evict below would "
                        + "serve it again")
                .isEqualTo(1);

        // 2) Complete it early. CONFIRMED -> COMPLETED leaves the `status = 'CONFIRMED'` occupancy
        //    predicate, so the unused 17:20–19:00 tail becomes bookable again.
        bookingService.completeBooking(m.userId(), bookingId);

        assertThat(slotStarts(m.masterId(), TODAY, svc))
                .as("the freed tail reappears on the very next read — 17:30 stays out because it is "
                        + "below the 17:35 cutoff, which proves the list was RECOMPUTED against the "
                        + "live clock rather than merely widened")
                .containsExactly(LocalTime.of(18, 0), LocalTime.of(18, 30), LocalTime.of(19, 0));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Cases 14–18 — CACHE: the SAME cross-service proof for every remaining occupancy-freeing
    // transition, and for the two non-BookingService writers into the same sweep
    // ════════════════════════════════════════════════════════════════════════════════════════
    //
    // Cases 11–13 pinned create / not-complete / complete. Cancel, decline, reschedule and the
    // appointment-level + guest analogues route through the identical
    // SlotCalculationService#evictMasterAvailabilityCaches by-master sweep, but were covered only
    // by Mockito verifies — which prove the CALL is written, never that the sweep actually reaches
    // a live Caffeine entry keyed on a DIFFERENT service than the one written. These five close
    // that asymmetry:
    //
    //   * case 14 — cancel   (BookingService#cancelBooking,     client-initiated → CANCELLED)
    //   * case 15 — decline  (BookingService#declineBooking,    provider-initiated → DECLINED)
    //   * case 16 — reschedule (BookingService#rescheduleBooking) — a MOVE, so BOTH halves are
    //     asserted off ONE post-write read: the vacated time returns AND the target time goes.
    //   * case 17 — whole-visit cancel (AppointmentTransitionService#cancelAppointment) — the
    //     appointment-level analogue, whose eviction lives in its OWN registerEviction hook.
    //   * case 18 — guest cancel-by-token (BookingCancellationService#cancel) — the third writer,
    //     whose eviction lives inside registerAfterCommitSms, downstream of the SMS attempt.
    //
    // Every case follows cases 11–13's shape exactly: warm a SECOND service's slot list, guard
    // that the warm read genuinely populated the cache, drive the REAL service-level transition,
    // then re-read IMMEDIATELY — no sleep, no TTL wait. Each expected list keeps at least one
    // declared time the transition never touched, so an empty-list/blanket-failure cannot pass.

    @Test
    @DisplayName("case 14 — a CLIENT cancel of the service-A booking returns 11:00 and 12:00 to "
            + "service B's ALREADY-CACHED slot list on the very next read (no TTL wait)")
    void should_evictASecondServicesCachedSlots_when_theClientCancelsTheBooking() {
        LocalDate day = TODAY.plusDays(22);

        Master m = seedIndependentMaster();
        UUID svcA = addService(m, 120, 0);  // booked 11:00 → runs to 13:00, swallowing 12:00
        UUID svcB = addService(m, 30, 0);   // the OTHER service, whose cached list must move too
        UUID client = seedClient();
        seedExplicitTimesDay(m, day, LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));

        UUID bookingId = bookingService.createBooking(client, null, new CreateBookingRequest(
                m.masterId(), svcA, day.atTime(11, 0).atZone(TimeZones.KYIV), null, null)).id();
        // Isolation (case 12's reasoning verbatim): the create above already swept this master's
        // keys, so clearing here makes the warm read below unambiguously fresh and this case pin
        // the CANCEL eviction alone, never the create-path eviction case 11 owns.
        clearSlotCache();

        // 1) Warm service B's entry with the POST-booking picture a second client would hold.
        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("the CONFIRMED 11:00–13:00 booking on service A hides 11:00 and 12:00 from service B")
                .containsExactly(LocalTime.of(15, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — service B's taken-slot list is genuinely cached, so failing "
                        + "to evict below would serve it again")
                .isEqualTo(1);

        // 2) CONFIRMED -> CANCELLED leaves the `status = 'CONFIRMED'` occupancy predicate, i.e. it
        //    FREES the master's 11:00–13:00 block for every service he performs.
        bookingService.cancelBooking(client, bookingId,
                new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null));

        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("both freed declared times are bookable on service B on the very next read — not "
                        + "60 seconds later; 15:00 was never consumed and is still offered, which "
                        + "proves a recomputation rather than an empty-list failure")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("case 15 — a PROVIDER decline of the service-A booking returns 11:00 and 12:00 to "
            + "service B's ALREADY-CACHED slot list on the very next read (no TTL wait)")
    void should_evictASecondServicesCachedSlots_when_theProviderDeclinesTheBooking() {
        LocalDate day = TODAY.plusDays(23);

        Master m = seedIndependentMaster();
        UUID svcA = addService(m, 120, 0);
        UUID svcB = addService(m, 30, 0);
        UUID client = seedClient();
        seedExplicitTimesDay(m, day, LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));

        UUID bookingId = bookingService.createBooking(client, null, new CreateBookingRequest(
                m.masterId(), svcA, day.atTime(11, 0).atZone(TimeZones.KYIV), null, null)).id();
        clearSlotCache();

        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("the CONFIRMED 11:00–13:00 booking on service A hides 11:00 and 12:00 from service B")
                .containsExactly(LocalTime.of(15, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — service B's taken-slot list is genuinely cached, so failing "
                        + "to evict below would serve it again")
                .isEqualTo(1);

        // CONFIRMED -> DECLINED, the provider-initiated sibling of case 14's client cancel: a
        // different status and a different actor, but the same freed occupancy.
        bookingService.declineBooking(m.userId(), bookingId,
                new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));

        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("the declined block is bookable on service B on the very next read; 15:00 "
                        + "survives, which proves a recomputation rather than an empty-list failure")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("case 16 — rescheduling the service-A booking 11:00 → 13:00 both RETURNS 11:00 and "
            + "REMOVES 13:00 from service B's ALREADY-CACHED slot list, on one single next read")
    void should_moveBothSidesOfASecondServicesCachedSlots_when_theBookingIsRescheduled() {
        LocalDate day = TODAY.plusDays(24);

        Master m = seedIndependentMaster();
        UUID svcA = addService(m, 60, 0);   // 11:00 → 12:00, moved to 13:00 → 14:00
        UUID svcB = addService(m, 30, 0);
        UUID client = seedClient();
        seedExplicitTimesDay(m, day, LocalTime.of(11, 0), LocalTime.of(13, 0), LocalTime.of(15, 0));

        UUID bookingId = bookingService.createBooking(client, null, new CreateBookingRequest(
                m.masterId(), svcA, day.atTime(11, 0).atZone(TimeZones.KYIV), null, null)).id();
        clearSlotCache();

        // 1) Warm service B's entry with the pre-move picture: 11:00 taken, 13:00 and 15:00 free.
        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("before the move service A occupies 11:00 only")
                .containsExactly(LocalTime.of(13, 0), LocalTime.of(15, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — the pre-move list is genuinely cached, so a half-eviction "
                        + "(old date only, or new date only) would be observable below")
                .isEqualTo(1);

        // 2) The MOVE. One by-master sweep has to cover BOTH the vacated 11:00 and the newly
        //    occupied 13:00 — a per-key eviction that forgot either side fails exactly one half of
        //    the single assertion below.
        bookingService.rescheduleBooking(client, bookingId, new RescheduleBookingRequest(
                day.atTime(13, 0).atZone(TimeZones.KYIV).toOffsetDateTime()));

        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("ONE post-move read shows both halves at once: 11:00 came back (old side "
                        + "freed) and 13:00 went away (new side occupied); 15:00 is untouched, so "
                        + "this is a recomputation and not a blanket wipe")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("case 17 — cancelling a whole multi-service VISIT returns both its 11:00 and 12:00 "
            + "legs to a third service's ALREADY-CACHED slot list on the very next read")
    void should_evictAThirdServicesCachedSlots_when_aWholeAppointmentIsCancelled() {
        LocalDate day = TODAY.plusDays(25);

        Master m = seedIndependentMaster();
        UUID svcA = addService(m, 60, 0);   // visit leg 1 — 11:00 → 12:00
        UUID svcB = addService(m, 60, 0);   // visit leg 2 — 12:00 → 13:00
        UUID svcC = addService(m, 30, 0);   // the OBSERVER service, booked by nobody
        UUID client = seedClient();
        seedExplicitTimesDay(m, day, LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));

        // Raw JDBC, matching this suite's fixture convention: the visit must already exist before
        // the warm read, and seeding it directly keeps this case pinned to the CANCEL eviction
        // alone rather than to AppointmentService's create-path eviction.
        UUID appointmentId = insertConfirmedAppointment(client);
        insertAppointmentItem(appointmentId, m.masterId(), svcA, client, day,
                LocalTime.of(11, 0), LocalTime.of(12, 0));
        insertAppointmentItem(appointmentId, m.masterId(), svcB, client, day,
                LocalTime.of(12, 0), LocalTime.of(13, 0));

        // 1) Warm the THIRD service's entry — neither leg's own service, so only a by-master sweep
        //    can reach it.
        assertThat(slotStarts(m.masterId(), day, svcC))
                .as("the two CONFIRMED visit legs occupy 11:00–13:00, hiding both from service C")
                .containsExactly(LocalTime.of(15, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — service C's taken-slot list is genuinely cached, so failing "
                        + "to evict below would serve it again")
                .isEqualTo(1);

        // 2) The whole-visit client cancel — header + every item to CANCELLED, evicting through
        //    AppointmentTransitionService's own after-commit hook, not BookingService's.
        appointmentTransitionService.cancelAppointment(client, appointmentId,
                new AppointmentCancelRequest(null));

        assertThat(slotStarts(m.masterId(), day, svcC))
                .as("both cancelled legs are bookable on service C on the very next read; 15:00 "
                        + "survives, which proves a recomputation rather than an empty-list failure")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("case 18 — a GUEST cancelling by one-time token returns the 11:00 slot to a second "
            + "service's ALREADY-CACHED slot list on the very next read (no TTL wait)")
    void should_evictASecondServicesCachedSlots_when_aGuestCancelsByToken() {
        LocalDate day = TODAY.plusDays(26);

        Master m = seedIndependentMaster();
        UUID svcA = addService(m, 60, 0);   // the guest's booking — 11:00 → 12:00
        UUID svcB = addService(m, 30, 0);   // the OTHER service, whose cached list must move too
        seedExplicitTimesDay(m, day, LocalTime.of(11, 0), LocalTime.of(15, 0));

        // A LINK booking carrying a live cancel_token (V91's chk_bookings_guest_fields shape). The
        // day is 26 days out, far beyond the 2-hour cancellation window, so `isCancellable` admits it.
        UUID cancelToken = insertGuestBookingWithCancelToken(m.masterId(), svcA, day,
                LocalTime.of(11, 0), LocalTime.of(12, 0));

        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("the guest's CONFIRMED 11:00 booking hides 11:00 from service B")
                .containsExactly(LocalTime.of(15, 0));
        assertThat(cachedSlotEntryCount(m.masterId()))
                .as("mechanism guard — service B's taken-slot list is genuinely cached, so failing "
                        + "to evict below would serve it again")
                .isEqualTo(1);

        // The public guest cancel: token consumed atomically, then the after-commit hook fires the
        // SMS and the SAME by-master availability sweep. No SMS provider is configured under the
        // test profile, so the send throws and is swallowed by design — the eviction must still run.
        bookingCancellationService.cancel(cancelToken);

        assertThat(slotStarts(m.masterId(), day, svcB))
                .as("the guest-freed 11:00 is bookable on service B on the very next read; 15:00 "
                        + "survives, which proves a recomputation rather than an empty-list failure")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(15, 0));
    }

    /**
     * Live entries of the {@code available-slots} Caffeine cache whose key's FIRST element is this
     * master — the exact predicate {@link com.beautica.common.cache.MasterCachePrefixEvictor} sweeps.
     * Read-only: it never mutates the cache, so it cannot influence the behavioural assertions it sits
     * beside.
     */
    private long cachedSlotEntryCount(UUID masterId) {
        var caffeine = (com.github.benmanes.caffeine.cache.Cache<?, ?>) slotCache().getNativeCache();
        return caffeine.asMap().keySet().stream()
                .filter(k -> k instanceof List<?> parts && !parts.isEmpty() && masterId.equals(parts.get(0)))
                .count();
    }

    /** Test-fixture reset of the slot cache — see its one caller (case 12) for why it is needed. */
    private void clearSlotCache() {
        slotCache().clear();
    }

    private Cache slotCache() {
        Cache springCache = cacheManager.getCache("available-slots");
        assertThat(springCache).as("the available-slots cache must be registered in the test context")
                .isNotNull();
        return springCache;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Fixtures (raw JDBC — pins exact dates/intervals/bookings the write path would reshape)
    // ════════════════════════════════════════════════════════════════════════════════════════

    private record Master(UUID masterId, UUID userId) {
    }

    private Master seedIndependentMaster() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', 'Ind', "
                        + "'Master', true, true)",
                userId, "ind-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return new Master(masterId, userId);
    }

    /** An active service definition + active master_services assignment; returns the masterServiceId. */
    private UUID addService(Master m, int durationMinutes, int bufferMinutes) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, "
                        + "service_type_id, base_duration_minutes, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) VALUES (?, 'INDEPENDENT_MASTER', ?, 'Svc', ?, "
                        + "?, ?, ?, true, NOW(), NOW())",
                serviceDefId, m.userId(), resolveUnusedIndieServiceTypeId(m.userId()), durationMinutes,
                PRICE, bufferMinutes);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, m.masterId(), serviceDefId);
        return masterServiceId;
    }

    private void seedInterval(UUID masterId, LocalDate validFrom, LocalDate validTo,
                              int isoDow, LocalTime start, LocalTime end) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        jdbcTemplate.update("INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, "
                        + "end_time) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    /**
     * An {@code EXPLICIT_TIMES} schedule override declaring exactly {@code times} on {@code day} —
     * the shape cases 14–18 all need (a day whose offered starts are enumerable to the minute, so
     * an expected slot list can be written literally). Extracted for the new cases only; cases
     * 11/12 keep their inline {@code upsertOverride} so their assertions stay byte-for-byte as
     * originally reviewed.
     */
    private void seedExplicitTimesDay(Master m, LocalDate day, LocalTime... times) {
        masterScheduleService.upsertOverride(m.userId(), m.masterId(),
                new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.EXPLICIT_TIMES, null, List.of(times)));
    }

    /** A CONFIRMED, APP-sourced multi-service visit header (case 17). */
    private UUID insertConfirmedAppointment(UUID clientId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, status, booking_source, "
                + "created_at, updated_at) VALUES (?, ?, 'CONFIRMED', 'APP', NOW(), NOW())", id, clientId);
        return id;
    }

    /**
     * One CONFIRMED leg of a visit — an ordinary booking row additionally pointing at the
     * appointment header (V125's {@code bookings.appointment_id}), which is what makes
     * {@code AppointmentTransitionService} treat it as a child rather than a standalone booking.
     */
    private void insertAppointmentItem(UUID appointmentId, UUID masterId, UUID masterServiceId,
                                       UUID clientId, LocalDate date, LocalTime start, LocalTime end) {
        UUID id = UUID.randomUUID();
        insertBooking(masterId, masterServiceId, clientId, date, start, end, "CONFIRMED", id);
        jdbcTemplate.update("UPDATE bookings SET appointment_id = ? WHERE id = ?", appointmentId, id);
    }

    /**
     * A guest (LINK) booking with a live one-time {@code cancel_token}, satisfying V91's
     * {@code chk_bookings_guest_fields} LINK branch (client_id NULL, guest name + E.164 phone
     * present, token non-null while CONFIRMED). Returns the token case 18 then presents to
     * {@code BookingCancellationService.cancel}.
     */
    private UUID insertGuestBookingWithCancelToken(UUID masterId, UUID masterServiceId,
                                                   LocalDate date, LocalTime start, LocalTime end) {
        OffsetDateTime startsAt = date.atTime(start).atZone(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime endsAt = date.atTime(end).atZone(TimeZones.KYIV).toOffsetDateTime();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        UUID cancelToken = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, "
                        + "status, starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, guest_name, guest_phone, "
                        + "cancel_token, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, 'CONFIRMED', ?, ?, ?, ?, 0, 'LINK', 'Guest', "
                        + "'+380501234567', ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, masterServiceId, startsAt, endsAt, PRICE, minutes,
                cancelToken);
        return cancelToken;
    }

    /** A CLIENT user to satisfy the APP-booking CHECK (client_id NOT NULL, guest fields NULL). */
    private UUID seedClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'CLIENT', 'Cli', 'Ent', true, true)",
                id, "cli-" + id + "@beautica.test");
        return id;
    }

    /** An APP booking occupying {@code [start, end)} Kyiv-civil time on {@code date} in the given status. */
    private void insertBooking(UUID masterId, UUID masterServiceId, UUID clientId, LocalDate date,
                               LocalTime start, LocalTime end, String status) {
        insertBooking(masterId, masterServiceId, clientId, date, start, end, status, UUID.randomUUID());
    }

    /**
     * As {@link #insertBooking}, but hands back the id — case 13 has to name the row it then drives
     * through {@code BookingService.completeBooking}.
     */
    private UUID insertBookingReturningId(UUID masterId, UUID masterServiceId, UUID clientId,
                                          LocalDate date, LocalTime start, LocalTime end) {
        UUID id = UUID.randomUUID();
        insertBooking(masterId, masterServiceId, clientId, date, start, end, "CONFIRMED", id);
        return id;
    }

    private void insertBooking(UUID masterId, UUID masterServiceId, UUID clientId, LocalDate date,
                               LocalTime start, LocalTime end, String status, UUID bookingId) {
        OffsetDateTime startsAt = date.atTime(start).atZone(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime endsAt = date.atTime(end).atZone(TimeZones.KYIV).toOffsetDateTime();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, status,
                startsAt, endsAt, PRICE, minutes);
    }

    /**
     * A booking persisted the way {@code BookingService} persists one when the service carries a buffer:
     * {@code ends_at = starts_at + duration + bufferMinutesAfter} (BookingService:1842), with the buffer
     * recorded in {@code buffer_minutes_at_booking}. The slot walk subtracts the persisted
     * {@code [starts_at, ends_at)} span, so the buffer occupies the master exactly as the work does —
     * which is why a buffer can consume a later declared time (case 7i).
     */
    private void insertBufferedBooking(UUID masterId, UUID masterServiceId, UUID clientId, LocalDate date,
                                       LocalTime start, int durationMinutes, int bufferMinutes) {
        OffsetDateTime startsAt = date.atTime(start).atZone(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusMinutes((long) durationMinutes + bufferMinutes);
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId,
                startsAt, endsAt, PRICE, durationMinutes, bufferMinutes);
    }

    /**
     * Resolves a selectable {@code service_types.id} the master does not already offer ACTIVELY.
     *
     * <p>V121's {@code ux_service_def_owner_service_type_active} makes a second ACTIVE definition
     * on the same {@code (owner_type, owner_id, service_type_id)} a unique violation, and cases 2
     * and 6 below both seed TWO services for ONE master on purpose (a long one and a short one, to
     * prove the day gate turns on the service's DURATION rather than on the day being free). The
     * {@code NOT EXISTS} keeps that scenario expressible without weakening the invariant; it
     * mirrors {@code BookingTestFixtures.resolveUnusedServiceTypeId} (kept local, matching this
     * suite's existing self-contained-fixture convention).
     */
    private UUID resolveUnusedIndieServiceTypeId(UUID ownerUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "AND NOT EXISTS (SELECT 1 FROM service_definitions sd "
                        + "                WHERE sd.owner_type = 'INDEPENDENT_MASTER' AND sd.owner_id = ? "
                        + "                  AND sd.service_type_id = st.id AND sd.is_active = TRUE) "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class, ownerUserId);
    }
}
