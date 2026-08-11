package com.beautica.booking.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.AvailableSlotResponse;
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
        OffsetDateTime startsAt = date.atTime(start).atZone(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime endsAt = date.atTime(end).atZone(TimeZones.KYIV).toOffsetDateTime();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, status,
                startsAt, endsAt, PRICE, minutes);
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
