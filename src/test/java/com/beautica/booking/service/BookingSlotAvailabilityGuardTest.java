package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.service.entity.MasterServiceAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit pin for {@link BookingSlotAvailabilityGuard}'s <b>date normalisation</b> — the one line
 * ({@code startsAt.atZoneSameInstant(TimeZones.KYIV).toLocalDate()}) that decides WHICH day's slot list
 * the guard is answered from.
 *
 * <h2>The bug (backlog {@code docs/backend-phases/backlog.md:334}, closed 2026-08-11)</h2>
 * The guard used to key the availability read off {@code startsAt.toLocalDate()} — the date in whatever
 * offset the CLIENT happened to send. Every availability read downstream is scoped by the KYIV civil
 * date ({@code SlotCalculationService} resolves the effective day, loads that day's bookings and
 * generates candidates entirely in {@link TimeZones#KYIV}), so the two disagreed for any instant whose
 * Kyiv date and sent-offset date differ — and a mobile client that serialises in UTC produces exactly
 * that for every start between Kyiv midnight and 02:00/03:00. The guard then queried the PREVIOUS day's
 * slot list, found no match, and returned a spurious {@code 409 "Slot not available"} for a time the
 * master genuinely worked.
 *
 * <h2>Why these tests are shaped this way</h2>
 * Asserting only the CAPTURED {@code LocalDate} would pin the argument but not the behaviour. Each test
 * therefore stubs the two candidate days ASYMMETRICALLY — the Kyiv day carries the slot, the sent-offset
 * day is explicitly EMPTY — so the pre-fix implementation reads the empty list and throws the very
 * {@code 409 "Slot not available"} the backlog row describes, rather than dying on a strict-stub
 * mismatch. The wrong-day stub is {@code lenient()} exactly so the RED surfaces as production behaviour;
 * the captor assertion then names the offending date.
 *
 * <p>Deliberately a Mockito unit test, not a {@code @SpringBootTest}: the guard is a static utility over
 * an injected {@link SlotCalculationService}, so a mocked slot oracle is the complete collaborator set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingSlotAvailabilityGuard — the availability read is scoped by the KYIV civil date")
class BookingSlotAvailabilityGuardTest {

    /** 00:30 Kyiv on 2026-08-18 (EEST, UTC+3) — the instant a UTC-serialising client sends as 21:30Z. */
    private static final OffsetDateTime JUST_AFTER_KYIV_MIDNIGHT_AS_UTC =
            OffsetDateTime.parse("2026-08-17T21:30:00Z");
    private static final LocalDate KYIV_DAY = LocalDate.of(2026, 8, 18);
    private static final LocalDate SENT_OFFSET_DAY = LocalDate.of(2026, 8, 17);

    @Mock
    private SlotCalculationService slotCalculationService;

    @Captor
    private ArgumentCaptor<LocalDate> dateCaptor;

    private final UUID masterId = UUID.randomUUID();
    private final UUID masterServiceId = UUID.randomUUID();

    /**
     * The already-resolved assignment a CREATE caller hands the guard (Perf MEDIUM, 2026-08-11) — passed
     * through opaquely and asserted on every read below, so the shortcut cannot be silently dropped or
     * swapped for a different entity. It is deliberately a bare mock: the guard never dereferences it (the
     * verification of "is this really this request's assignment" lives in {@code SlotCalculationService},
     * behind its own tests), it only forwards it. The RESCHEDULE callers pass {@code null} here instead —
     * that shape is exercised end-to-end by the reschedule ITs.
     */
    @Mock
    private MasterServiceAssignment preloaded;

    /** One slot at exactly {@code startsAt}, expressed in Kyiv time as the real oracle would. */
    private static List<AvailableSlotResponse> slotAt(OffsetDateTime startsAt) {
        return List.of(new AvailableSlotResponse(
                startsAt.atZoneSameInstant(TimeZones.KYIV),
                startsAt.plusMinutes(60).atZoneSameInstant(TimeZones.KYIV)));
    }

    // ── single-service create/reschedule guard ──────────────────────────────────

    @Test
    @DisplayName("single-service — a 00:30 Kyiv start sent as a UTC instant (21:30Z the previous date) is "
            + "checked against the 2026-08-18 slot list, not 2026-08-17")
    void should_readTheKyivCivilDay_when_startIsJustAfterKyivMidnightSentAsUtc() {
        // Asymmetric on purpose: only the KYIV day knows about this slot. The sent-offset date is stubbed
        // EMPTY (leniently — it must go unused on the fixed code), so reading it reproduces the exact
        // production symptom this fix removes: a spurious 409 for a time the master genuinely works.
        when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, masterServiceId, preloaded))
                .thenReturn(slotAt(JUST_AFTER_KYIV_MIDNIGHT_AS_UTC));
        lenient().when(slotCalculationService.getAvailableSlots(
                        masterId, SENT_OFFSET_DAY, masterServiceId, preloaded))
                .thenReturn(List.of());

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, preloaded,
                JUST_AFTER_KYIV_MIDNIGHT_AS_UTC))
                .as("00:30 Kyiv IS on the master's 2026-08-18 slot list — the offset the client chose to "
                        + "serialise it in must not change the answer")
                .doesNotThrowAnyException();

        verify(slotCalculationService)
                .getAvailableSlots(eq(masterId), dateCaptor.capture(), eq(masterServiceId), eq(preloaded));
        assertThat(dateCaptor.getValue())
                .as("the availability read must be scoped by the KYIV civil date; sent-offset date was %s",
                        JUST_AFTER_KYIV_MIDNIGHT_AS_UTC.toLocalDate())
                .isEqualTo(KYIV_DAY)
                .isNotEqualTo(SENT_OFFSET_DAY);
    }

    @Test
    @DisplayName("single-service — a 23:30 Kyiv start sent from an EASTERN offset (05:30+09:00 the NEXT "
            + "date) is checked against the 2026-08-18 slot list, not 2026-08-19")
    void should_readTheKyivCivilDay_when_startIsLateKyivEveningSentFromAnEasternOffset() {
        // The mirror direction: here the sent-offset date runs AHEAD of the Kyiv date. Both directions
        // matter — the DTO accepts any OffsetDateTime, so the guard must never trust the sent offset.
        OffsetDateTime lateKyivEvening = OffsetDateTime.parse("2026-08-19T05:30:00+09:00");
        assertThat(lateKyivEvening.toLocalDate())
                .as("guard: the fixture only bites if the sent-offset date really differs from the Kyiv one")
                .isEqualTo(LocalDate.of(2026, 8, 19));

        when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, masterServiceId, preloaded))
                .thenReturn(slotAt(lateKyivEvening));
        lenient().when(slotCalculationService.getAvailableSlots(
                        masterId, LocalDate.of(2026, 8, 19), masterServiceId, preloaded))
                .thenReturn(List.of());

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, preloaded, lateKyivEvening))
                .as("23:30 Kyiv on 2026-08-18 — reading the sent-offset date 2026-08-19 finds nothing")
                .doesNotThrowAnyException();

        verify(slotCalculationService)
                .getAvailableSlots(eq(masterId), dateCaptor.capture(), eq(masterServiceId), eq(preloaded));
        assertThat(dateCaptor.getValue()).isEqualTo(KYIV_DAY);
    }

    @Test
    @DisplayName("single-service — 409 \"Slot not available\" when the KYIV day genuinely offers no "
            + "matching start (the normalisation must not turn the guard into a no-op)")
    void should_throw409_when_theKyivDayOffersNoMatchingStart() {
        // Non-vacuity for the two tests above: normalising the date must still leave a guard that
        // REJECTS. A slot one hour away is on the right day and still no match.
        when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, masterServiceId, preloaded))
                .thenReturn(slotAt(JUST_AFTER_KYIV_MIDNIGHT_AS_UTC.plusHours(1)));

        assertThatThrownBy(() -> BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, preloaded,
                JUST_AFTER_KYIV_MIDNIGHT_AS_UTC))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ── multi-service (BE-2) visit guard ────────────────────────────────────────

    @Test
    @DisplayName("multi-service visit — the ordered-list overload normalises to the KYIV civil date too "
            + "(both call sites share one #kyivDate)")
    void should_readTheKyivCivilDay_when_visitStartIsJustAfterKyivMidnightSentAsUtc() {
        List<UUID> serviceIds = List.of(masterServiceId, UUID.randomUUID());
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, serviceIds, preloadedChain))
                .thenReturn(slotAt(JUST_AFTER_KYIV_MIDNIGHT_AS_UTC));
        lenient().when(slotCalculationService.getAvailableSlots(
                        masterId, SENT_OFFSET_DAY, serviceIds, preloadedChain))
                .thenReturn(List.of());

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain,
                JUST_AFTER_KYIV_MIDNIGHT_AS_UTC))
                .as("POST /appointments and the LINK visit create reach this overload — a UTC-serialised "
                        + "post-midnight start must not 409 there either")
                .doesNotThrowAnyException();

        verify(slotCalculationService)
                .getAvailableSlots(eq(masterId), dateCaptor.capture(), eq(serviceIds), eq(preloadedChain));
        assertThat(dateCaptor.getValue())
                .as("the visit overload must not be left on the old startsAt.toLocalDate() reading")
                .isEqualTo(KYIV_DAY)
                .isNotEqualTo(SENT_OFFSET_DAY);
    }

    // ── STAFF whole-chain visit guard (Phase 22.10) ──────────────────────────────

    /** "Now" for the STAFF 0-minute-floor tests — an ordinary Kyiv instant, no offset trickery. */
    /** Same Kyiv civil date as {@link #KYIV_DAY}, so it can share stubs keyed on that date. */
    private static final OffsetDateTime NOW_KYIV = OffsetDateTime.parse("2026-08-18T11:00:00+03:00");

    private final UUID secondMasterServiceId = UUID.randomUUID();

    @Test
    @DisplayName("staff visit — accepted when the chain starts on a slot and the whole block fits the "
            + "working window")
    void should_pass_when_staffVisitStartsOnGridAndChainFitsWorkingWindow() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        when(slotCalculationService.isStaffVisitSlotAvailable(
                masterId, KYIV_DAY, serviceIds, preloadedChain, NOW_KYIV))
                .thenReturn(true);

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain, NOW_KYIV))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("staff visit — 409 when the chain's first start is off the slot grid")
    void should_throw409_when_staffVisitFirstStartOffGrid() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        when(slotCalculationService.isStaffVisitSlotAvailable(
                masterId, KYIV_DAY, serviceIds, preloadedChain, NOW_KYIV))
                .thenReturn(false);

        assertThatThrownBy(() -> BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain, NOW_KYIV))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * D2's fatal case, pinned at the guard boundary: item 0 fits ALONE, but the ORACLE the guard must
     * call — {@code isStaffVisitSlotAvailable}, which judges the whole Σ-duration block — says the chain
     * does not fit. A guard mutated to ask a per-item check on item 0 instead (e.g.
     * {@code isStaffSlotAvailable(..., masterServiceId, ...)}) would find the item-0 stub answering
     * {@code true} and wrongly accept — the exact defect D2 exists to rule out.
     *
     * <p><b>Mutation-check RED</b>: swap the guard's call for a single-service
     * {@code isStaffSlotAvailable} check on item 0 and this test goes RED (see the falsification note in
     * the phase report).
     */
    @Test
    @DisplayName("staff visit — 409 when the chain overruns the working window, though the first "
            + "service fits alone (the whole-chain case N per-item checks would wrongly accept)")
    void should_throw409_when_chainOverrunsWorkingWindow_thoughFirstServiceFitsAlone() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        // Item 0 fits alone — a per-item check on it would wrongly pass.
        lenient().when(slotCalculationService.isStaffSlotAvailable(
                        eq(masterId), eq(KYIV_DAY), eq(masterServiceId), eq(preloaded), eq(NOW_KYIV)))
                .thenReturn(true);
        // The whole Σ-duration chain does not fit — the correct oracle for a chained visit.
        when(slotCalculationService.isStaffVisitSlotAvailable(
                masterId, KYIV_DAY, serviceIds, preloadedChain, NOW_KYIV))
                .thenReturn(false);

        assertThatThrownBy(() -> BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain, NOW_KYIV))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * The 0-minute STAFF floor, pinned by proving the guard calls the STAFF oracle and never the
     * CLIENT-floor {@code assertVisitStartsOnAvailableSlot} path (which asks
     * {@code getAvailableSlots(...)} and would reject a start inside the 15-minute window). The client
     * oracle is stubbed to answer EMPTY — the pre-fix / mutated behaviour — so if the guard were rewritten
     * to delegate to {@code assertVisitStartsOnAvailableSlot} this test goes RED on a spurious 409,
     * exactly the mutation-check the phase doc calls for.
     */
    @Test
    @DisplayName("staff visit — a chain starting exactly at \"now\" is accepted (0-minute STAFF floor) "
            + "even where the 15-minute CLIENT floor would reject it")
    void should_pass_when_startIsNowExactly() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        when(slotCalculationService.isStaffVisitSlotAvailable(
                masterId, KYIV_DAY, serviceIds, preloadedChain, NOW_KYIV))
                .thenReturn(true);
        // The CLIENT-floor oracle finds nothing for the same instant — the 15-minute window a walk-in
        // keyed in right now would be rejected by, and the wrong path this test must NOT take.
        lenient().when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, serviceIds, preloadedChain))
                .thenReturn(List.of());

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain, NOW_KYIV))
                .as("STAFF floor is 0 minutes — \"now\" is a legal walk-in start")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("staff visit — 409 when the requested start has already passed")
    void should_throw409_when_startIsInThePast() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        OffsetDateTime aMinuteAgo = NOW_KYIV.minusMinutes(1);
        when(slotCalculationService.isStaffVisitSlotAvailable(
                masterId, KYIV_DAY, serviceIds, preloadedChain, aMinuteAgo))
                .thenReturn(false);

        assertThatThrownBy(() -> BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain, aMinuteAgo))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("staff visit — the availability read is scoped by the KYIV civil date, not the sent offset")
    void should_useKyivCivilDate_when_startSubmittedInAnotherOffset() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        when(slotCalculationService.isStaffVisitSlotAvailable(
                eq(masterId), eq(KYIV_DAY), eq(serviceIds), eq(preloadedChain),
                eq(JUST_AFTER_KYIV_MIDNIGHT_AS_UTC)))
                .thenReturn(true);
        lenient().when(slotCalculationService.isStaffVisitSlotAvailable(
                        eq(masterId), eq(SENT_OFFSET_DAY), eq(serviceIds), eq(preloadedChain),
                        eq(JUST_AFTER_KYIV_MIDNIGHT_AS_UTC)))
                .thenReturn(false);

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain,
                JUST_AFTER_KYIV_MIDNIGHT_AS_UTC))
                .doesNotThrowAnyException();

        verify(slotCalculationService).isStaffVisitSlotAvailable(
                eq(masterId), dateCaptor.capture(), eq(serviceIds), eq(preloadedChain),
                eq(JUST_AFTER_KYIV_MIDNIGHT_AS_UTC));
        assertThat(dateCaptor.getValue())
                .isEqualTo(KYIV_DAY)
                .isNotEqualTo(SENT_OFFSET_DAY);
    }

    @Test
    @DisplayName("staff visit — the caller's preloaded assignment list is forwarded, not reloaded")
    void should_passPreloadedAssignmentsThrough_when_plannerAlreadyResolvedThem() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        List<MasterServiceAssignment> preloadedChain =
                List.of(preloaded, mock(MasterServiceAssignment.class));
        when(slotCalculationService.isStaffVisitSlotAvailable(
                masterId, KYIV_DAY, serviceIds, preloadedChain, NOW_KYIV))
                .thenReturn(true);

        BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, preloadedChain, NOW_KYIV);

        // Same list instance, element for element — VisitPlanner#planChainedItems's resolution is never
        // re-fetched behind the guard's back.
        verify(slotCalculationService)
                .isStaffVisitSlotAvailable(eq(masterId), eq(KYIV_DAY), eq(serviceIds), eq(preloadedChain),
                        eq(NOW_KYIV));
    }

    // ── ACTOR-DISPATCHING overloads (reschedule parity) ──────────────────────────
    //
    // The 6-arg overloads that route a RESCHEDULE to the staff oracle (initiatedByProvider) or the
    // client one. Nothing referenced these before: every provider-initiated reschedule test targets a
    // plusDays(2)-class time both floors accept, so routing them all to the client twin was
    // indistinguishable from the fix.
    //
    // Every test below stubs the two oracles ASYMMETRICALLY — the arm that must NOT be taken answers
    // "no such slot" (leniently, so it may legitimately go unused). A dispatch mutated to always take
    // one arm therefore surfaces as the production symptom (a spurious 409 / a wrongly accepted
    // start), never as a strict-stub mismatch.
    //
    // The reschedule callers hold no resolved assignment, so `preloaded` is null on both overloads
    // here — the exact shape BookingService#rescheduleBooking and
    // AppointmentTransitionService#reschedule{Appointment,AppointmentItem} pass.

    private static final boolean PROVIDER = true;
    private static final boolean CLIENT = false;

    @Test
    @DisplayName("single-service dispatch — a provider-initiated reschedule asks the STAFF oracle, so a "
            + "start inside the 15-minute window the client list omits is still accepted")
    void should_askStaffOracle_when_singleServiceRescheduleIsInitiatedByProvider() {
        when(slotCalculationService.isStaffSlotAvailable(masterId, KYIV_DAY, masterServiceId, null, NOW_KYIV))
                .thenReturn(true);
        // The client-floor oracle offers nothing for this instant — the pre-fix behaviour, and the
        // arm this call must NOT take.
        lenient().when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, masterServiceId, null))
                .thenReturn(List.of());

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, null, NOW_KYIV, PROVIDER))
                .as("routing a provider reschedule to the client oracle turns the relaxed floor into a "
                        + "409 \"Slot not available\" instead of a 400 — the two layers must move together")
                .doesNotThrowAnyException();

        verify(slotCalculationService).isStaffSlotAvailable(masterId, KYIV_DAY, masterServiceId, null, NOW_KYIV);
    }

    @Test
    @DisplayName("single-service dispatch — a CLIENT-initiated reschedule still asks the client oracle "
            + "and 409s on a start it does not offer (the dispatch is not a blanket relaxation)")
    void should_askClientOracle_when_singleServiceRescheduleIsInitiatedByClient() {
        when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, masterServiceId, null))
                .thenReturn(List.of());
        // The staff oracle WOULD accept — so a dispatch mutated to always take the staff arm passes
        // this start and this test goes red.
        lenient().when(slotCalculationService.isStaffSlotAvailable(
                        masterId, KYIV_DAY, masterServiceId, null, NOW_KYIV))
                .thenReturn(true);

        assertThatThrownBy(() -> BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, null, NOW_KYIV, CLIENT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("single-service dispatch — a provider-initiated reschedule the STAFF oracle rejects "
            + "still 409s (the relaxed arm is a floor swap, not a bypass)")
    void should_throw409_when_providerRescheduleIsRejectedByTheStaffOracle() {
        when(slotCalculationService.isStaffSlotAvailable(masterId, KYIV_DAY, masterServiceId, null, NOW_KYIV))
                .thenReturn(false);

        assertThatThrownBy(() -> BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, null, NOW_KYIV, PROVIDER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available");
    }

    @Test
    @DisplayName("whole-visit dispatch — a provider-initiated visit reschedule asks the STAFF chain "
            + "oracle, so a block starting inside the 15-minute window is accepted")
    void should_askStaffChainOracle_when_visitRescheduleIsInitiatedByProvider() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        when(slotCalculationService.isStaffVisitSlotAvailable(masterId, KYIV_DAY, serviceIds, null, NOW_KYIV))
                .thenReturn(true);
        lenient().when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, serviceIds, null))
                .thenReturn(List.of());

        assertThatCode(() -> BookingSlotAvailabilityGuard.assertVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, null, NOW_KYIV, PROVIDER))
                .as("AppointmentTransitionService#rescheduleAppointment reaches this overload — the "
                        + "whole-visit route must relax with the single-booking one")
                .doesNotThrowAnyException();

        verify(slotCalculationService).isStaffVisitSlotAvailable(masterId, KYIV_DAY, serviceIds, null, NOW_KYIV);
    }

    @Test
    @DisplayName("whole-visit dispatch — a CLIENT-initiated visit reschedule still asks the client chain "
            + "oracle and 409s on a start it does not offer")
    void should_askClientChainOracle_when_visitRescheduleIsInitiatedByClient() {
        List<UUID> serviceIds = List.of(masterServiceId, secondMasterServiceId);
        when(slotCalculationService.getAvailableSlots(masterId, KYIV_DAY, serviceIds, null))
                .thenReturn(List.of());
        lenient().when(slotCalculationService.isStaffVisitSlotAvailable(
                        masterId, KYIV_DAY, serviceIds, null, NOW_KYIV))
                .thenReturn(true);

        assertThatThrownBy(() -> BookingSlotAvailabilityGuard.assertVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, serviceIds, null, NOW_KYIV, CLIENT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
