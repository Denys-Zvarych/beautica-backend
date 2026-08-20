package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.StaffBookingCommand;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.booking.dto.StaffClientRef;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.StaffBookingService;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.service.MasterScheduleService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 22.2 — {@link StaffBookingService} end-to-end over a real Postgres and a real, persisted
 * schedule.
 *
 * <p>The unit suite ({@code StaffBookingServiceTest}) mocks the slot oracle, so it can only prove
 * the service ASKS the right question. This suite drives the genuine
 * resolver → {@code TimeSlotCalculator} → {@code SlotCalculationService} pipeline and, critically,
 * the genuine INSERT — so the walk-in row has to satisfy {@code chk_bookings_guest_fields} (V137),
 * {@code chk_bookings_guest_phone_format} (V89), {@code chk_bookings_source} and the
 * {@code no_overlapping_bookings} GIST EXCLUDE. A phone that was not normalised, or a
 * {@code cancel_token} left non-null, fails here and only here.
 *
 * <p><b>Frozen clock.</b> A single {@code systemClock} bean override pins "now" to 09:00 Kyiv on
 * Wednesday 2026-06-03 for EVERY collaborator at once — the service's lead-time guard, the slot
 * generator's cutoff and {@code MasterScheduleService}'s past-edit guard. That is what makes the
 * "a start equal to now is accepted" case deterministic rather than racy; it is the whole point of
 * the STAFF minimum-lead-0 rule and cannot be asserted against a live clock.
 *
 * <p><b>No endpoint is exercised.</b> There is none yet — the HTTP surface and its authorization
 * are Phase 22.4. The service is autowired and called directly.
 *
 * <p>Fixture data uses no occupied-territory locality references.
 */
@DisplayName("StaffBookingIT — staff walk-in create against a real schedule + real constraints")
@Import(StaffBookingIT.FrozenKyivClockConfig.class)
class StaffBookingIT extends AbstractIntegrationTest {

