package com.beautica.booking.service;

import com.beautica.booking.dto.AppointmentProviderNoteRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.OverrideConflictCandidate;
import com.beautica.common.TimeZones;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.dto.OverrideConflictPreviewResponse;
import com.beautica.master.dto.OverrideConflictResponse;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.ScheduleDateMath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests (Mockito) for {@link ScheduleOverrideConflictService} — the 2026-07-26 "schedule
 * override over existing bookings" orchestrator. Every collaborator is mocked; the calculator
 * itself ({@link com.beautica.master.service.ScheduleConflictCalculator}) is exercised directly by
 * its own test, not re-verified here.
 */
class ScheduleOverrideConflictServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private BookingRepository bookingRepository;
    private BookingService bookingService;
    private AppointmentTransitionService appointmentTransitionService;
    private MasterScheduleService masterScheduleService;
    private MasterCachePrefixEvictor cachePrefixEvictor;
    private ScheduleDateMath dateMath;
    private ScheduleOverrideConflictService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        bookingService = mock(BookingService.class);
        appointmentTransitionService = mock(AppointmentTransitionService.class);
        masterScheduleService = mock(MasterScheduleService.class);
        cachePrefixEvictor = mock(MasterCachePrefixEvictor.class);
        dateMath = mock(ScheduleDateMath.class);
        // Fixed well before DATE so every candidate's startsAt >= now, regardless of clock re-zoning.
        Clock clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
        service = new ScheduleOverrideConflictService(
                bookingRepository, bookingService, appointmentTransitionService, masterScheduleService,
                cachePrefixEvictor, dateMath, clock);
        // Every write-path test needs the advisory lock to succeed (finding 2) — a non-null return
        // means "acquired". Not relevant to the authorization-failure test below (the lock is never
        // even attempted once authz throws), so a lenient default here is safe.
        when(bookingRepository.acquireAdvisoryLockWithTimeout(any())).thenReturn(1);
    }

    private static OffsetDateTime kyiv(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    private static OverrideConflictCandidate standaloneCandidate(UUID bookingId, LocalTime start, LocalTime end) {
        return new OverrideConflictCandidate(
                bookingId, null, kyiv(DATE, start), kyiv(DATE, end),
                "Olena", "Kovalenko", null, null, "Manicure");
    }

    private static OverrideConflictCandidate appointmentChildCandidate(
            UUID bookingId, UUID appointmentId, LocalTime start, LocalTime end) {
        return new OverrideConflictCandidate(
                bookingId, appointmentId, kyiv(DATE, start), kyiv(DATE, end),
                null, null, "Ihor", "Petrenko", "Haircut");
    }

    // ── previewConflicts ───────────────────────────────────────────────────────

    @Test
    void should_returnEmptyList_when_noBookingsConflictWithIntendedOverride() {
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(standaloneCandidate(UUID.randomUUID(), LocalTime.of(10, 0), LocalTime.of(11, 0))));

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                List.of(new com.beautica.master.dto.WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0))),
                null);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.totalCount()).isZero();
        assertThat(result.truncated()).isFalse();
        verify(masterScheduleService).requireScheduleManagementAuthority(ACTOR_ID, MASTER_ID);
        verify(dateMath).assertExpandable(DATE, DATE);
    }

    @Test
    void should_returnConflict_when_dayOffOrphansAConfirmedBooking() {
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(standaloneCandidate(bookingId, LocalTime.of(10, 0), LocalTime.of(11, 0))));

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, null, null);

        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        OverrideConflictResponse conflict = result.conflicts().get(0);
        assertThat(conflict.bookingId()).isEqualTo(bookingId);
        assertThat(conflict.appointmentId()).isNull();
        assertThat(conflict.date()).isEqualTo(DATE);
        assertThat(conflict.clientDisplayName()).isEqualTo("Olena Kovalenko");
        assertThat(conflict.serviceName()).isEqualTo("Manicure");
    }

    @Test
    void should_useGuestName_when_conflictingBookingIsAppointmentChildWithNoRegisteredClient() {
        UUID bookingId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(appointmentChildCandidate(bookingId, appointmentId, LocalTime.of(9, 0), LocalTime.of(9, 30))));

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, null, null);

        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.conflicts().get(0).appointmentId()).isEqualTo(appointmentId);
        assertThat(result.conflicts().get(0).clientDisplayName()).isEqualTo("Ihor Petrenko");
    }

    @Test
    void should_truncateAndFlag_when_conflictCountExceedsPreviewCap() {
        List<OverrideConflictCandidate> many = java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> standaloneCandidate(UUID.randomUUID(),
                        LocalTime.of(0, 0).plusMinutes(i), LocalTime.of(0, 1).plusMinutes(i)))
                .toList();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(many);

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, null, null);

        assertThat(result.totalCount())
                .as("the true count must always be reported, even past the cap")
                .isEqualTo(501);
        assertThat(result.conflicts())
                .as("the returned list must be capped at the service's result limit")
                .hasSize(500);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void should_throwForbidden_when_previewAuthorizationFails() {
        when(masterScheduleService.requireScheduleManagementAuthority(any(), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        assertThatThrownBy(() -> service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, null, null))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(bookingRepository, dateMath);
    }

    // ── applyOverrideWithConflictHandling ──────────────────────────────────────

    @Test
    void should_writeOverrideWithoutDeclining_when_noConflicts() {
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of());
        var request = new ScheduleOverrideRequest(DATE, ScheduleExceptionKind.DAY_OFF, List.of());
        var expected = new ScheduleOverrideResponse(DATE, ScheduleExceptionKind.DAY_OFF, List.of());
        when(masterScheduleService.upsertOverride(ACTOR_ID, MASTER_ID, request)).thenReturn(expected);

        ScheduleOverrideResponse response = service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request);

        assertThat(response).isSameAs(expected);
        verify(masterScheduleService).requireScheduleManagementAuthority(ACTOR_ID, MASTER_ID);
        verify(bookingRepository).acquireAdvisoryLockWithTimeout(MASTER_ID);
        verify(masterScheduleService).upsertOverride(ACTOR_ID, MASTER_ID, request);
        verifyNoInteractions(bookingService, appointmentTransitionService);
        verifyNoInteractions(cachePrefixEvictor);
    }

    @Test
    void should_throwConflict_when_conflictsExistAndCancelOverlappingIsFalse() {
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(standaloneCandidate(UUID.randomUUID(), LocalTime.of(10, 0), LocalTime.of(11, 0))));
        var request = new ScheduleOverrideRequest(DATE, ScheduleExceptionKind.DAY_OFF, List.of());

        assertThatThrownBy(() -> service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request))
                .isInstanceOf(BusinessException.class);

        verify(masterScheduleService, never()).upsertOverride(any(), any(), any());
        verifyNoInteractions(bookingService, appointmentTransitionService);
    }

    @Test
    void should_writeOverrideAndDeclineStandaloneBookingWithoutAnyNote_when_conflictsExistAndCancelOverlappingIsTrue() {
        // D2 REVISED (2026-07-26 product decision reversal): a master taking a day-off/pause gives
        // no reason at all — there is no providerComment field on ScheduleOverrideRequest anymore,
        // and the StatusUpdateRequest handed to declineBookingForBatch must always carry a null
        // comment, never whatever free text a caller might otherwise have supplied.
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(standaloneCandidate(bookingId, LocalTime.of(10, 0), LocalTime.of(11, 0))));
        var request = new ScheduleOverrideRequest(
                DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, List.of(), null, true);
        var expected = new ScheduleOverrideResponse(DATE, ScheduleExceptionKind.DAY_OFF, List.of());
        when(masterScheduleService.upsertOverride(ACTOR_ID, MASTER_ID, request)).thenReturn(expected);

        ScheduleOverrideResponse response = service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request);

        assertThat(response).isSameAs(expected);
        ArgumentCaptor<StatusUpdateRequest> captor = ArgumentCaptor.forClass(StatusUpdateRequest.class);
        verify(bookingService).declineBookingForBatch(eq(ACTOR_ID), eq(bookingId), captor.capture());
        assertThat(captor.getValue().cancellationReason()).isEqualTo(CancellationReason.PROVIDER_UNAVAILABLE);
        assertThat(captor.getValue().comment()).isNull();
        verify(bookingService, never()).declineBooking(any(), any(), any());
        verifyNoInteractions(appointmentTransitionService);
    }

    @Test
    void should_declineViaAppointmentTransitionService_when_conflictIsAnAppointmentChild() {
        UUID bookingId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(appointmentChildCandidate(bookingId, appointmentId, LocalTime.of(9, 0), LocalTime.of(9, 30))));
        var request = new ScheduleOverrideRequest(
                DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, List.of(), null, true);
        when(masterScheduleService.upsertOverride(ACTOR_ID, MASTER_ID, request))
                .thenReturn(new ScheduleOverrideResponse(DATE, ScheduleExceptionKind.DAY_OFF, List.of()));

        service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request);

        ArgumentCaptor<AppointmentProviderNoteRequest> captor =
                ArgumentCaptor.forClass(AppointmentProviderNoteRequest.class);
        verify(appointmentTransitionService).declineAppointmentItems(
                eq(ACTOR_ID), eq(appointmentId), eq(List.of(bookingId)), captor.capture(), eq(false));
        assertThat(captor.getValue().providerComment()).isNull();
        verifyNoInteractions(bookingService);
    }

    @Test
    void should_groupSiblingConflicts_when_multipleConflictsShareSameAppointment() {
        // The O(K²) regression guard (perf finding 1): K=3 conflicting siblings of the SAME
        // appointment must be declined through exactly ONE declineAppointmentItems call carrying
        // all three booking ids, never three separate calls.
        UUID appointmentId = UUID.randomUUID();
        UUID booking1 = UUID.randomUUID();
        UUID booking2 = UUID.randomUUID();
        UUID booking3 = UUID.randomUUID();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(
                        appointmentChildCandidate(booking1, appointmentId, LocalTime.of(9, 0), LocalTime.of(9, 30)),
                        appointmentChildCandidate(booking2, appointmentId, LocalTime.of(9, 30), LocalTime.of(10, 0)),
                        appointmentChildCandidate(booking3, appointmentId, LocalTime.of(10, 0), LocalTime.of(10, 30))));
        var request = new ScheduleOverrideRequest(
                DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, List.of(), null, true);
        when(masterScheduleService.upsertOverride(ACTOR_ID, MASTER_ID, request))
                .thenReturn(new ScheduleOverrideResponse(DATE, ScheduleExceptionKind.DAY_OFF, List.of()));

        service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request);

        verify(appointmentTransitionService, org.mockito.Mockito.times(1)).declineAppointmentItems(
                eq(ACTOR_ID), eq(appointmentId), eq(List.of(booking1, booking2, booking3)), any(), eq(false));
    }

    @Test
    void should_notWriteOverride_when_authorizationFails() {
        var request = new ScheduleOverrideRequest(DATE, ScheduleExceptionKind.DAY_OFF, List.of());
        when(masterScheduleService.requireScheduleManagementAuthority(any(), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        assertThatThrownBy(() -> service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(bookingRepository, bookingService, appointmentTransitionService);
        verify(masterScheduleService, never()).upsertOverride(any(), any(), any());
    }

    @Test
    void should_throwInternalServerError_when_advisoryLockAcquisitionFails() {
        when(bookingRepository.acquireAdvisoryLockWithTimeout(MASTER_ID)).thenReturn(null);
        var request = new ScheduleOverrideRequest(DATE, ScheduleExceptionKind.DAY_OFF, List.of());

        assertThatThrownBy(() -> service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request))
                .isInstanceOf(BusinessException.class);

        verify(bookingRepository, never()).findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any());
        verify(masterScheduleService, never()).upsertOverride(any(), any(), any());
    }

    @Test
    void should_rejectWithConflict_when_conflictCountExceedsAbuseCap() {
        List<OverrideConflictCandidate> tooMany = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> standaloneCandidate(UUID.randomUUID(),
                        LocalTime.of(0, 0).plusMinutes(i), LocalTime.of(0, 1).plusMinutes(i)))
                .toList();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(tooMany);
        var request = new ScheduleOverrideRequest(
                DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, List.of(), null, true);

        assertThatThrownBy(() -> service.applyOverrideWithConflictHandling(ACTOR_ID, MASTER_ID, request))
                .isInstanceOf(BusinessException.class);

        verify(masterScheduleService, never()).upsertOverride(any(), any(), any());
        verifyNoInteractions(bookingService, appointmentTransitionService);
    }

    // NOTE: this class used to have a "decline-rate-limiter" test section here, proving
    // ScheduleOverrideConflictService charged a proportional per-conflict token from
    // BookingDeclineRateLimiter (backend-security re-audit finding 1). That charge — and
    // BookingDeclineRateLimiter itself — was removed on 2026-07-26 (D6, product decision
    // reversal): an override-driven decline no longer dispatches any notification, so there is no
    // longer an outbound-message blast radius to bound proportionally. This service no longer
    // participates in rate limiting at all; the route's ordinary destructive-bulk-write throttling
    // now lives entirely in BookingRateLimitFilter / RateLimitConfig#scheduleOverrideWriteBuckets
    // (see ScheduleOverrideConflictServiceTest's counterpart in BookingRateLimitFilterTest and the
    // real end-to-end coverage in MasterScheduleOverrideConflictIT).

    // ── candidate-scan cap (backend-perf re-audit finding 3) ─────────────────────

    @Test
    void should_capScanAndFlagScanTruncated_when_rawCandidateCountExceedsScanCap() {
        // MAX_CANDIDATES_SCANNED is package-private specifically so this test can pin the exact
        // boundary without hand-copying the literal.
        List<OverrideConflictCandidate> overCap = java.util.stream.IntStream
                .range(0, ScheduleOverrideConflictService.MAX_CANDIDATES_SCANNED + 1)
                .mapToObj(i -> standaloneCandidate(UUID.randomUUID(),
                        LocalTime.of(0, 0).plusMinutes(i % 1440), LocalTime.of(0, 1).plusMinutes(i % 1440)))
                .toList();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(overCap);

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, null, null);

        assertThat(result.scanTruncated())
                .as("the raw candidate scan itself hit MAX_CANDIDATES_SCANNED before every row could be loaded")
                .isTrue();
        assertThat(result.totalCount())
                .as("totalCount is now only a LOWER BOUND — computed over the capped candidate set")
                .isEqualTo(ScheduleOverrideConflictService.MAX_CANDIDATES_SCANNED);
    }

    @Test
    void should_notFlagScanTruncated_when_rawCandidateCountIsUnderScanCap() {
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(List.of(standaloneCandidate(UUID.randomUUID(), LocalTime.of(10, 0), LocalTime.of(11, 0))));

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, null, null);

        assertThat(result.scanTruncated()).isFalse();
    }

    @Test
    void should_notFlagScanTruncated_when_rawCandidateCountEqualsScanCapExactly() {
        // Off-by-one boundary companion to should_capScanAndFlagScanTruncated_when_rawCandidateCount
        // ExceedsScanCap (which pins CAP + 1). Exactly MAX_CANDIDATES_SCANNED rows is the last value
        // that must NOT trip scanTruncated — the repository's Pageable.of(0, CAP + 1) "limit-plus-one"
        // trick only signals truncation when the extra sentinel row actually comes back.
        List<OverrideConflictCandidate> exactlyAtCap = java.util.stream.IntStream
                .range(0, ScheduleOverrideConflictService.MAX_CANDIDATES_SCANNED)
                .mapToObj(i -> standaloneCandidate(UUID.randomUUID(),
                        LocalTime.of(0, 0).plusMinutes(i % 1440), LocalTime.of(0, 1).plusMinutes(i % 1440)))
                .toList();
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(exactlyAtCap);

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, null, null);

        assertThat(result.scanTruncated())
                .as("exactly MAX_CANDIDATES_SCANNED rows must NOT be flagged as scan-truncated")
                .isFalse();
        assertThat(result.totalCount())
                .as("every one of the exactly-at-cap candidates conflicts under DAY_OFF")
                .isEqualTo(ScheduleOverrideConflictService.MAX_CANDIDATES_SCANNED);
    }

    @Test
    void should_flagScanTruncatedButLeaveResultUntruncated_when_scanCapExceededButFewCandidatesActuallyConflict() {
        // Pins the independence of the two truncation signals (perf re-audit finding 3's whole
        // point): scanTruncated=true must NOT imply truncated=true. A raw scan that hits the
        // MAX_CANDIDATES_SCANNED cap only means the SCAN was capped — the vast majority of the
        // scanned rows may still fall inside the intended CUSTOM_HOURS/INTERVAL working hours and
        // never surface as a conflict at all, leaving the FILTERED conflict list well under the
        // separate MAX_PREVIEW_RESULTS (500) cap. Conflicting rows are placed FIRST in the returned
        // list so the scan-cap's prefix-truncation (see loadCandidates' subList) can only ever drop
        // "safe" rows, never one of the 11 real conflicts — keeping this test's arithmetic exact.
        List<OverrideConflictCandidate> conflicting = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> standaloneCandidate(UUID.randomUUID(), LocalTime.of(20, 0), LocalTime.of(20, 1)))
                .toList();
        List<OverrideConflictCandidate> safe = java.util.stream.IntStream
                .range(0, ScheduleOverrideConflictService.MAX_CANDIDATES_SCANNED - 10)
                .mapToObj(i -> standaloneCandidate(UUID.randomUUID(),
                        LocalTime.of(9, 0).plusMinutes(i % 50), LocalTime.of(9, 0).plusMinutes((i % 50) + 1)))
                .toList();
        List<OverrideConflictCandidate> allRows =
                java.util.stream.Stream.concat(conflicting.stream(), safe.stream()).toList();
        // Total raw rows = MAX_CANDIDATES_SCANNED + 1 — one past the scan cap.
        when(bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(any(), any(), any(), any()))
                .thenReturn(allRows);

        OverrideConflictPreviewResponse result = service.previewConflicts(
                ACTOR_ID, MASTER_ID, DATE, DATE, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                List.of(new com.beautica.master.dto.WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                null);

        assertThat(result.scanTruncated())
                .as("the raw scan hit MAX_CANDIDATES_SCANNED before every row could be loaded")
                .isTrue();
        assertThat(result.truncated())
                .as("scanTruncated must NOT imply truncated — only the 11 candidates outside "
                        + "[09:00,18:00) actually conflict, far under the 500-row preview cap")
                .isFalse();
        assertThat(result.totalCount())
                .as("totalCount is a lower bound (scanTruncated=true) but still exactly the 11 real "
                        + "conflicts among the capped candidate set — none of them was the row the "
                        + "scan cap's prefix-truncation dropped")
                .isEqualTo(11);
        assertThat(result.conflicts()).hasSize(11);
    }
}
