package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.StaffBookingCommand;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.booking.dto.StaffClientRef;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.sms.SmsDeliveryException;
import com.beautica.notification.sms.SmsService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 22.2 — {@link StaffBookingService}, every branch.
 *
 * <p><b>{@link VisitPlanner} is REAL, not mocked</b> (wired over a mocked
 * {@link MasterServiceRepository}, exactly as {@code GuestBookingServiceTest} does). It is the
 * component that resolves the assignment and derives the price/duration/buffer snapshot, so mocking
 * it would delete the very behaviour the "snapshots match the client path" assertions exist to
 * prove, and would make the service-eligibility 404 untestable.
 *
 * <p><b>The clock is fixed at 2026-06-01T10:00Z</b> so "now", "the past" and "now + 1 min" are
 * exact rather than racy — the staff lead-time rule is entirely about that boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffBookingService — walk-in staff booking creation")
class StaffBookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");
    private static final String RAW_PHONE = "050 123 45 67";
    private static final String E164_PHONE = "+380501234567";
    private static final BigDecimal BASE_PRICE = new BigDecimal("350.00");

    /** The platform-curated {@code ServiceType.nameUk} — the ONLY service string the SMS may carry. */
    private static final String PLATFORM_SERVICE_NAME = "Манікюр";
    private static final int BASE_DURATION = 60;
    private static final int BUFFER = 15;

    @Mock private com.beautica.master.repository.MasterRepository masterRepository;
    @Mock private MasterServiceRepository masterServiceRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private com.beautica.booking.repository.AppointmentRepository appointmentRepository;
    @Mock private SlotCalculationService slotCalculationService;
    @Mock private SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    /**
     * Phase 22.7. Mocked at the INTERFACE, which is exactly how production sees it: the
     * {@code app.booking.sms.enabled} gate picks the implementation in {@code SmsConfig}, so this
     * service holds a real sender or a {@code NoOpSmsService} and cannot tell which. Nothing here
     * touches the flag, and that absence is asserted below.
     */
    @Mock private SmsService smsService;

    /**
     * Phase 22.14. Mocked, not wired real: {@code AppointmentService#enrich} is a full graph-to-DTO
     * mapper with its own collaborator ({@code DiscoveryLocationResolver}) and its own dedicated unit
     * suite — re-testing it here would duplicate coverage rather than isolate
     * {@link StaffBookingService}. What this suite proves is that {@code enrich} is CALLED, with the
     * header and the saved chain, not what it returns.
     */
    @Mock private AppointmentService appointmentService;

    private StaffBookingService service;

    /**
     * The REAL {@link BookingSmsDispatcher} over the mocked seam, driven by a {@link SyncTaskExecutor}.
     *
     * <p>Phase 22.7 hardening moved the send off the request thread, so the service now depends on
     * the dispatcher rather than on {@link SmsService} directly. Wiring the real dispatcher (rather
     * than mocking it) keeps every {@code verify(smsService)} assertion in this suite meaning what it
     * meant before — "this recipient, this body, actually reached the sender" — and additionally
     * covers the dispatcher's own swallow-the-failure contract, which a mock would delete. The
     * synchronous executor is the same stand-in {@code AsyncConfig} registers under the {@code test}
     * profile, so ordering is deterministic here for the same reason it is there.
     */
    private BookingSmsDispatcher bookingSmsDispatcher() {
        return new BookingSmsDispatcher(smsService, new SyncTaskExecutor());
    }

    private final UUID masterId = UUID.randomUUID();
    private final UUID masterServiceId = UUID.randomUUID();
    /** The 2nd and 3rd legs of a multi-service visit (Phase 22.12) — {@link MultiServiceVisit}. */
    private final UUID masterServiceId2 = UUID.randomUUID();
    private final UUID masterServiceId3 = UUID.randomUUID();
    private final UUID salonId = UUID.randomUUID();
    private final UUID masterUserId = UUID.randomUUID();
    private final UUID staffUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StaffBookingService(
                masterRepository, bookingRepository, appointmentRepository, slotCalculationService,
                salonCatalogCacheEvictor, new VisitPlanner(masterServiceRepository),
                bookingSmsDispatcher(), new BookingSmsProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC), appointmentService);
        // Lenient: rejection-path tests never reach the enrich() call, and MockitoExtension's
        // default STRICT_STUBS would otherwise flag this as an unnecessary stub for every one of
        // them. The return value is a placeholder — no test in this suite asserts on its content;
        // see the appointmentService field javadoc for why that assertion belongs to enrich's own
        // suite instead.
        lenient().when(appointmentService.enrich(any(), any())).thenReturn(stubAppointmentDetailResponse());
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Happy path & snapshots
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Walk-in create")
    class WalkInCreate {

        @Test
        @DisplayName("persists a CONFIRMED STAFF booking with the creating staff user recorded")
        void should_persistConfirmedStaffBooking_when_requestIsValid() {
            stubHappyPath(salonMaster(), assignment(null, null));

            AppointmentDetailResponse response = create(command(guest(RAW_PHONE)));

            Booking saved = captureSaved();
            assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(saved.getBookingSource()).isEqualTo(BookingSource.STAFF);
            assertThat(saved.getCreatedByUserId()).isEqualTo(staffUserId);
            assertThat(saved.getClient()).as("a walk-in has no account (V137 STAFF branch)").isNull();
            assertThat(saved.getCancelToken()).as("no guest self-cancel link for a staff booking").isNull();
            assertThat(response.status())
                    .as("the visit response comes straight from the mocked AppointmentService#enrich "
                            + "seam — see should_enrichWithHeaderAndSavedChain_when_visitCreated for "
                            + "the assertion that enrich is called with the real persisted state")
                    .isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("stores the guest identity with the phone normalised to E.164 before the insert")
        void should_normalisePhoneToE164_when_staffTypedItWithSpaces() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            Booking saved = captureSaved();
            assertThat(saved.getGuestName()).isEqualTo("Олена");
            assertThat(saved.getGuestSurname()).isEqualTo("Коваль");
            assertThat(saved.getGuestPhone())
                    .as("chk_bookings_guest_phone_format (V89) rejects the raw form at the DB level")
                    .isEqualTo(E164_PHONE)
                    .isNotEqualTo(RAW_PHONE);
        }

        @Test
        @DisplayName("freezes price/duration/buffer from the service definition when the assignment has no overrides")
        void should_snapshotBaseValues_when_assignmentHasNoOverrides() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            Booking saved = captureSaved();
            assertThat(saved.getPriceAtBooking()).isEqualByComparingTo(BASE_PRICE);
            assertThat(saved.getDurationMinutesAtBooking()).isEqualTo(BASE_DURATION);
            assertThat(saved.getBufferMinutesAtBooking()).isEqualTo(BUFFER);
            assertThat(saved.getEndsAt())
                    .as("endsAt = startsAt + duration + buffer, exactly as every other create path")
                    .isEqualTo(START.plusMinutes(BASE_DURATION + BUFFER));
        }

        @Test
        @DisplayName("the assignment's overrides beat the service definition's base values")
        void should_snapshotOverrides_when_assignmentDefinesThem() {
            stubHappyPath(salonMaster(), assignment(new BigDecimal("500.00"), 90));

            create(command(guest(RAW_PHONE)));

            Booking saved = captureSaved();
            assertThat(saved.getPriceAtBooking()).isEqualByComparingTo("500.00");
            assertThat(saved.getDurationMinutesAtBooking()).isEqualTo(90);
            assertThat(saved.getPriceMaxAtBooking())
                    .as("a priceOverride IS the agreed price — no RANGE ceiling survives it")
                    .isNull();
        }

        @Test
        @DisplayName("freezes the RANGE ceiling when the service is a genuine price band")
        void should_freezePriceCeiling_when_serviceIsRangePricedWithoutOverride() {
            MasterServiceAssignment msa = assignment(null, null);
            ReflectionTestUtils.setField(msa.getServiceDefinition(), "priceType", PriceType.RANGE);
            ReflectionTestUtils.setField(msa.getServiceDefinition(), "priceMax", new BigDecimal("600.00"));
            stubHappyPath(salonMaster(), msa);

            create(command(guest(RAW_PHONE)));

            assertThat(captureSaved().getPriceMaxAtBooking()).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("evicts the master's availability caches and the salon catalogue after the write")
        void should_evictAvailabilityCaches_when_bookingPersisted() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
            verify(salonCatalogCacheEvictor).evict(salonId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Independent master — the nullable-salonId amendment
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Independent master (no salon)")
    class IndependentMaster {

        @Test
        @DisplayName("persists with a null salon under a Self scope — no NPE, no 'salon required' branch")
        void should_persist_when_scopeIsSelfAndMasterHasNoSalon() {
            stubHappyPath(independentMaster(), assignmentOf(independentMaster(), null, null));

            createAs(masterUserId, command(new StaffBookingScope.Self(masterUserId), guest(RAW_PHONE)));

            Booking saved = captureSaved();
            assertThat(saved.getSalon()).isNull();
            assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(saved.getCreatedByUserId())
                    .as("a Self booking is attributed to the master themselves")
                    .isEqualTo(masterUserId);
            verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
            verifyNoInteractions(salonCatalogCacheEvictor);
        }

        /**
         * The hole the sealed {@link StaffBookingScope} closes (security MEDIUM, 2026-08-18). The old
         * shape took a nullable {@code salonId} and {@code return}ed early on {@code null}, so an
         * independent-master command bought NO scoping at all — {@code masterId} was unconstrained
         * and this exact call persisted a booking on a stranger's calendar. There is no longer any
         * way to express "no scope": {@code Self} asserts the master's own user.
         */
        @Test
        @DisplayName("rejects a Self scope naming a master who is someone else — the unscoped-null hole")
        void should_reject403_when_scopeIsSelfAndMasterIsSomeoneElse() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(independentMaster()));

            // The scope names the ACTOR, so the actor cross-check passes and the master-vs-user
            // comparison is what refuses — the branch this case exists to pin.
            assertThatThrownBy(() -> create(
                    command(new StaffBookingScope.Self(staffUserId), guest(RAW_PHONE))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this account");

            verify(bookingRepository, never()).saveAll(any());
        }

        /**
         * <b>The relocated {@code createdByUserId} hazard</b> (security MEDIUM, 2026-08-18).
         * {@code Self} is a public record over an UNCONSTRAINED {@code UUID} and {@code scope} is a
         * component of {@code StaffBookingCommand} — the record carrying every client-supplied value
         * — so 22.4's natural {@code request.toCommand()} mapping is one field away from making the
         * scope body-sourced. Before the cross-check, this exact call booked a stranger's calendar:
         * the named user IS the target master's user, so the {@code master.getUser()} comparison
         * passed, and the row was attributed to the ATTACKER's {@code actorId}.
         *
         * <p>Note the fixture shape: the named user IS the loaded master's user, so the sibling
         * {@code master.getUser()} comparison would wave this through. Only the {@code actorId}
         * cross-check can produce the 403 — nothing downstream is even stubbed, and under strict
         * stubs that absence is itself the proof the refusal happens before the planner. The IT
         * counterpart runs the same shape past every remaining gate against a real database.
         */
        @Test
        @DisplayName("rejects a Self scope naming a user other than the acting one — body-sourced scope is inert")
        void should_reject403_when_selfScopeNamesAUserOtherThanTheActor() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(independentMaster()));

            assertThatThrownBy(() -> createAs(staffUserId,
                    command(new StaffBookingScope.Self(masterUserId), guest(RAW_PHONE))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this account");

            verify(bookingRepository, never()).saveAll(any());
        }

        /**
         * The case the previous suite never covered: {@code salonId == null} while the target master
         * DOES belong to a salon. Under the nullable-salonId shape this passed — a salon master's
         * calendar was bookable by anyone who simply omitted the salon. It is now a 403 because a
         * {@code Self} scope compares users, and this master's user is not the acting one.
         */
        @Test
        @DisplayName("rejects a Self scope aimed at a SALON master — the case the old null branch waved through")
        void should_reject403_when_scopeIsSelfAndMasterBelongsToASalon() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(salonMaster()));

            // Again the scope names the ACTOR, so the refusal is the master-vs-user comparison.
            assertThatThrownBy(() -> create(
                    command(new StaffBookingScope.Self(staffUserId), guest(RAW_PHONE))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this account");

            verify(bookingRepository, never()).saveAll(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Lead time — the STAFF rule: "now" is bookable, the past is not
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("STAFF lead time")
    class StaffLeadTime {

        @Test
        @DisplayName("accepts a start equal to now, which the shared ≥15-min client rule would reject")
        void should_accept_when_startsAtIsExactlyNow() {
            OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            stubStaffSlotAvailable(now);
            stubLockFreeAndSave();

            create(commandAt(now, new StaffBookingScope.InSalon(salonId), guest(RAW_PHONE)));

            assertThat(captureSaved().getStartsAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("rejects a start one minute in the past, before any lock or write")
        void should_reject400_when_startsAtIsInThePast() {
            // Only the master is stubbed: the lead-time guard runs BEFORE the assignment lookup, so a
            // rejected start never costs the service-resolution query. Mockito's strict stubbing is
            // what pins that ordering — stubbing the assignment here would fail as unnecessary.
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(salonMaster()));
            OffsetDateTime past = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(1);

            assertThatThrownBy(() -> create(commandAt(past, new StaffBookingScope.InSalon(salonId), guest(RAW_PHONE))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Booking cannot start in the past")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(bookingRepository, never()).acquireAdvisoryLockWithTimeout(any());
            verify(bookingRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("rejects a start beyond the 180-day horizon, which STAFF shares with every other path")
        void should_reject400_when_startsAtIsBeyondTheHorizon() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(salonMaster()));
            OffsetDateTime tooFar = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(181);

            assertThatThrownBy(() -> create(commandAt(tooFar, new StaffBookingScope.InSalon(salonId), guest(RAW_PHONE))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("180 days");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Rejections
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Rejections")
    class Rejections {

        @Test
        @DisplayName("rejects a start the master does not work (empty staff slot list) before taking the lock")
        void should_reject409_when_startIsOutsideWorkingHours() {
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            // Left at Mockito's `false` default — the master does not work then, so the zero-lead
            // oracle offers nothing at that instant.

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            verify(bookingRepository, never()).acquireAdvisoryLockWithTimeout(any());
            verify(bookingRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("rejects a window that collides with an existing CONFIRMED booking")
        void should_reject409_when_windowOverlapsAnExistingBooking() {
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            stubStaffSlotAvailable(START);
            when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
            when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available");

            verify(bookingRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("rejects an inactive master with the same 404 an unknown one gets")
        void should_reject404_when_masterIsInactive() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(inactiveMaster()));

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Master not found or inactive");
        }

        @Test
        @DisplayName("rejects a service the master does not perform (service eligibility)")
        void should_reject404_when_serviceIsNotAssignedToThatMaster() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(salonMaster()));
            when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Master service not found");
        }

        @Test
        @DisplayName("rejects a master that belongs to a different salon than the caller's")
        void should_reject403_when_masterBelongsToAnotherSalon() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(salonMaster()));

            assertThatThrownBy(() -> create(
                    command(new StaffBookingScope.InSalon(UUID.randomUUID()), guest(RAW_PHONE))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this salon");
        }

        /**
         * The {@code salon == null} half of the {@code InSalon} arm
         * ({@code StaffBookingService.java:237}) — a salon-scoped caller aiming at a SALON-LESS
         * independent master. The sibling case above only exercises the {@code !equals} half, so a
         * guard rewritten as {@code !inSalon.salonId().equals(salon.getId())} alone would NPE in
         * production (a 500 on a request that must be a 403) while every other case stayed green.
         */
        @Test
        @DisplayName("rejects an InSalon scope aimed at an independent master, who has no salon at all")
        void should_reject403_when_inSalonScopeTargetsASalonLessMaster() {
            when(masterRepository.findByIdWithUserAndSalon(masterId))
                    .thenReturn(Optional.of(independentMaster()));

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Master does not belong to this salon");

            verify(bookingRepository, never()).saveAll(any());
        }

        /**
         * {@code BookingSlotLockGuard#lockMasterAndAssertFree}'s null-lock branch — the one arm of
         * the guard shared verbatim with {@code GuestBookingService} that no staff case reached.
         * {@code pg_advisory_xact_lock} returning no row means the lock was never taken, so the
         * following {@code existsOverlap} would be a plain unsynchronised read: proceeding is worse
         * than failing, and the guard must abort rather than fall through to the insert.
         */
        @Test
        @DisplayName("aborts with 500 when the per-master advisory lock cannot be acquired, never writing")
        void should_reject500_when_advisoryLockIsNotAcquired() {
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            stubStaffSlotAvailable(START);
            when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(null);

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Advisory lock acquisition failed")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

            verify(bookingRepository, never()).existsOverlap(any(), any(), any());
            verify(bookingRepository, never()).saveAll(any());
        }

        /**
         * {@code BookingSlotLockGuard#saveOrConflict} — the {@code no_overlapping_bookings} GIST
         * EXCLUDE backstop behind the advisory lock. A racer that slips past both the lock and
         * {@code existsOverlap} is rejected by the database at flush, and that
         * {@code DataIntegrityViolationException} must surface as the SAME
         * {@code 409 "Slot not available"} the application check returns — otherwise the loser of a
         * genuine race gets a 500 and the caller can tell which layer refused it.
         *
         * <p>This is the deterministic stand-in for the two-thread staff-vs-client race: the staff
         * path shares this guard verbatim with the guest path, whose live race is pinned by
         * {@code GuestBookingConcurrencyIT}.
         */
        @Test
        @DisplayName("translates the GIST overlap constraint into the same 409 the application check returns")
        void should_reject409_when_theDatabaseRejectsTheInsertAsOverlapping() {
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            stubStaffSlotAvailable(START);
            when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
            when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(false);
            when(bookingRepository.saveAll(any()))
                    .thenThrow(new DataIntegrityViolationException("no_overlapping_bookings"));

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .as("a lost race must be a conflict, never the handler's 500 catch-all")
                    .isEqualTo(HttpStatus.CONFLICT);

            verifyNoInteractions(salonCatalogCacheEvictor);
            verify(slotCalculationService, never()).evictMasterAvailabilityCaches(any());
        }

        @Test
        @DisplayName("rejects an unparseable phone before any lock or write")
        void should_reject400_when_phoneIsNotAUkrainianNumber() {
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            stubStaffSlotAvailable(START);

            assertThatThrownBy(() -> create(command(guest("not-a-phone"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Invalid phone format");

            verify(bookingRepository, never()).acquireAdvisoryLockWithTimeout(any());
        }

        @Test
        @DisplayName("rejects an existing-platform-client subject with 501 (Phase 22.3, deferred)")
        void should_reject501_when_clientIsAnExistingPlatformClient() {
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            stubStaffSlotAvailable(START);

            assertThatThrownBy(() -> create(
                    command(new StaffClientRef.ExistingClient(UUID.randomUUID()))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.NOT_IMPLEMENTED);

            verify(bookingRepository, never()).saveAll(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Walk-in identity — all three fields required, rejected before any DB write
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Walk-in identity")
    class WalkInIdentity {

        @Test
        @DisplayName("rejects a missing first name")
        void should_reject400_when_guestNameIsMissing() {
            assertThatThrownBy(() -> new StaffClientRef.Guest(null, "Коваль", RAW_PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Client name is required");
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("rejects a blank surname")
        void should_reject400_when_guestSurnameIsBlank() {
            assertThatThrownBy(() -> new StaffClientRef.Guest("Олена", "   ", RAW_PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Client surname is required");
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("rejects a missing phone")
        void should_reject400_when_guestPhoneIsMissing() {
            assertThatThrownBy(() -> new StaffClientRef.Guest("Олена", "Коваль", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Client phone is required");
            verifyNoInteractions(bookingRepository);
        }

        /**
         * Parity with the OTP-verified LINK path, which has enforced
         * {@code @Pattern("^[^\\p{Cntrl}]*$")} on {@code name}/{@code surname} since BE-7
         * ({@code GuestBookingRequest}). The staff path is the LESS trusted of the two — no OTP, and
         * the PII belongs to a third party — so it must not be the weaker one: a CR/LF here would
         * persist and flow into Phase 22.7's SMS body (security MEDIUM, 2026-08-18).
         */
        @Test
        @DisplayName("rejects a name containing a control character, matching the LINK path's guard")
        void should_reject400_when_guestNameContainsControlCharacters() {
            assertThatThrownBy(() -> new StaffClientRef.Guest("Оле\nна", "Коваль", RAW_PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Client name must not contain control characters");

            assertThatThrownBy(() -> new StaffClientRef.Guest("Олена", "Ко\u0007валь", RAW_PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Client surname must not contain control characters");

            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("rejects a name longer than the guest_name column, rather than 500ing at flush")
        void should_reject400_when_guestNameExceedsColumnLength() {
            String tooLong = "я".repeat(StaffClientRef.Guest.MAX_NAME_LENGTH + 1);

            assertThatThrownBy(() -> new StaffClientRef.Guest(tooLong, "Коваль", RAW_PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must not exceed");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Attribution — the acting staff user is a parameter, not a command field
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Staff attribution")
    class StaffAttribution {

        /**
         * {@code bookings.created_by_user_id} is the ONLY attribution a staff booking carries, and
         * the column is NOT NULL. A missing principal is §B's 403, never a null reaching the insert
         * and surfacing as a constraint-violation 500.
         */
        @Test
        @DisplayName("rejects a create with no acting staff user before touching the database")
        void should_reject403_when_actorIdIsMissing() {
            assertThatThrownBy(() -> service.createStaffBooking(command(guest(RAW_PHONE)), null))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Invalid authentication context");

            verifyNoInteractions(masterRepository, bookingRepository);
        }

        @Test
        @DisplayName("records the ACTING user, which no command field can influence")
        void should_recordTheActingUser_when_bookingPersisted() {
            stubHappyPath(salonMaster(), assignment(null, null));
            UUID otherStaffUser = UUID.randomUUID();

            service.createStaffBooking(command(guest(RAW_PHONE)), otherStaffUser);

            assertThat(captureSaved().getCreatedByUserId())
                    .as("attribution follows the argument the security context supplies, nothing else")
                    .isEqualTo(otherStaffUser);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Walk-in confirmation SMS (Phase 22.7)
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * The service-side half of the 22.7 gate. What is asserted here is deliberately NOT "was an SMS
     * delivered" — that depends on which {@code SmsService} bean {@code SmsConfig} picked, which is
     * {@code SmsFeatureGateTest}'s subject. What is asserted is that this service always calls the
     * seam, with the right recipient and the right copy, and that a failure at the seam cannot
     * reach the caller.
     */
    @Nested
    @DisplayName("Walk-in confirmation SMS")
    class WalkInConfirmationSms {

        @Test
        @DisplayName("should_sendConfirmationToTheNormalisedGuestPhone_when_bookingCommitted")
        void should_sendConfirmationToTheNormalisedGuestPhone_when_bookingCommitted() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            // E.164, not the "050 123 45 67" the staff member typed — the SMS must reach the same
            // number the DB stored, or the client is told nothing and nobody notices.
            verify(smsService).send(eq(E164_PHONE), any(String.class));
        }

        /**
         * The custom service name is attacker-authored text and must never reach a branded SMS
         * (security LOW, 2026-08-19).
         *
         * <p>A walk-in confirmation goes to a Ukrainian number that never opted in — the recipient's
         * only signal that it is legitimate is the Beautica branding. {@code ServiceDefinition.name}
         * is 100 characters of free text a self-registered provider chooses
         * ({@code CreateServiceDefinitionRequest}: {@code @Size(max = 100)} plus a
         * no-control-characters {@code @Pattern}, and nothing else), and at the walk-in rate limit
         * that is hundreds of attacker-authored SMS an hour to distinct strangers once
         * {@code app.booking.sms.enabled} flips at release. {@code Placeholders#format} already stops
         * such a value becoming template MARKUP; it cannot stop the value from BEING the payload.
         *
         * <p>So the copy renders {@code ServiceType.nameUk}, which is platform-authored taxonomy.
         * Both halves are asserted: the platform name is present AND the hostile string is absent —
         * the second is the one that fails if someone "restores" the custom name alongside it.
         */
        @Test
        @DisplayName("should_usePlatformServiceTypeName_when_masterSetCustomServiceName")
        void should_usePlatformServiceTypeName_when_masterSetCustomServiceName() {
            String hostile = "УВАГА! Ваш запис скасовано, деталі: beautica-support.example/win";
            stubHappyPath(salonMaster(), assignmentWithCustomServiceName(hostile));

            create(command(guest(RAW_PHONE)));

            assertThat(captureSmsText())
                    .as("the branded SMS must carry the platform taxonomy name, never the "
                            + "provider's own string")
                    .contains(PLATFORM_SERVICE_NAME)
                    .doesNotContain(hostile)
                    .doesNotContain("beautica-support.example");
        }

        @Test
        @DisplayName("should_renderMasterServiceDateAndTimeInKyivCivilTime_when_sending")
        void should_renderMasterServiceDateAndTimeInKyivCivilTime_when_sending() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            assertThat(captureSmsText())
                    .contains("Марія Левченко")
                    .contains("Манікюр")
                    // START is 2026-06-10T12:00+03:00 — the client reads a wall clock, not the UTC
                    // instant the column stores.
                    .contains("10.06.2026")
                    .contains("12:00");
        }

        @Test
        @DisplayName("should_omitAnyCancelLink_when_sending")
        void should_omitAnyCancelLink_when_sending() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            // A STAFF booking has cancel_token = NULL (V137), so there is nothing to link to. Both
            // an actual URL and an unsubstituted placeholder would be defects a client would see.
            assertThat(captureSmsText())
                    .doesNotContain("http")
                    .doesNotContain("{cancelUrl}")
                    .doesNotContain("Скасувати: ");
        }

        @Test
        @DisplayName("should_stillReturnTheBooking_when_smsProviderFails")
        void should_stillReturnTheBooking_when_smsProviderFails() {
            stubHappyPath(salonMaster(), assignment(null, null));
            org.mockito.Mockito.doThrow(new SmsDeliveryException("provider down"))
                    .when(smsService).send(any(), any());

            AppointmentDetailResponse response = create(command(guest(RAW_PHONE)));

            // The booking is committed before the send is attempted; a vendor outage must not turn a
            // successful create into a failed request (GuestBookingService#sendConfirmationSms) — the
            // call returns normally (no exception propagated) and the row is CONFIRMED regardless.
            assertThat(response).isNotNull();
            assertThat(captureSaved().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("should_callTheSeamRegardless_when_theFeatureFlagIsOff")
        void should_callTheSeamRegardless_when_theFeatureFlagIsOff() {
            BookingSmsProperties properties = new BookingSmsProperties();
            properties.getSms().setEnabled(false);
            service = new StaffBookingService(
                    masterRepository, bookingRepository, appointmentRepository, slotCalculationService,
                    salonCatalogCacheEvictor, new VisitPlanner(masterServiceRepository),
                    bookingSmsDispatcher(), properties, Clock.fixed(NOW, ZoneOffset.UTC), appointmentService);
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            // Suppression is the BEAN's job, never this call site's. If someone adds an
            // `if (properties.isEnabled())` here, the gate stops being un-forgettable at the next
            // new call site — which is the whole reason 22.7 gated the seam instead of the callers.
            verify(smsService).send(eq(E164_PHONE), any(String.class));
        }

        /**
         * The byte-identical N = 1 golden string (D3, Phase 22.13) — widening the builder to accept
         * a service LIST must not perturb the single-service rendering by even one character.
         * {@code should_renderMasterServiceDateAndTimeInKyivCivilTime_when_sending} above already
         * checks the individual fragments; this pins the WHOLE string so any stray character (a
         * misplaced space, a changed line break) shows up here even if every fragment still matches.
         */
        @Test
        @DisplayName("should_renderByteIdenticalBody_when_singleService")
        void should_renderByteIdenticalBody_when_singleService() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            assertThat(captureSmsText()).isEqualTo(
                    "Beautica: Запис підтверджено!\n"
                            + "Марія Левченко, Манікюр\n"
                            + "10.06.2026 о 12:00\n\n"
                            + "Скасувати — за телефоном майстра.");
        }

        /**
         * D3: name the first service, count the rest — never the full list, and never one SMS per
         * service. A count-only assertion (verifying {@code smsService.send} was invoked exactly
         * once) would pass even with the pre-22.13 first-service-only text, since a 3-service visit
         * still sends exactly one message either way; only inspecting the BODY proves the copy
         * actually widened.
         */
        @Test
        @DisplayName("should_nameFirstServiceAndCountTheRest_when_threeServiceVisitCreated")
        void should_nameFirstServiceAndCountTheRest_when_threeServiceVisitCreated() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            assertThat(captureSmsText())
                    .as("first service named, plural-correct count of the remaining two")
                    .contains(PLATFORM_SERVICE_NAME + " та ще 2 послуги")
                    .as("the rendered date/time are the VISIT's own start (firstStart), unchanged "
                            + "by widening the builder to accept the whole chain")
                    .contains("10.06.2026")
                    .contains("12:00");
        }

        /**
         * The budget query is consulted ONCE per visit, never once per chained service — a per-item
         * call would be exactly the row-vs-visit confusion
         * {@code BookingRepository#countStaffWalkInVisitsForPhoneSince} exists to remove, restated at
         * the call-site level. The query's own row-vs-visit correctness is proved where the real SQL
         * runs: {@code V138WalkInPhoneIndexMigrationTest} and {@code WalkInBookingSmsEnabledIT}.
         */
        @Test
        @DisplayName("should_checkTheBudgetOnce_when_threeServiceVisitCreated")
        void should_checkTheBudgetOnce_when_threeServiceVisitCreated() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            verify(bookingRepository, org.mockito.Mockito.times(1))
                    .countStaffWalkInVisitsForPhoneSince(eq(E164_PHONE), any());
        }

        private String captureSmsText() {
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(smsService).send(eq(E164_PHONE), captor.capture());
            return captor.getValue();
        }
    }

    /**
     * The per-RECIPIENT SMS-spend cap (SEC MEDIUM, 2026-08-18) — the half no per-actor rate-limit
     * bucket can provide, because an attacker may hold several staff accounts and several masters.
     *
     * <p>Note what these rows are careful about: the count is taken on the NORMALISED phone (so the
     * two spellings of one number share a budget) and BEFORE the advisory lock (so a throttled
     * request never contends for it), and the 429 body names no phone number.
     */
    @Nested
    @DisplayName("Per-phone walk-in SMS budget")
    class PerPhoneSmsBudget {

        @Test
        @DisplayName("should_return429_when_thisPhoneAlreadyHitTheWalkInBudget")
        void should_return429_when_thisPhoneAlreadyHitTheWalkInBudget() {
            stubMasterAndAssignment(salonMaster(), assignment(null, null));
            stubStaffSlotAvailable(START);
            when(bookingRepository.countStaffWalkInVisitsForPhoneSince(eq(E164_PHONE), any())).thenReturn(5L);

            assertThatThrownBy(() -> create(command(guest(RAW_PHONE))))
                    .isInstanceOf(BusinessException.class)
                    .as("a 429 must not confirm to a prober that this number was booked recently")
                    .hasMessageNotContaining(E164_PHONE)
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            // Nothing was written and no SMS was attempted — and, critically, the per-master
            // advisory lock was never taken, so a throttled caller cannot queue contention on it.
            verify(bookingRepository, never()).save(any());
            verifyNoInteractions(smsService);
        }

        @Test
        @DisplayName("should_countTheNormalisedPhone_when_staffTypedItWithSpaces")
        void should_countTheNormalisedPhone_when_staffTypedItWithSpaces() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            // Counting "050 123 45 67" verbatim would let the same number be alternated between
            // spellings to buy a second budget — the cap must key on what the DB actually stores.
            verify(bookingRepository).countStaffWalkInVisitsForPhoneSince(eq(E164_PHONE), any());
        }

        @Test
        @DisplayName("should_allowTheCreate_when_thePhoneIsOneBelowTheBudget")
        void should_allowTheCreate_when_thePhoneIsOneBelowTheBudget() {
            stubHappyPath(salonMaster(), assignment(null, null));
            when(bookingRepository.countStaffWalkInVisitsForPhoneSince(eq(E164_PHONE), any())).thenReturn(4L);

            // The boundary in the admitting direction: 4 prior sends is inside the budget of 5, so a
            // strict-vs-non-strict comparison error would show up here rather than only in prod.
            assertThat(create(command(guest(RAW_PHONE))).status()).isEqualTo(BookingStatus.CONFIRMED);
            verify(smsService).send(eq(E164_PHONE), any(String.class));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Multi-service visits (Phase 22.12) — one header + N chained bookings
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-service visits")
    class MultiServiceVisit {

        /**
         * D2's locked decision, pinned at the unit tier: N = 1 is NOT special-cased, so even a
         * single-service command still produces an {@code appointments} header. Mutation-check RED
         * by reintroducing a {@code size == 1} short-circuit around the header build.
         */
        @Test
        @DisplayName("should_createOneHeaderAndOneBooking_when_singleService")
        void should_createOneHeaderAndOneBooking_when_singleService() {
            stubHappyPath(salonMaster(), assignment(null, null));

            create(command(guest(RAW_PHONE)));

            verify(appointmentRepository).save(any(Appointment.class));
            List<Booking> saved = captureSavedBookings();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getAppointment()).isNotNull();
        }

        @Test
        @DisplayName("should_createOneHeaderAndThreeBookings_when_threeServices")
        void should_createOneHeaderAndThreeBookings_when_threeServices() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            ArgumentCaptor<Appointment> appointmentCaptor = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentRepository).save(appointmentCaptor.capture());
            List<Booking> saved = captureSavedBookings();
            assertThat(saved).hasSize(3);
            assertThat(saved).allSatisfy(b -> assertThat(b.getAppointment()).isSameAs(appointmentCaptor.getValue()));
        }

        @Test
        @DisplayName("should_chainStartsAndEnds_when_multipleServices")
        void should_chainStartsAndEnds_when_multipleServices() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            List<Booking> saved = captureSavedBookings();
            assertThat(saved.get(0).getStartsAt()).isEqualTo(START);
            assertThat(saved.get(1).getStartsAt())
                    .as("item i starts exactly at item i-1's end")
                    .isEqualTo(saved.get(0).getEndsAt());
            assertThat(saved.get(2).getStartsAt()).isEqualTo(saved.get(1).getEndsAt());
        }

        /**
         * Mutation-check RED by removing {@code setAppointment} from the loop in
         * {@code StaffBookingService#createStaffBooking}.
         */
        @Test
        @DisplayName("should_linkEveryBookingToTheHeader_when_multipleServices")
        void should_linkEveryBookingToTheHeader_when_multipleServices() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            ArgumentCaptor<Appointment> appointmentCaptor = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentRepository).save(appointmentCaptor.capture());
            assertThat(captureSavedBookings())
                    .extracting(Booking::getAppointment)
                    .containsOnly(appointmentCaptor.getValue());
        }

        /**
         * Mutation-check RED by passing {@code items.get(0).endsAt()} as {@code to} — the ONE span
         * check must cover the whole chain, not just the first item.
         */
        @Test
        @DisplayName("should_lockAndCheckOverlapOverWholeSpan_when_multipleServices")
        void should_lockAndCheckOverlapOverWholeSpan_when_multipleServices() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            OffsetDateTime lastEnd = START.plusMinutes(3L * (BASE_DURATION + BUFFER));
            verify(bookingRepository).existsOverlap(eq(masterId), eq(START), eq(lastEnd));
        }

        @Test
        @DisplayName("should_callSlotGuardOnce_when_multipleServices")
        void should_callSlotGuardOnce_when_multipleServices() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            verify(slotCalculationService, org.mockito.Mockito.times(1)).isStaffVisitSlotAvailable(
                    eq(masterId), any(LocalDate.class), eq(List.of(masterServiceId, masterServiceId2, masterServiceId3)),
                    any(), eq(START));
        }

        @Test
        @DisplayName("should_stampCreatedByUserIdOnHeaderAndEveryBooking")
        void should_stampCreatedByUserIdOnHeaderAndEveryBooking() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            ArgumentCaptor<Appointment> appointmentCaptor = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentRepository).save(appointmentCaptor.capture());
            assertThat(appointmentCaptor.getValue().getCreatedByUserId()).isEqualTo(staffUserId);
            assertThat(captureSavedBookings())
                    .extracting(Booking::getCreatedByUserId)
                    .containsOnly(staffUserId);
        }

        @Test
        @DisplayName("should_evictSlotsOnce_when_multipleServices")
        void should_evictSlotsOnce_when_multipleServices() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            verify(slotCalculationService, org.mockito.Mockito.times(1)).evictMasterAvailabilityCaches(masterId);
            verify(salonCatalogCacheEvictor, org.mockito.Mockito.times(1)).evict(salonId);
        }

        /**
         * The multi-service counterpart of {@code Rejections
         * #should_reject409_when_theDatabaseRejectsTheInsertAsOverlapping} — a GIST violation on ANY
         * chained row must map to the same 409, and no downstream side effect (cache eviction, SMS)
         * may fire for a visit that was never actually committed.
         */
        @Test
        @DisplayName("should_map409AndFireNoSideEffects_when_saveAllViolatesOverlapExclude")
        void should_map409AndFireNoSideEffects_when_saveAllViolatesOverlapExclude() {
            stubMasterAndThreeAssignments();
            stubStaffSlotAvailable(START);
            when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
            when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(false);
            when(bookingRepository.saveAll(any()))
                    .thenThrow(new DataIntegrityViolationException("no_overlapping_bookings"));

            assertThatThrownBy(() -> create(threeServiceCommand()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Slot not available")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            verifyNoInteractions(salonCatalogCacheEvictor, smsService);
            verify(slotCalculationService, never()).evictMasterAvailabilityCaches(any());
        }

        @Test
        @DisplayName("should_sendExactlyOneSms_when_threeServiceVisitCreated")
        void should_sendExactlyOneSms_when_threeServiceVisitCreated() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            verify(smsService, org.mockito.Mockito.times(1)).send(eq(E164_PHONE), any(String.class));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Visit response (Phase 22.14) — reuses AppointmentService#enrich, never a second mapper
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Visit response")
    class VisitResponse {

        /**
         * REUSE-FIRST, pinned: the create path must hand {@code enrich} the REAL persisted header and
         * the REAL saved chain — not a re-derived or partial substitute — so the returned
         * {@code AppointmentDetailResponse} reflects exactly what was committed. Mutation-check RED
         * by handing {@code enrich} an empty or re-built list instead of {@code saved}.
         */
        @Test
        @DisplayName("should_enrichWithTheHeaderAndTheSavedChain_when_visitCreated")
        void should_enrichWithTheHeaderAndTheSavedChain_when_visitCreated() {
            stubThreeServiceVisit();

            create(threeServiceCommand());

            List<Booking> saved = captureSavedBookings();
            ArgumentCaptor<Appointment> appointmentCaptor = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentService).enrich(appointmentCaptor.capture(), eq(saved));
            assertThat(appointmentCaptor.getValue().getBookingSource()).isEqualTo(BookingSource.STAFF);
        }

        /**
         * The service returns exactly what {@code enrich} produces — no post-processing, no field
         * dropped or substituted on the way out.
         */
        @Test
        @DisplayName("should_returnWhatEnrichReturns_when_visitCreated")
        void should_returnWhatEnrichReturns_when_visitCreated() {
            stubHappyPath(salonMaster(), assignment(null, null));
            AppointmentDetailResponse stub = stubAppointmentDetailResponse();
            when(appointmentService.enrich(any(), any())).thenReturn(stub);

            AppointmentDetailResponse response = create(command(guest(RAW_PHONE)));

            assertThat(response).isSameAs(stub);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private StaffClientRef.Guest guest(String phone) {
        return new StaffClientRef.Guest("Олена", "Коваль", phone);
    }

    /** A salon-scoped command for the salon under test — the shape almost every case needs. */
    private StaffBookingCommand command(StaffClientRef client) {
        return commandAt(START, new StaffBookingScope.InSalon(salonId), client);
    }

    private StaffBookingCommand command(StaffBookingScope scope, StaffClientRef client) {
        return commandAt(START, scope, client);
    }

    /** Single-service command — the shape almost every existing case needs (N = 1). */
    private StaffBookingCommand commandAt(
            OffsetDateTime startsAt, StaffBookingScope scope, StaffClientRef client) {
        return commandWithServices(List.of(masterServiceId), startsAt, scope, client);
    }

    /** Multi-service command — the N &gt; 1 visit shape (Phase 22.12). */
    private StaffBookingCommand commandWithServices(
            List<UUID> masterServiceIds, OffsetDateTime startsAt, StaffBookingScope scope,
            StaffClientRef client) {
        return new StaffBookingCommand(scope, masterId, masterServiceIds, startsAt, client);
    }

    /**
     * The service takes the acting staff user as an EXPLICIT second argument, never as a command
     * field (security MEDIUM, 2026-08-18) — so no request-body mapping in Phase 22.4 can reach
     * {@code bookings.created_by_user_id}. Every call in this suite goes through here.
     */
    private AppointmentDetailResponse create(StaffBookingCommand cmd) {
        return createAs(staffUserId, cmd);
    }

    /**
     * The self-booking shape: {@code actorId} IS the master's own user. A {@code Self} scope may name
     * no one else (security MEDIUM, 2026-08-18), so this is the only actor a Self happy path can
     * legitimately have — the suite used to pass an unrelated {@code staffUserId} there, which is a
     * combination the service now refuses and the real world never produces.
     */
    private AppointmentDetailResponse createAs(UUID actorId, StaffBookingCommand cmd) {
        return service.createStaffBooking(cmd, actorId);
    }

    /**
     * A placeholder {@link AppointmentDetailResponse} — the mocked {@link #appointmentService}'s
     * {@code enrich(...)} return value. No test in this suite asserts on its content (see the
     * {@code appointmentService} field javadoc), so every field bar {@code status} is an arbitrary
     * valid value.
     */
    private static AppointmentDetailResponse stubAppointmentDetailResponse() {
        java.time.ZonedDateTime start = START.atZoneSameInstant(com.beautica.common.TimeZones.KYIV);
        return new AppointmentDetailResponse(
                UUID.randomUUID(),                       // id
                BookingStatus.CONFIRMED,                 // status
                UUID.randomUUID(),                        // masterId
                "Марія",                                  // masterFirstName
                "Левченко",                                // masterLastName
                null,                                      // masterProfessionalTitle
                null,                                      // masterAvatarUrl
                Role.SALON_MASTER,                         // masterType
                null,                                       // salonName
                start,                                       // startsAt
                start.plusMinutes(BASE_DURATION),            // endsAt
                BASE_DURATION,                                // totalDurationMinutes
                BASE_PRICE,                                    // totalPrice
                null,                                           // totalPriceMax
                null,                                            // clientComment
                java.time.OffsetDateTime.now(ZoneOffset.UTC),     // createdAt
                List.of(),                                         // items
                null,                                               // providerComment
                null,                                               // clientCancellationNote
                null,                                               // cityLabel
                null,                                               // districtLabel
                null,                                               // street
                null,                                               // buildingNo
                null);                                              // locationNote
    }

    private void stubHappyPath(Master master, MasterServiceAssignment msa) {
        stubMasterAndAssignment(master, msa);
        stubStaffSlotAvailable(START);
        stubLockFreeAndSave();
    }

    private void stubMasterAndAssignment(Master master, MasterServiceAssignment msa) {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
    }

    /**
     * Stubs the STAFF WHOLE-CHAIN schedule-fit oracle. Note the method:
     * {@code isStaffVisitSlotAvailable}, NOT {@code isStaffSlotAvailable} (Phase 22.12 — the
     * single-service guard has zero call sites in production now) and NOT any
     * {@code getAvailableSlots} overload — a staff create must consult the ZERO-lead oracle, and
     * stubbing a client-facing one here would leave the real call unstubbed (Mockito's {@code false}
     * default → 409), which is exactly how this test catches the service silently reverting to the
     * 15-minute floor.
     *
     * <p>The stub is start-SPECIFIC ({@code eq(startsAt)}): the gate asks about one instant, so a
     * service that asked about a different one — a mis-derived Kyiv civil date, say — would get the
     * unstubbed {@code false} and 409 rather than passing on a wildcard. The id list and preloaded
     * assignment list are wildcarded so this one stub serves both the single- and multi-service
     * fixtures in this suite.
     */
    private void stubStaffSlotAvailable(OffsetDateTime startsAt) {
        when(slotCalculationService.isStaffVisitSlotAvailable(
                eq(masterId), any(LocalDate.class), any(), any(), eq(startsAt)))
                .thenReturn(true);
    }

    /**
     * {@code saveAll} + implicit {@code flush} (Phase 22.12 — {@code BookingSlotLockGuard
     * #saveOrConflict(repo, Booking)} now delegates to the list overload, so even a single-service
     * fixture goes through {@code saveAll}, never {@code saveAndFlush}).
     */
    private void stubLockFreeAndSave() {
        when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(false);
        when(bookingRepository.saveAll(any())).thenAnswer(inv -> {
            List<Booking> bookings = inv.getArgument(0);
            // @CreationTimestamp is a Hibernate flush-time hook, so an unpersisted entity has none —
            // BookingResponse.from reads it. Set it here rather than making the entity mutable.
            bookings.forEach(b -> ReflectionTestUtils.setField(b, "createdAt", NOW));
            return bookings;
        });
    }

    /** The FIRST saved booking — the shape almost every single-service assertion needs. */
    private Booking captureSaved() {
        return captureSavedBookings().get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Booking> captureSavedBookings() {
        ArgumentCaptor<List<Booking>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookingRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private Master salonMaster() {
        return Master.builder()
                .id(masterId)
                .user(user(Role.SALON_MASTER))
                .salon(Salon.builder().id(salonId).isActive(true).build())
                .isActive(true)
                .build();
    }

    private Master independentMaster() {
        return Master.builder().id(masterId).user(user(Role.INDEPENDENT_MASTER)).isActive(true).build();
    }

    private Master inactiveMaster() {
        return Master.builder().id(masterId).user(user(Role.SALON_MASTER)).isActive(false).build();
    }

    /**
     * The master's own {@code User}, with a PINNED id — {@link StaffBookingScope.Self} asserts
     * {@code master.getUser().getId()}, so a random id per fixture would make the self-scope cases
     * pass or fail for the wrong reason.
     */
    private User user(Role role) {
        User u = new User("m@beautica.test", "x", role, "Марія", "Левченко", null);
        ReflectionTestUtils.setField(u, "id", masterUserId);
        return u;
    }

    private MasterServiceAssignment assignment(BigDecimal priceOverride, Integer durationOverride) {
        return assignmentOf(salonMaster(), priceOverride, durationOverride);
    }

    /** A second/third leg fixture for {@link MultiServiceVisit} — same duration/price, own id. */
    private MasterServiceAssignment assignmentWithId(UUID id) {
        return MasterServiceAssignment.builder()
                .id(id)
                .master(salonMaster())
                .serviceDefinition(serviceDefinition("Манікюр"))
                .isActive(true)
                .build();
    }

    /** The ordered 3-service command {@link MultiServiceVisit} chains. */
    private StaffBookingCommand threeServiceCommand() {
        return new StaffBookingCommand(new StaffBookingScope.InSalon(salonId), masterId,
                List.of(masterServiceId, masterServiceId2, masterServiceId3), START, guest(RAW_PHONE));
    }

    private void stubMasterAndThreeAssignments() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(salonMaster()));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(assignmentWithId(masterServiceId)));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId2))
                .thenReturn(Optional.of(assignmentWithId(masterServiceId2)));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId3))
                .thenReturn(Optional.of(assignmentWithId(masterServiceId3)));
    }

    private void stubThreeServiceVisit() {
        stubMasterAndThreeAssignments();
        stubStaffSlotAvailable(START);
        stubLockFreeAndSave();
    }

    private MasterServiceAssignment assignmentOf(Master master, BigDecimal priceOverride, Integer durationOverride) {
        ServiceDefinition def = serviceDefinition("Манікюр");
        return MasterServiceAssignment.builder()
                .id(masterServiceId)
                .master(master)
                .serviceDefinition(def)
                .priceOverride(priceOverride)
                .durationOverrideMinutes(durationOverride)
                .isActive(true)
                .build();
    }

    /**
     * A service definition whose PROVIDER-SET custom name is {@code customName} and whose
     * PLATFORM-SET taxonomy name is always {@link #PLATFORM_SERVICE_NAME}. The two are separable on
     * purpose: {@code StaffBookingService#platformServiceName} must read the second, and a fixture
     * that set both to the same string could not tell the two apart (Anti-Bug — a fixture value that
     * defangs the assertion).
     *
     * <p>The default {@code customName} equals the platform name, so every pre-existing row in this
     * suite keeps asserting the copy it always asserted; only
     * {@link WalkInConfirmationSms#should_usePlatformServiceTypeName_when_masterSetCustomServiceName}
     * pulls them apart.
     */
    private ServiceDefinition serviceDefinition(String customName) {
        return ServiceDefinition.builder()
                .name(customName)
                .serviceType(ServiceType.builder()
                        .nameUk(PLATFORM_SERVICE_NAME)
                        .slug("manikur")
                        .build())
                .baseDurationMinutes(BASE_DURATION)
                .bufferMinutesAfter(BUFFER)
                .basePrice(BASE_PRICE)
                .build();
    }

    /** Same as {@link #assignment} but with a provider-chosen custom service name. */
    private MasterServiceAssignment assignmentWithCustomServiceName(String customName) {
        return MasterServiceAssignment.builder()
                .id(masterServiceId)
                .master(salonMaster())
                .serviceDefinition(serviceDefinition(customName))
                .isActive(true)
                .build();
    }
}