    /** Wednesday 2026-06-03, 09:00 Kyiv (EEST, +03:00) = 06:00Z. */
    private static final Instant NOW = Instant.parse("2026-06-03T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 3);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 6, 4);
    private static final int TODAY_ISO_DOW = 3;   // Wednesday
    private static final int TOMORROW_ISO_DOW = 4;

    private static final int DURATION_MINUTES = 60;
    private static final BigDecimal PRICE = new BigDecimal("350.00");
    private static final String RAW_PHONE = "050 123 45 67";
    private static final String E164_PHONE = "+380501234567";

    @TestConfiguration
    static class FrozenKyivClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(NOW, TimeZones.KYIV);
        }
    }

    @Autowired
    private StaffBookingService staffBookingService;

    @Autowired
    private MasterScheduleService masterScheduleService;

    @Autowired
    private com.beautica.booking.service.SlotCalculationService slotCalculationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory emf;

    private Seed salon;

    @BeforeEach
    void seedWorkingSalonMaster() {
        salon = seedSalonMaster();
        giveWorkingHours(salon, TODAY_ISO_DOW, TOMORROW_ISO_DOW);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Happy path — the row the database actually accepts
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Walk-in create")
    class WalkInCreate {

        @Test
        @DisplayName("persists a CONFIRMED STAFF row with the phone normalised and every V137 CHECK satisfied")
        void should_persistStaffBooking_when_startIsOnTheMastersSchedule() {
            AppointmentDetailResponse response = create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
            Map<String, Object> row = bookingRow(bookingIdOf(response));
            assertThat(row.get("booking_source")).isEqualTo("STAFF");
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(row.get("created_by_user_id")).isEqualTo(salon.staffUserId());
            assertThat(row.get("guest_phone"))
                    .as("the raw '050 123 45 67' cannot satisfy chk_bookings_guest_phone_format — "
                            + "reaching this assertion at all proves the normaliser ran before the insert")
                    .isEqualTo(E164_PHONE);
            assertThat(row.get("guest_name")).isEqualTo("Олена");
            assertThat(row.get("guest_surname")).isEqualTo("Коваль");
            assertThat(row.get("client_id")).as("STAFF walk-in ⇒ client_id NULL (V137)").isNull();
            assertThat(row.get("cancel_token")).as("STAFF walk-in ⇒ cancel_token NULL (V137)").isNull();
            assertThat(row.get("appointment_id"))
                    .as("Phase 22.12 — N = 1 still creates an appointments header, no size "
                            + "short-circuit; see VisitShapeBoundary for the header-row assertions")
                    .isNotNull();
            assertThat(row.get("salon_id")).isEqualTo(salon.salonId());
        }

        @Test
        @DisplayName("freezes price and duration from the master's assignment")
        void should_freezePriceAndDuration_when_bookingPersisted() {
            AppointmentDetailResponse response = create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            Map<String, Object> row = bookingRow(bookingIdOf(response));
            assertThat((BigDecimal) row.get("price_at_booking")).isEqualByComparingTo(PRICE);
            assertThat(row.get("duration_minutes_at_booking")).isEqualTo(DURATION_MINUTES);
            assertThat(row.get("price_max_at_booking")).as("FIXED price ⇒ no band ceiling").isNull();
        }

        @Test
        @DisplayName("an independent master with no salon books successfully with a null salonId")
        void should_persistWithNullSalon_when_masterIsIndependent() {
            Seed independent = seedIndependentMaster();
            giveWorkingHours(independent, TODAY_ISO_DOW, TOMORROW_ISO_DOW);

            AppointmentDetailResponse response = create(independent, command(independent, kyiv(TODAY, 13, 0),
                            new StaffBookingScope.Self(independent.masterUserId())));

            assertThat(bookingRow(bookingIdOf(response)).get("salon_id")).isNull();
            assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Lead time — proven against the REAL slot generator, not a mocked one
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("STAFF lead time")
    class StaffLeadTime {

        @Test
        @DisplayName("accepts a start equal to now — the slot list the staff path reads has no 15-minute floor")
        void should_accept_when_startIsExactlyNowAndInsideWorkingHours() {
            // 09:00 Kyiv today IS the frozen "now" AND the first slot of the working day. With the
            // shared client floor (now + 15 min) the generator would not emit it and this would 409,
            // so a green assertion here is the end-to-end proof of the minimum-lead-0 rule.
            OffsetDateTime now = kyiv(TODAY, 9, 0);
            assertThat(now.toInstant()).isEqualTo(NOW);

            assertThatCode(() -> create(salon, command(salon, now, salon.salonId()))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a start before now with 400")
        void should_reject400_when_startIsInThePast() {
            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TODAY, 8, 30), salon.salonId())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Booking cannot start in the past");

            assertThat(bookingCount()).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Schedule containment — the guarantee this phase adds
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Schedule containment")
    class ScheduleContainment {

        @Test
        @DisplayName("rejects a start outside the master's working interval even though nothing overlaps it")
        void should_reject409_when_startIsOutsideWorkingHours() {
            // 18:00 is after the 17:00 interval end: future, on the 30-minute grid, and the master's
            // calendar is completely empty — so ONLY the schedule-fit gate can reject this.
            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TODAY, 18, 0), salon.salonId())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("rejects a start on a DAY_OFF override")
        void should_reject409_when_dateIsADayOff() {
            masterScheduleService.upsertOverride(salon.masterUserId(), salon.masterId(),
                    new ScheduleOverrideRequest(TOMORROW, ScheduleExceptionKind.DAY_OFF, null));

            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TOMORROW, 12, 0), salon.salonId())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available");

            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("accepts inside a CUSTOM_HOURS window and rejects in its gap")
        void should_honourCustomHours_when_overrideNarrowsTheDay() {
            masterScheduleService.upsertOverride(salon.masterUserId(), salon.masterId(),
                    new ScheduleOverrideRequest(TOMORROW, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(new WorkIntervalDto(LocalTime.of(14, 0), LocalTime.of(17, 0)))));

            assertThatCode(() -> create(salon, command(salon, kyiv(TOMORROW, 14, 0), salon.salonId()))).doesNotThrowAnyException();

            // 10:00 is inside the WEEKLY TEMPLATE's 09:00-17:00 but outside the override that replaced
            // it for this date — the case a raw overlap check alone would wave through.
            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TOMORROW, 10, 0), salon.salonId())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Multi-service visit — whole-chain guard, end to end (Phase 22.12)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-service visit")
    class MultiServiceVisitCreate {

        /**
         * The case a per-item overlap check would wrongly accept: the visit's FIRST service
         * (12:00-13:00) does not collide with anything, but the chain's second leg (13:00-14:00)
         * lands exactly on an already-persisted booking. The whole-span
         * {@code BookingSlotLockGuard#lockMasterAndAssertFree} check must reject the whole visit —
         * and, critically, must reject it BEFORE any header or booking row is written (atomicity).
         */
        @Test
        @DisplayName("rejects a chain that collides with an existing booking, though its first service alone would fit")
        void should_reject409_when_chainCollidesWithExistingBooking_thoughFirstServiceWouldFit() {
            UUID service2 = insertService(salon.masterId(), "SALON", salon.salonId(), 1);
            UUID service3 = insertService(salon.masterId(), "SALON", salon.salonId(), 2);
            create(salon, command(salon, kyiv(TODAY, 13, 0), salon.salonId()));

            assertThatThrownBy(() -> create(salon, visitCommand(salon,
                    List.of(salon.masterServiceId(), service2, service3), kyiv(TODAY, 12, 0),
                    new StaffBookingScope.InSalon(salon.salonId()))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available");

            assertThat(bookingCount()).as("only the pre-existing booking, nothing from the failed visit").isEqualTo(1);
            // The pre-existing single-service booking ALSO created its own header (D2: N = 1 still
            // creates one) — so the count after the failed visit is 1, not 0. The failed visit itself
            // must contribute no second header.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))
                    .as("only the pre-existing booking's own header — the failed visit adds none")
                    .isEqualTo(1);
        }

        /**
         * A per-item schedule-fit check would wrongly ACCEPT this: the first service (16:00-17:00)
         * fits the working window exactly, but the chain's Σ duration runs the block to 19:00 — an
         * hour past the master's 17:00 close. Only the whole-chain guard
         * ({@code BookingSlotAvailabilityGuard#assertStaffVisitStartsOnAvailableSlot}) catches this.
         */
        @Test
        @DisplayName("rejects a chain whose total duration overruns the working window, though its first service alone would fit")
        void should_reject409_when_chainOverrunsWorkingWindow() {
            UUID service2 = insertService(salon.masterId(), "SALON", salon.salonId(), 1);
            UUID service3 = insertService(salon.masterId(), "SALON", salon.salonId(), 2);

            assertThatThrownBy(() -> create(salon, visitCommand(salon,
                    List.of(salon.masterServiceId(), service2, service3), kyiv(TODAY, 16, 0),
                    new StaffBookingScope.InSalon(salon.salonId()))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available");

            assertThat(bookingCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
        }

        @Test
        @DisplayName("rejects a visit start that does not land on the 30-minute slot grid")
        void should_reject409_when_startIsOffGrid() {
            UUID service2 = insertService(salon.masterId(), "SALON", salon.salonId(), 1);

            assertThatThrownBy(() -> create(salon, visitCommand(salon,
                    List.of(salon.masterServiceId(), service2), kyiv(TODAY, 12, 5),
                    new StaffBookingScope.InSalon(salon.salonId()))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available");

            assertThat(bookingCount()).isZero();
        }

        /**
         * The 0-lead STAFF floor, end to end, for a chained visit — not just a single-service
         * booking. 09:00 Kyiv today IS the frozen "now" AND the first slot of the working day.
         */
        @Test
        @DisplayName("accepts a multi-service visit starting exactly now")
        void should_return201_when_startIsNow() {
            UUID service2 = insertService(salon.masterId(), "SALON", salon.salonId(), 1);
            OffsetDateTime now = kyiv(TODAY, 9, 0);
            assertThat(now.toInstant()).isEqualTo(NOW);

            assertThatCode(() -> create(salon, visitCommand(salon,
                    List.of(salon.masterServiceId(), service2), now,
                    new StaffBookingScope.InSalon(salon.salonId()))))
                    .doesNotThrowAnyException();

            assertThat(bookingCount()).isEqualTo(2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Rejections
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Rejections")
    class Rejections {

        @Test
        @DisplayName("rejects a window that collides with an already-persisted CONFIRMED booking")
        void should_reject409_when_windowOverlapsAnExistingBooking() {
            create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TODAY, 12, 30), salon.salonId())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available");

            assertThat(bookingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects a masterServiceId that belongs to a different master (service eligibility)")
        void should_reject404_when_serviceBelongsToAnotherMaster() {
            Seed other = seedIndependentMaster();

            assertThatThrownBy(() -> create(salon,
                    new StaffBookingCommand(new StaffBookingScope.InSalon(salon.salonId()),
                            salon.masterId(), List.of(other.masterServiceId()), kyiv(TODAY, 12, 0), walkIn())))
                    .isInstanceOf(NotFoundException.class);

            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("rejects a masterServiceId that belongs to a different master at position 2 of a multi-service visit")
        void should_reject404_when_secondServiceBelongsToAnotherMaster() {
            Seed other = seedIndependentMaster();

            assertThatThrownBy(() -> create(salon,
                    new StaffBookingCommand(new StaffBookingScope.InSalon(salon.salonId()),
                            salon.masterId(), List.of(salon.masterServiceId(), other.masterServiceId()),
                            kyiv(TODAY, 12, 0), walkIn())))
                    .isInstanceOf(NotFoundException.class);

            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("rejects an inactive master")
        void should_reject404_when_masterIsInactive() {
            jdbc.update("UPDATE masters SET is_active = false WHERE id = ?", salon.masterId());

            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId())))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Master not found or inactive");
        }

        @Test
        @DisplayName("rejects a master that is not in the caller's salon")
        void should_reject403_when_masterBelongsToAnotherSalon() {
            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TODAY, 12, 0), UUID.randomUUID())))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(bookingCount()).isZero();
        }

        /**
         * <b>The unscoped-null hole, end to end</b> (security MEDIUM, 2026-08-18). Under the previous
         * nullable-{@code salonId} command a {@code null} salon skipped scoping entirely, so this
         * exact call — a {@code Self} scope naming a user who is neither the actor nor the target
         * master's user — created a real {@code CONFIRMED} row on a stranger's calendar.
         * {@link StaffBookingScope} has no unscoped variant, so SOMETHING always asserts.
         *
         * <p><b>Which guard fires here</b> (Javadoc corrected, QA 2026-08-18 — the previous text
         * claimed "the {@code Self} arm compares users and refuses", which this fixture cannot
         * reach). The actor is {@code salon.staffUserId()} and the scope names
         * {@code outsider.masterUserId()}, so the FIRST check —
         * {@code self.masterUserId().equals(actorId)}, the trusted-vs-untrusted cross-check — refuses
         * and {@code master.getUser()} is never read. That is the branch this case pins: a
         * body-sourced {@code Self} may not name a third party at all. The master-vs-user branch is
         * pinned separately by {@link #should_reject403_when_selfScopeUserIsNotTheMastersOwnUser}.
         */
        @Test
        @DisplayName("rejects a Self-scoped command naming a third party — the actor cross-check refuses first")
        void should_reject403_when_scopeIsSelfAndMasterIsSomeoneElse() {
            Seed outsider = seedIndependentMaster();

            assertThatThrownBy(() -> create(salon, command(salon, kyiv(TODAY, 12, 0),
                    new StaffBookingScope.Self(outsider.masterUserId()))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this account");

            assertThat(bookingCount()).isZero();
        }

        /**
         * <b>The master-vs-user branch, at the IT tier</b> (QA-added 2026-08-18 — the gap the
         * corrected Javadoc above exposed). The actor names HIMSELF, so
         * {@code self.masterUserId().equals(actorId)} passes and the refusal can only come from the
         * second comparison, {@code master.getUser().getId()} — the arm no IT case reached, and the
         * one that keeps {@code findByIdWithUserAndSalon}'s {@code user} JOIN FETCH load-bearing.
         *
         * <p><b>Why this is worth an IT and not left to the two unit cases.</b> The unit tier builds
         * its {@code Master} with a {@code User} already attached, so it proves the comparison is
         * WRITTEN but cannot prove the production finder actually delivers a resolvable
         * {@code getUser()} for a DB-loaded master. This is also the cross-actor IDOR shape (QA
         * playbook Q9): a real, authenticated INDEPENDENT_MASTER reaching a real salon master's
         * calendar. The target has working hours and the start is inside them, so bookability,
         * assignment, lead time, schedule fit and overlap would all pass — the absent row is
         * attributable to the scope check alone.
         */
        @Test
        @DisplayName("rejects a Self scope whose named user is the actor but not the target master's own user")
        void should_reject403_when_selfScopeUserIsNotTheMastersOwnUser() {
            Seed independent = seedIndependentMaster();

            assertThatThrownBy(() -> createAs(independent.masterUserId(),
                    command(salon, kyiv(TODAY, 12, 0),
                            new StaffBookingScope.Self(independent.masterUserId()))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this account");

            assertThat(bookingCount()).isZero();
        }

        /**
         * The {@code salon == null} half of the {@code InSalon} arm against a real database: a
         * salon-scoped caller may not reach a salon-LESS independent master. Distinct from
         * {@link #should_reject403_when_masterBelongsToAnotherSalon}, which exercises the
         * {@code !equals} half — a guard written as the equality test alone would NPE here, i.e.
         * answer a 500 to a request that must be a 403.
         */
        @Test
        @DisplayName("rejects an InSalon scope aimed at an independent master, who belongs to no salon")
        void should_reject403_when_inSalonScopeTargetsASalonLessMaster() {
            Seed independent = seedIndependentMaster();
            giveWorkingHours(independent, TODAY_ISO_DOW, TOMORROW_ISO_DOW);

            assertThatThrownBy(() -> createAs(salon.staffUserId(),
                    command(independent, kyiv(TODAY, 12, 0),
                            new StaffBookingScope.InSalon(salon.salonId()))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this salon");

            assertThat(bookingCount()).isZero();
        }

        /**
         * <b>The relocated {@code createdByUserId} hazard, end to end</b> (security MEDIUM,
         * 2026-08-18). {@code Self} carries an unconstrained {@code UUID} and lives on the same
         * record as every client-supplied value, so a body-sourced scope is one 22.4 mapping field
         * away. Here the named user genuinely IS the target master's user — the
         * {@code master.getUser()} comparison passes — and only the {@code actorId} cross-check stops
         * the salon owner from writing a {@code CONFIRMED} row onto an independent master's real
         * calendar, attributed to the owner's own id.
         *
         * <p>The master is given working hours and the start is inside them, so every other gate
         * (bookability, assignment, lead time, schedule fit, overlap) would pass: the absent row is
         * attributable to the scope check alone.
         */
        @Test
        @DisplayName("rejects a Self-scoped command whose named user is not the acting staff user")
        void should_reject403_when_selfScopeNamesAUserOtherThanTheActor() {
            Seed independent = seedIndependentMaster();
            giveWorkingHours(independent, TODAY_ISO_DOW, TOMORROW_ISO_DOW);

            assertThatThrownBy(() -> createAs(salon.staffUserId(),
                    command(independent, kyiv(TODAY, 13, 0),
                            new StaffBookingScope.Self(independent.masterUserId()))))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(bookingCount()).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // After-commit cache eviction — the live BookingAfterCommit branch
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Availability cache eviction")
    class AvailabilityCacheEviction {

        /**
         * <b>{@code BookingAfterCommit}'s TRANSACTION-ACTIVE branch, which only an IT can reach</b>
         * (QA-added 2026-08-18). Every unit test calls the service outside a transaction, so it
         * exercises the {@code else} fallback that runs the task inline; the
         * {@code registerSynchronization} path — the one production always takes — had no coverage at
         * all, and neither did the eviction's effect on a genuinely warm Caffeine entry.
         *
         * <p>The read is the CLIENT-facing {@code @Cacheable} entry, deliberately: that is the one a
         * self-service client is served from, and leaving it stale is how a walk-in and a client end
         * up offered the same 14:00. Warming it first is what makes the assertion non-vacuous — an
         * uncached second read would pass whatever the service did.
         *
         * <p>Mutation-verified: deleting the {@code registerSlotEviction(...)} call from
         * {@code StaffBookingService#createStaffBooking} turns this RED (14:00 survives in the warm
         * entry for the whole 60-second TTL).
         */
        @Test
        @DisplayName("a staff create drops the master's already-warm client slot list, so the taken slot disappears")
        void should_evictTheWarmClientSlotList_when_staffBookingCommits() {
            assertThat(clientSlotStarts(TOMORROW))
                    .as("guard against a vacuous assertion: 14:00 must be on offer before the booking")
                    .contains(kyiv(TOMORROW, 14, 0));

            create(salon, command(salon, kyiv(TOMORROW, 14, 0), salon.salonId()));

            assertThat(clientSlotStarts(TOMORROW))
                    .as("the warm entry was evicted after commit, so the consumed 14:00 is gone")
                    .doesNotContain(kyiv(TOMORROW, 14, 0));
        }

        private List<OffsetDateTime> clientSlotStarts(LocalDate date) {
            return slotCalculationService.getAvailableSlots(salon.masterId(), date, salon.masterServiceId())
                    .stream()
                    .map(slot -> slot.startsAt().toOffsetDateTime())
                    .toList();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The visit-shape boundary
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Visit-shape boundary")
    class VisitShapeBoundary {

        /**
         * Phase 22.2 chose option (a) — staff bookings are single-service — precisely BECAUSE
         * {@code chk_appointment_source} admitted only {@code 'APP','LINK'} at the time: V137 widened
         * the {@code bookings} constraint alone, so a multi-service staff visit would have failed at
         * the appointment insert. Phase 22.8 (V139/V140) closed that gap, so the header CHECK now
         * admits STAFF too. Phase 22.12 is where {@code StaffBookingService} finally exercises it:
         * every visit — including a one-service one, D2's locked "no size short-circuit" decision —
         * now persists exactly ONE {@code appointments} header, and every chained {@code bookings}
         * row carries that header's id.
         *
         * <p><b>Replaces {@code should_leaveAppointmentIdNull_when_staffBookingPersisted}</b>, which
         * pinned the OBSOLETE pre-22.12 behaviour (no writer ever joined an appointment). That
         * assertion is now false for every shape created after this phase; the legacy NULL shape
         * lives on only in rows created before it, which Phase 22.15's dual-shape parity matrix
         * covers separately — this test asserts the NEW shape only.
         */
        @Test
        @DisplayName("links every chained booking to exactly one appointments header (Phase 22.12)")
        void should_linkEveryBookingToOneHeader_when_staffVisitPersisted() {
            UUID service2 = insertService(salon.masterId(), "SALON", salon.salonId(), 1);
            UUID service3 = insertService(salon.masterId(), "SALON", salon.salonId(), 2);

            AppointmentDetailResponse response = create(salon, visitCommand(salon,
                    List.of(salon.masterServiceId(), service2, service3), kyiv(TODAY, 9, 0),
                    new StaffBookingScope.InSalon(salon.salonId())));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))
                    .as("exactly one header for the whole visit")
                    .isEqualTo(1);
            UUID appointmentId = (UUID) bookingRow(bookingIdOf(response)).get("appointment_id");
            assertThat(appointmentId).isNotNull();
            assertThat(response.id())
                    .as("Phase 22.14 — the 201 body IS the visit: its own id equals the header every "
                            + "chained booking links to, read straight off the response with no DB "
                            + "round trip")
                    .isEqualTo(appointmentId);
            assertThat(response.items())
                    .as("one item per chained service")
                    .hasSize(3);

            List<UUID> childAppointmentIds = jdbc.queryForList(
                    "SELECT appointment_id FROM bookings WHERE master_id = ? ORDER BY starts_at",
                    UUID.class, salon.masterId());
            assertThat(childAppointmentIds)
                    .as("every chained row links to the SAME header, none NULL")
                    .hasSize(3)
                    .containsOnly(appointmentId);

            Map<String, Object> header = appointmentRow(appointmentId);
            assertThat(header.get("booking_source")).isEqualTo("STAFF");
            assertThat(header.get("status")).isEqualTo("CONFIRMED");
            assertThat(header.get("cancel_token")).as("no guest self-cancel link for a staff visit").isNull();
            assertThat(header.get("created_by_user_id")).isEqualTo(salon.staffUserId());
        }

        @Test
        @DisplayName("persists a 1-header + 5-booking visit, all CONFIRMED/STAFF, cancel_token NULL throughout")
        void should_persistHeaderAndChain_when_fiveServiceWalkIn() {
            List<UUID> serviceIds = new ArrayList<>();
            serviceIds.add(salon.masterServiceId());
            for (int i = 1; i <= 4; i++) {
                serviceIds.add(insertService(salon.masterId(), "SALON", salon.salonId(), i));
            }

            AppointmentDetailResponse response = create(salon, visitCommand(salon, serviceIds, kyiv(TODAY, 9, 0),
                    new StaffBookingScope.InSalon(salon.salonId())));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isEqualTo(1);
            assertThat(bookingCount()).isEqualTo(5);
            UUID appointmentId = (UUID) bookingRow(bookingIdOf(response)).get("appointment_id");
            assertThat(response.id()).isEqualTo(appointmentId);
            assertThat(response.items()).hasSize(5);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM bookings WHERE master_id = ? ORDER BY starts_at", salon.masterId());
            assertThat(rows).hasSize(5).allSatisfy(row -> {
                assertThat(row.get("appointment_id")).isEqualTo(appointmentId);
                assertThat(row.get("booking_source")).isEqualTo("STAFF");
                assertThat(row.get("status")).isEqualTo("CONFIRMED");
                assertThat(row.get("cancel_token")).isNull();
            });
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Statement count (Anti-Bug §F3 audit finding 3, 2026-08-20) — pins createStaffBooking's SQL
    // cost so the N-1 wasted platformServiceName lazy loads (audit finding 1) cannot silently
    // regress unnoticed, mirroring BookingPriceRangeContractIT#OWNER_DETAIL_STATEMENTS_ALIGNED and
    // AppointmentReadIT#VISIT_DETAIL_STATEMENTS.
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Statement count")
    class StatementCount {

        /**
         * Fixed per-visit cost — everything {@code createStaffBooking} issues that does NOT scale
         * with the number of chained services: the master read, the SMS-budget count, the whole-chain
         * schedule-fit query, the advisory lock + overlap check, the header insert, the batched
         * booking insert(s) (one JDBC round trip regardless of N, {@code hibernate.jdbc.batch_size:
         * 50}), the single first-item {@code service_types} lazy load Finding-1 left in place, and
         * {@code AppointmentService#enrich}'s reads. Measured against an isolated, freshly-seeded
         * master per N (own salon, own working-hours row, distinct guest phone) so no fixture reuse
         * across N could shift the count via warm caches or an already-loaded row.
         */
        private static final long CREATE_FIXED_STATEMENTS = 11L;

        /**
         * Per-CHAINED-ITEM cost, AFTER the Finding-1 fix: exactly ONE extra statement per additional
         * {@code masterServiceIds} entry — {@code VisitPlanner#planChainedItems}'
         * {@code findByMasterIdAndIdWithGraph} lookup (pre-existing, shared with the APP/LINK visit
         * paths, out of scope — see the class-level "Do NOT touch VisitPlanner" note). BEFORE the
         * Finding-1 fix this was 3 per item (the planner lookup PLUS the discarded
         * {@code platformServiceName} lazy load for every item, min-cardinality proxy fetches
         * included) — see {@code should_notRegressToTheN-1WastedLazyLoadShape} below for the
         * falsification of this exact number.
         */
        private static final long CREATE_PER_ITEM_STATEMENTS = 1L;

        @Test
        @DisplayName("N=1: fixed cost only, no chained-item statements")
        void should_issueFixedStatementCount_when_creatingASingleServiceVisit() {
            Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
            statistics.setStatisticsEnabled(true);
            Seed fresh = seedStatementCountMaster();

            statistics.clear();
            create(fresh, singleServiceStatementCountCommand(fresh, 1));
            long statements = statistics.getPrepareStatementCount();

            assertThat(statements)
                    .as("N=1 must cost exactly the fixed baseline — a rise here means a new "
                            + "per-visit (not per-item) query was added to the create path")
                    .isEqualTo(CREATE_FIXED_STATEMENTS + CREATE_PER_ITEM_STATEMENTS);
        }

        @Test
        @DisplayName("N=10: fixed cost plus exactly one statement per chained item")
        void should_issueFixedPlusLinearPerItemStatementCount_when_creatingATenServiceVisit() {
            Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
            statistics.setStatisticsEnabled(true);
            Seed fresh = seedStatementCountMaster();
            List<UUID> serviceIds = new ArrayList<>();
            serviceIds.add(fresh.masterServiceId());
            for (int i = 1; i < 10; i++) {
                serviceIds.add(insertService(fresh.masterId(), "SALON", fresh.salonId(), i));
            }

            statistics.clear();
            create(fresh, new StaffBookingCommand(new StaffBookingScope.InSalon(fresh.salonId()),
                    fresh.masterId(), serviceIds, kyiv(TODAY, 9, 0), statementCountWalkIn(10)));
            long statements = statistics.getPrepareStatementCount();

            assertThat(statements)
                    .as("N=10 must land at fixed + 10×per-item. Before the Finding-1 fix this was "
                            + "30 (10 + 2×10) — the N-1 wasted service_types lazy loads; the "
                            + "falsification test below proves the guard actually catches that "
                            + "regression.")
                    .isEqualTo(CREATE_FIXED_STATEMENTS + 10 * CREATE_PER_ITEM_STATEMENTS);
        }

        /** Isolated per-test master: own salon, own weekly schedule wide enough for a 10×60min chain. */
        private Seed seedStatementCountMaster() {
            Seed fresh = seedSalonMaster();
            masterScheduleService.upsertWeeklySchedule(fresh.masterUserId(), fresh.masterId(), null,
                    new WeeklyScheduleRequest(TODAY, null, List.of(new WeeklyScheduleDayRequest(
                            TODAY_ISO_DOW,
                            List.of(new WorkIntervalDto(LocalTime.of(0, 0), LocalTime.of(23, 59)))))));
            return fresh;
        }

        private StaffBookingCommand singleServiceStatementCountCommand(Seed seed, int phoneSuffix) {
            return new StaffBookingCommand(new StaffBookingScope.InSalon(seed.salonId()), seed.masterId(),
                    List.of(seed.masterServiceId()), kyiv(TODAY, 9, 0), statementCountWalkIn(phoneSuffix));
        }

        /** A distinct guest phone per call — the isolated master alone does not de-dupe the
         * per-phone SMS-budget count, which is keyed by phone across the whole suite's DB rows. */
        private StaffClientRef.Guest statementCountWalkIn(int suffix) {
            return new StaffClientRef.Guest("Олена", "Коваль", String.format("+38050912%04d", suffix));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Locked-rule regressions (Phase 22.5 Gap 2) — per-BOOKING isolation
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Locked-rule regressions")
    class LockedRuleRegressions {

        /**
         * Per-BOOKING rule (CLAUDE.md Domain Rules — {@code project_completion_is_per_service}):
         * every transition, including a create, touches exactly ONE booking row. A staff create
         * against the same master must never mutate a prior booking for that master — snapshotting
         * the sibling's full row before and after is the only way to prove "untouched" rather than
         * merely "still present."
         */
        @Test
        @DisplayName("a staff create never touches a sibling booking for the same master")
        void should_leaveSiblingBookingUntouched_when_creatingAnotherStaffBooking() {
            AppointmentDetailResponse sibling = create(salon, command(salon, kyiv(TODAY, 9, 0), salon.salonId()));
            Map<String, Object> siblingBefore = bookingRow(bookingIdOf(sibling));

            create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            assertThat(bookingRow(bookingIdOf(sibling))).isEqualTo(siblingBefore);
        }

        /**
         * Phase 22.12's widened counterpart: a header now exists, so "touches no sibling" must be
         * proved at BOTH grains — no sibling VISIT's child booking rows change, AND no sibling
         * VISIT's {@code appointments} header row changes. Same full-row-snapshot idiom as
         * {@link #should_leaveSiblingBookingUntouched_when_creatingAnotherStaffBooking}, extended
         * rather than reinvented.
         */
        @Test
        @DisplayName("a staff visit create never touches a sibling visit's bookings or header for the same master")
        void should_leaveSiblingVisitUntouched_when_creatingAnotherStaffVisit() {
            UUID service2 = insertService(salon.masterId(), "SALON", salon.salonId(), 1);
            AppointmentDetailResponse siblingFirst = create(salon, visitCommand(salon,
                    List.of(salon.masterServiceId(), service2), kyiv(TODAY, 9, 0),
                    new StaffBookingScope.InSalon(salon.salonId())));
            Map<String, Object> siblingBookingBefore = bookingRow(bookingIdOf(siblingFirst));
            UUID siblingAppointmentId = (UUID) siblingBookingBefore.get("appointment_id");
            Map<String, Object> siblingHeaderBefore = appointmentRow(siblingAppointmentId);

            create(salon, visitCommand(salon, List.of(salon.masterServiceId(), service2),
                    kyiv(TODAY, 13, 0), new StaffBookingScope.InSalon(salon.salonId())));

            assertThat(bookingRow(bookingIdOf(siblingFirst))).isEqualTo(siblingBookingBefore);
            assertThat(appointmentRow(siblingAppointmentId)).isEqualTo(siblingHeaderBefore);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    /**
     * @param staffUserId  the user recorded in {@code created_by_user_id} — a real row, because the
     *                     V137 FK is {@code ON DELETE RESTRICT} and would reject a synthetic id
     * @param masterUserId the master's OWN user, the actor {@code MasterScheduleService} authorises
     *                     schedule writes against
     */
    private record Seed(UUID salonId, UUID masterId, UUID masterUserId, UUID masterServiceId, UUID staffUserId) {
    }

    /**
     * An OWNER-OPERATED salon master ({@code master_type = 'SALON_OWNER'}, the master's user IS the
     * salon owner) — deliberately, not an invited {@code SALON_MASTER}.
     *
     * <p>{@code AuthorizationService#enforceCanManageMasterSchedule} authorises an invited
     * {@code SALON_MASTER}'s schedule through {@code hasManagementAccess}, which reads the actor's
     * role from the <em>SecurityContext</em>; this suite calls services directly with no HTTP
     * request, so that branch throws {@code "Not authenticated"} while seeding. The
     * {@code SALON_OWNER} branch compares ids only. Nothing under test cares which master type it
     * is — {@code StaffBookingService} is master-type agnostic, exactly like the slot layer — so
     * this picks the shape whose FIXTURE setup needs no fake authentication.
     */
    private Seed seedSalonMaster() {
        UUID ownerId = insertUser("SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbc.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, NOW(), NOW())", salonId, ownerId, "Salon-" + salonId);
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'SALON_OWNER', true, NOW(), NOW())", masterId, ownerId, salonId);
        UUID masterServiceId = insertService(masterId, "SALON", salonId);
        return new Seed(salonId, masterId, ownerId, masterServiceId, ownerId);
    }

    private Seed seedIndependentMaster() {
        UUID userId = insertUser("INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())", masterId, userId);
        UUID masterServiceId = insertService(masterId, "INDEPENDENT_MASTER", userId);
        return new Seed(null, masterId, userId, masterServiceId, userId);
    }

    private UUID insertUser(String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, salon_id, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', ?, ?, 'Марія', 'Левченко', true, true)",
                id, "staff-it-" + id + "@beautica.test", role, salonId);
        return id;
    }

    private UUID insertService(UUID masterId, String ownerType, UUID ownerId) {
        return insertService(masterId, ownerType, ownerId, resolveServiceTypeId(0));
    }

    /**
     * Additive overload (Phase 22.12) for a visit-create fixture that needs SEVERAL services on the
     * SAME owner: {@code ux_service_def_owner_service_type_active} is a partial-unique index over
     * {@code (owner_type, owner_id, service_type_id)}, so two calls sharing an owner MUST resolve
     * distinct {@code service_type_id}s — {@code index} selects the Nth one, deterministically
     * ordered exactly like the single-service {@link #insertService(UUID, String, UUID)} overload's
     * OFFSET-0 pick, so existing single-service Seeds are unaffected.
     */
    private UUID insertService(UUID masterId, String ownerType, UUID ownerId, int index) {
        return insertService(masterId, ownerType, ownerId, resolveServiceTypeId(index));
    }

    private UUID insertService(UUID masterId, String ownerType, UUID ownerId, UUID serviceTypeId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbc.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, "
                        + "updated_at) VALUES (?, ?, ?, 'Манікюр', ?, ?, ?, 0, true, NOW(), NOW())",
                serviceDefId, ownerType, ownerId, serviceTypeId, DURATION_MINUTES, PRICE);
        UUID masterServiceId = UUID.randomUUID();
        jdbc.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, true, NOW(), NOW())", masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /**
     * V111 made {@code service_definitions.service_type_id} NOT NULL — resolve a real, selectable
     * one. {@code index} (0-based, via {@code OFFSET}) picks the Nth in a stable order, so repeated
     * calls for the SAME owner return DISTINCT ids — required by
     * {@code ux_service_def_owner_service_type_active} (see {@link #insertService(UUID, String, UUID, int)}).
     */
    private UUID resolveServiceTypeId(int index) {
        return jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1 OFFSET ?",
                UUID.class, index);
    }

    /** 09:00–17:00 on the given ISO weekdays, valid from the frozen "today" onwards. */
    private void giveWorkingHours(Seed seed, int... isoDays) {
        List<WeeklyScheduleDayRequest> days = java.util.Arrays.stream(isoDays)
                .mapToObj(dow -> new WeeklyScheduleDayRequest(dow,
                        List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
                .toList();
        masterScheduleService.upsertWeeklySchedule(seed.masterUserId(), seed.masterId(), null,
                new WeeklyScheduleRequest(TODAY, null, days));
    }

    /**
     * A salon-scoped command. There is no "unscoped" shape left to construct — the independent
     * master case names {@link StaffBookingScope.Self} explicitly at its call sites, where the old
     * nullable-{@code salonId} fixture simply passed {@code null} and bought no scoping at all
     * (security MEDIUM, 2026-08-18).
     */
    private StaffBookingCommand command(Seed seed, OffsetDateTime startsAt, UUID salonId) {
        return command(seed, startsAt, new StaffBookingScope.InSalon(salonId));
    }

    private StaffBookingCommand command(Seed seed, OffsetDateTime startsAt, StaffBookingScope scope) {
        return new StaffBookingCommand(scope, seed.masterId(), List.of(seed.masterServiceId()),
                startsAt, walkIn());
    }

    /** Multi-service visit command (Phase 22.12) — the ordered {@code masterServiceIds} chain. */
    private StaffBookingCommand visitCommand(
            Seed seed, List<UUID> masterServiceIds, OffsetDateTime startsAt, StaffBookingScope scope) {
        return new StaffBookingCommand(scope, seed.masterId(), masterServiceIds, startsAt, walkIn());
    }

    /**
     * The acting staff user is an explicit ARGUMENT, never a command field — so Phase 22.4's
     * {@code request.toCommand()} mapping cannot reach {@code bookings.created_by_user_id}.
     */
    private AppointmentDetailResponse create(Seed seed, StaffBookingCommand cmd) {
        return createAs(seed.staffUserId(), cmd);
    }

    /**
     * Names the acting user independently of the seed, so a {@link StaffBookingScope.Self} scope can
     * be aimed at a master the actor is NOT (security MEDIUM, 2026-08-18) — the combination
     * {@code create(Seed, …)} can never produce for an independent master, whose seed makes
     * {@code staffUserId} and {@code masterUserId} the same id.
     */
    private AppointmentDetailResponse createAs(UUID actorId, StaffBookingCommand cmd) {
        return staffBookingService.createStaffBooking(cmd, actorId);
    }

    /**
     * The FIRST chained booking's id — {@code response.id()} is now the VISIT (appointment) id
     * (Phase 22.14), so every fixture here that needs a single BOOKING row's id (to look it up, to
     * complete it, to prove sibling isolation) must read it off {@code items[0]} instead. Every
     * fixture in this suite creates a single-service command, so item 0 is the only booking.
     */
    private static UUID bookingIdOf(AppointmentDetailResponse response) {
        return response.items().get(0).bookingId();
    }

    private static StaffClientRef.Guest walkIn() {
        return new StaffClientRef.Guest("Олена", "Коваль", RAW_PHONE);
    }

    private static OffsetDateTime kyiv(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    private Map<String, Object> bookingRow(UUID bookingId) {
        return jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
    }

    private Map<String, Object> appointmentRow(UUID appointmentId) {
        return jdbc.queryForMap("SELECT * FROM appointments WHERE id = ?", appointmentId);
    }

    private int bookingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class);
    }
}
