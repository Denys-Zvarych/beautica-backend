package com.beautica.booking.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
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
    @DisplayName("case 3 — a future day fully covered by a CONFIRMED + a PENDING booking → day FALSE and /slots EMPTY (both statuses subtract)")
    void should_agreeDayUnavailable_when_futureDayFullyBookedByPendingAndConfirmed() {
        LocalDate day = TODAY.plusDays(10);

        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        seedInterval(m.masterId(), day, day, day.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
        UUID client = seedClient();
        // The two bookings tile the whole 09:00–17:00 interval — one CONFIRMED, one PENDING.
        insertBooking(m.masterId(), svc, client, day, LocalTime.of(9, 0), LocalTime.of(13, 0), "CONFIRMED");
        insertBooking(m.masterId(), svc, client, day, LocalTime.of(13, 0), LocalTime.of(17, 0), "PENDING");

        boolean working = bookableDay(m.masterId(), day, svc);
        List<LocalTime> slots = slotStarts(m.masterId(), day, svc);

        assertThat(working)
                .as("every slot is taken by an active (PENDING/CONFIRMED) booking → non-working, even "
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
                serviceDefId, m.userId(), resolveServiceTypeId(), durationMinutes, PRICE, bufferMinutes);
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

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }
}
