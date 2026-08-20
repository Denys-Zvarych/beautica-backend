package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingResponse;
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
            BookingResponse response = create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
            Map<String, Object> row = bookingRow(response.id());
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
                    .as("single-service by design — no appointments row, so chk_appointment_source "
                            + "is never reached")
                    .isNull();
            assertThat(row.get("salon_id")).isEqualTo(salon.salonId());
        }

        @Test
        @DisplayName("freezes price and duration from the master's assignment")
        void should_freezePriceAndDuration_when_bookingPersisted() {
            BookingResponse response = create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            Map<String, Object> row = bookingRow(response.id());
            assertThat((BigDecimal) row.get("price_at_booking")).isEqualByComparingTo(PRICE);
            assertThat(row.get("duration_minutes_at_booking")).isEqualTo(DURATION_MINUTES);
            assertThat(row.get("price_max_at_booking")).as("FIXED price ⇒ no band ceiling").isNull();
        }

        @Test
        @DisplayName("an independent master with no salon books successfully with a null salonId")
        void should_persistWithNullSalon_when_masterIsIndependent() {
            Seed independent = seedIndependentMaster();
            giveWorkingHours(independent, TODAY_ISO_DOW, TOMORROW_ISO_DOW);

            BookingResponse response = create(independent, command(independent, kyiv(TODAY, 13, 0),
                            new StaffBookingScope.Self(independent.masterUserId())));

            assertThat(bookingRow(response.id()).get("salon_id")).isNull();
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
                            salon.masterId(), other.masterServiceId(), kyiv(TODAY, 12, 0), walkIn())))
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
    // The single-service boundary
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Single-service boundary")
    class SingleServiceBoundary {

        /**
         * <b>Tripwire for the {@code appointments} source CHECK.</b> Phase 22.2 chose option (a) —
         * staff bookings are single-service — precisely BECAUSE {@code chk_appointment_source} still
         * admits only {@code 'APP','LINK'}: V137 widened the {@code bookings} constraint alone, so a
         * multi-service staff visit would fail at the appointment insert. No migration was written
         * for a capability nothing offers.
         *
         * <p>This test pins that premise. It goes RED the day someone widens the constraint — which
         * is the correct moment to come back here, lift the scalar {@code masterServiceId} on
         * {@code StaffBookingCommand} and delete {@code StaffBookingService#onlyItem}. It is a
         * deliberate "come back and finish the job" marker, not a claim that the constraint should
         * never change.
         */
        @Test
        @DisplayName("chk_appointment_source still excludes STAFF, which is why this track is single-service")
        void should_stillExcludeStaffFromAppointmentSource_when_readingTheLiveConstraint() {
            String definition = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_appointment_source'",
                    String.class);

            assertThat(definition).contains("'APP'").contains("'LINK'");
            assertThat(definition)
                    .as("if this now admits STAFF, revisit StaffBookingCommand's scalar masterServiceId")
                    .doesNotContain("STAFF");
        }

        @Test
        @DisplayName("a staff booking never joins an appointment, so the constraint above is never reached")
        void should_leaveAppointmentIdNull_when_staffBookingPersisted() {
            BookingResponse response = create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            assertThat(bookingRow(response.id()).get("appointment_id")).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
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
            BookingResponse sibling = create(salon, command(salon, kyiv(TODAY, 9, 0), salon.salonId()));
            Map<String, Object> siblingBefore = bookingRow(sibling.id());

            create(salon, command(salon, kyiv(TODAY, 12, 0), salon.salonId()));

            assertThat(bookingRow(sibling.id())).isEqualTo(siblingBefore);
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
        UUID serviceDefId = UUID.randomUUID();
        jdbc.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, "
                        + "updated_at) VALUES (?, ?, ?, 'Манікюр', ?, ?, ?, 0, true, NOW(), NOW())",
                serviceDefId, ownerType, ownerId, resolveServiceTypeId(), DURATION_MINUTES, PRICE);
        UUID masterServiceId = UUID.randomUUID();
        jdbc.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, true, NOW(), NOW())", masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /** V111 made {@code service_definitions.service_type_id} NOT NULL — resolve a real, selectable one. */
    private UUID resolveServiceTypeId() {
        return jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
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
        return new StaffBookingCommand(scope, seed.masterId(), seed.masterServiceId(),
                startsAt, walkIn());
    }

    /**
     * The acting staff user is an explicit ARGUMENT, never a command field — so Phase 22.4's
     * {@code request.toCommand()} mapping cannot reach {@code bookings.created_by_user_id}.
     */
    private BookingResponse create(Seed seed, StaffBookingCommand cmd) {
        return createAs(seed.staffUserId(), cmd);
    }

    /**
     * Names the acting user independently of the seed, so a {@link StaffBookingScope.Self} scope can
     * be aimed at a master the actor is NOT (security MEDIUM, 2026-08-18) — the combination
     * {@code create(Seed, …)} can never produce for an independent master, whose seed makes
     * {@code staffUserId} and {@code masterUserId} the same id.
     */
    private BookingResponse createAs(UUID actorId, StaffBookingCommand cmd) {
        return staffBookingService.createStaffBooking(cmd, actorId);
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

    private int bookingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class);
    }
}
