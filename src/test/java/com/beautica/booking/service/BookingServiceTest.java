package com.beautica.booking.service;

import com.beautica.TestConstants;
import com.beautica.auth.Role;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.dto.AppointmentProviderNoteRequest;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingPartition;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.SalonClosureBookingCandidate;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBookingConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.cache.Cache;
import java.math.BigDecimal;
import org.springframework.data.domain.PageImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService — unit")
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private MasterRepository masterRepository;
    @Mock
    private MasterServiceRepository masterServiceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SalonRepository salonRepository;
    @Mock
    private AuthorizationService authz;
    @Mock
    private NotificationOutboxService outboxService;
    @Mock
    private com.beautica.notification.repository.NotificationOutboxRepository notificationOutboxRepository;
    @Mock
    private SlotCalculationService slotCalculationService;
    @Mock
    private com.beautica.review.repository.ReviewRepository reviewRepository;
    @Mock
    private com.beautica.review.repository.ClientReviewRepository clientReviewRepository;
    @Mock
    private com.beautica.location.DiscoveryLocationResolver discoveryLocationResolver;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Mock
    private ScheduleDateMath dateMath;
    @Mock
    private AppointmentTransitionService appointmentTransitionService;
    @Mock
    private AppointmentRepository appointmentRepository;

    private Clock clock;

    @InjectMocks
    private BookingService bookingService;

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private UUID clientId;
    private UUID masterId;
    private UUID masterServiceId;
    private UUID bookingId;

    private User client;
    private Master master;
    private ServiceDefinition serviceDef;
    private MasterServiceAssignment msa;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.now(), KYIV);
        bookingService = new BookingService(
                bookingRepository,
                masterRepository,
                masterServiceRepository,
                userRepository,
                salonRepository,
                authz,
                outboxService,
                notificationOutboxRepository,
                slotCalculationService,
                reviewRepository,
                clientReviewRepository,
                discoveryLocationResolver,
                clock,
                // A REAL evictor over the mocked CacheManager, never a mock: the key-shape predicate it
                // owns is the thing that silently no-opped for months, so it must actually execute here.
                // (BookingService no longer holds a CacheManager of its own — both of its former direct
                // uses were the prefix scans now delegated to this evictor.)
                new com.beautica.common.cache.MasterCachePrefixEvictor(cacheManager),
                salonCatalogCacheEvictor,
                dateMath,
                appointmentTransitionService,
                appointmentRepository,
                eventPublisher
        );

        clientId = UUID.randomUUID();
        masterId = UUID.randomUUID();
        masterServiceId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        client = buildUser(clientId, Role.CLIENT);
        master = buildMaster(masterId, MasterType.INDEPENDENT_MASTER);
        serviceDef = buildServiceDef(new BigDecimal("200.00"), 60, 0);
        msa = buildMsa(masterServiceId, master, serviceDef, null, null);

        // G2/G4 (cycle-7 audit 2026-08-03): cancelBooking's and rescheduleBooking's freshness
        // re-check (BookingRepository#existsConfirmedById) is now UNCONDITIONAL — it used to run
        // only for an appointment child, so a standalone-booking test never reached it. Defaulting
        // to "still CONFIRMED" here (lenient — most tests never touch this row, and MockitoExtension
        // is STRICT_STUBS) keeps every pre-existing standalone-path test's intent unchanged; the
        // dedicated negative tests for this recheck override it to false explicitly.
        lenient().when(bookingRepository.existsConfirmedById(any())).thenReturn(true);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private User buildUser(UUID id, Role role) {
        User u = new User("test@example.com", "hash", role, "First", "Last", "+380501234567");
        setField(u, "id", id);
        return u;
    }

    private Authentication buildAuth(Role role) {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
    }

    private Master buildMaster(UUID id, MasterType type) {
        Master m = Master.builder()
                .user(client)
                .masterType(type)
                .isActive(true)
                .build();
        setField(m, "id", id);
        return m;
    }

    private ServiceDefinition buildServiceDef(BigDecimal basePrice, int baseDuration, int buffer) {
        return ServiceDefinition.builder()
                .name("Test Service")
                .priceType(PriceType.FIXED)
                .basePrice(basePrice)
                .baseDurationMinutes(baseDuration)
                .bufferMinutesAfter(buffer)
                .isActive(true)
                .build();
    }

    private MasterServiceAssignment buildMsa(UUID id, Master m, ServiceDefinition sd,
                                              BigDecimal priceOverride, Integer durationOverride) {
        MasterServiceAssignment a = MasterServiceAssignment.builder()
                .master(m)
                .serviceDefinition(sd)
                .priceOverride(priceOverride)
                .durationOverrideMinutes(durationOverride)
                .isActive(true)
                .build();
        setField(a, "id", id);
        return a;
    }

    private Booking buildBooking(UUID id, User c, Master m, MasterServiceAssignment a, BookingStatus status) {
        return buildBookingStartingAt(id, c, m, a, status, ZonedDateTime.now(clock).plusHours(2).toOffsetDateTime());
    }

    /**
     * Phase 27.1: {@link #buildBooking} pins {@code startsAt} in the FUTURE (now+2h) — correct
     * for decline/create/cancel/reschedule fixtures, but wrong for {@code completeBooking}, whose
     * new {@code assertElapsedForComplete} guard requires {@code now >= startsAt}. This overload
     * takes an explicit {@code startsAt} so complete-path tests can pin an ELAPSED booking.
     */
    private Booking buildBookingStartingAt(
            UUID id, User c, Master m, MasterServiceAssignment a, BookingStatus status, OffsetDateTime startsAt) {
        Booking b = Booking.builder()
                .client(c)
                .master(m)
                .masterService(a)
                .status(status)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .priceAtBooking(new BigDecimal("200.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        setField(b, "id", id);
        ReflectionTestUtils.setField(b, "createdAt", Instant.now());
        return b;
    }

    /** Booking whose {@code startsAt} has already elapsed relative to the pinned {@link #clock} — for {@code completeBooking} happy-path fixtures (Phase 27.1). */
    private Booking buildElapsedBooking(UUID id, User c, Master m, MasterServiceAssignment a, BookingStatus status) {
        return buildBookingStartingAt(id, c, m, a, status, ZonedDateTime.now(clock).minusHours(1).toOffsetDateTime());
    }

    private CreateBookingRequest validRequest() {
        return new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusHours(2),
                null,
                null,
                false
        );
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + name);
    }

    /**
     * Stubs the CREATE-path schedule-fit oracle (2026-08-11) so {@code startsAt} resolves to an
     * on-schedule slot, mirroring {@link #stubRescheduleSlotAvailable} for the reschedule path.
     *
     * <p>Required by every create test that expects the booking to be PERSISTED: all three create
     * paths now assert the requested start matches a slot the master actually works, and an unstubbed
     * mock answers with an empty slot list — i.e. "the master does not work then" — which is a 409.
     */
    private void stubCreateSlotAvailable(ZonedDateTime startsAt) {
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(LocalDate.class), eq(masterServiceId),
                nullable(MasterServiceAssignment.class)))
                .thenReturn(List.of(new AvailableSlotResponse(
                        startsAt.withZoneSameInstant(KYIV),
                        startsAt.plusMinutes(60).withZoneSameInstant(KYIV))));
    }

    /** {@link #stubCreateSlotAvailable(ZonedDateTime)} for the many tests that book {@link #validRequest()}. */
    private void stubCreateSlotAvailable() {
        stubCreateSlotAvailable(validRequest().startsAt());
    }
    // ── createBooking ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking is created and saved when the slot is free and no overlap exists")
    void should_createBooking_when_slotAvailableAndNoConflict() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        stubCreateSlotAvailable();

        BookingDetailResponse result = bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result).isNotNull();
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
    }

    @Test
    @DisplayName("SALON_OWNER master — booking creation proceeds identically to INDEPENDENT_MASTER")
    void should_bookOwnerMaster_when_clientBooksAvailableSlot() {
        // Arrange — build an SALON_OWNER-type master (same as INDEPENDENT_MASTER from BookingService's perspective)
        Master ownerMaster = buildMaster(masterId, MasterType.SALON_OWNER);
        MasterServiceAssignment ownerMsa = buildMsa(masterServiceId, ownerMaster, serviceDef, null, null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(ownerMaster));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(ownerMsa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));

        Booking saved = buildBooking(bookingId, client, ownerMaster, ownerMsa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        // Act
        stubCreateSlotAvailable();
        BookingDetailResponse result = bookingService.createBooking(clientId, null, validRequest());

        // Assert — booking created; master_id is the owner-master's ID
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMaster().getMasterType()).isEqualTo(MasterType.SALON_OWNER);
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result).isNotNull();
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
    }

    @Test
    @DisplayName("existing booking is returned without saving when idempotency key already exists")
    void should_returnExistingBooking_when_idempotencyKeyMatches() {
        String key = "unique-key-123";
        Booking existing = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findActiveByClientIdAndIdempotencyKey(clientId, key))
                .thenReturn(Optional.of(existing));
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(existing));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.createBooking(clientId, key, validRequest());

        verify(bookingRepository, never()).saveAndFlush(any());
        assertThat(result.id()).isEqualTo(bookingId);
    }

    @Test
    @DisplayName("409 Conflict is thrown when the requested slot overlaps an existing booking")
    void should_throw409_when_slotOverlapsExistingBooking() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(true);
        stubCreateSlotAvailable();

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("409 CLIENT_BOOKING_CONFLICT is thrown when the client already holds an overlapping "
            + "booking with a DIFFERENT master — the master-busy check never runs")
    void should_throwClientBookingConflict_when_clientAlreadyHasOverlappingBookingWithDifferentMaster() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));

        UUID otherMasterId = UUID.randomUUID();
        Master otherMaster = buildMaster(otherMasterId, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment otherMsa = buildMsa(UUID.randomUUID(), otherMaster, serviceDef, null, null);
        UUID conflictingBookingId = UUID.randomUUID();
        Booking conflicting = buildBooking(conflictingBookingId, client, otherMaster, otherMsa, BookingStatus.CONFIRMED);
        when(bookingRepository.findFirstConflictingClientBookingId(eq(clientId), any(), any()))
                .thenReturn(Optional.of(conflictingBookingId));
        when(bookingRepository.findByIdWithFullGraph(conflictingBookingId)).thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(ClientBookingConflictException.class)
                .satisfies(ex -> {
                    ClientBookingConflictException cbce = (ClientBookingConflictException) ex;
                    assertThat(cbce.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(cbce.getConflictingBookingId()).isEqualTo(conflictingBookingId);
                    assertThat(cbce.getServiceName()).isEqualTo(serviceDef.getName());
                    assertThat(cbce.getMasterName()).isEqualTo("First Last");
                    assertThat(cbce.getStartsAt()).isEqualTo(conflicting.getStartsAt());
                    assertThat(cbce.getEndsAt()).isEqualTo(conflicting.getEndsAt());
                });

        // Client-conflict wins deterministically: since the Phase 19.4 client-then-master
        // reorder, the master lock is not even acquired — the shared per-master lock (which
        // every other client racing for the same popular master may be waiting on) is never
        // touched for a conflict that is entirely about this client's own calendar.
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).existsOverlap(any(), any(), any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("advisory locks are acquired client-then-master (deterministic order, Phase 19.4 "
            + "reorder), and the client-conflict check runs before the master lock is even acquired")
    void should_acquireClientLockBeforeMasterLock_andCheckClientConflictFirst_when_creatingBooking() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        stubCreateSlotAvailable();

        bookingService.createBooking(clientId, null, validRequest());

        // acquireClientAdvisoryLockWithTimeout is the fused query (Phase perf fix) that sets
        // the transaction-scoped lock_timeout AND takes the client lock in one round trip — its
        // presence here (in place of a separate setBookingLockTimeout() + acquireClientAdvisoryLock()
        // pair) IS the regression guard that the timeout ceiling is still applied before the
        // client lock wait, without weakening the ordering assertion below.
        InOrder inOrder = org.mockito.Mockito.inOrder(bookingRepository);
        inOrder.verify(bookingRepository).acquireClientAdvisoryLockWithTimeout(clientId);
        inOrder.verify(bookingRepository).findFirstConflictingClientBookingId(eq(clientId), any(), any());
        inOrder.verify(bookingRepository).acquireAdvisoryLock(masterId);
        inOrder.verify(bookingRepository).existsOverlap(eq(masterId), any(), any());

        // The master lock still uses the PLAIN (non-fused) query — the 3s lock_timeout set by
        // acquireClientAdvisoryLockWithTimeout is transaction-scoped (set_config(..., true)),
        // so it remains in force for this later master-lock wait without needing to be
        // re-applied. Asserting this negative confirms the fused master-lock variant was not
        // (mis)used here — that variant is reserved for GuestBookingService, which has no
        // preceding client lock to inherit the timeout from.
        verify(bookingRepository, never()).acquireAdvisoryLockWithTimeout(any());
    }

    @Test
    @DisplayName("404 NotFoundException is thrown when the master does not exist")
    void should_throw404_when_masterNotFound() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("404 NotFoundException is thrown when the master service assignment does not exist")
    void should_throw404_when_masterServiceNotFound() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("404 NotFoundException is thrown when the master exists but is inactive")
    void should_throw404_when_masterIsInactive() {
        // Arrange — build a master with isActive = false
        Master inactiveMaster = Master.builder()
                .user(client)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(false)
                .build();
        setField(inactiveMaster, "id", masterId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(inactiveMaster));

        // Act + Assert — the filter(Master::isActive) turns the Optional empty
        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    // ── salon-active guard on the booking write path (2026-08 security re-audit MEDIUM) ────────
    //
    // SalonService.deactivateSalon flips salons.is_active but does NOT cascade to
    // masters.is_active, so a closed salon's masters all still pass Master::isActive. Before this
    // guard, a client holding a masterServiceId (stale wish-list card, bookmarked deep link,
    // cached catalogue page) could still create a CONFIRMED booking against a salon the owner had
    // closed. The three tests below pin the guard AND both directions of its disjunction.

    @Test
    @DisplayName("404 NotFoundException is thrown when the master's salon was deactivated, even "
            + "though the master row itself is still active")
    void should_return404_when_bookingMasterOfDeactivatedSalon() {
        // Arrange — the ONLY false flag is the salon's. masters.is_active stays true, exactly the
        // state deactivateSalon leaves behind (it does not cascade).
        Master salonMaster = buildMaster(masterId, MasterType.SALON_MASTER);
        salonMaster.setSalon(com.beautica.salon.entity.Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .isActive(false)
                .build());
        assertThat(salonMaster.isActive())
                .as("precondition: the master row is NOT deactivated — otherwise this test would "
                        + "be re-testing the pre-existing Master::isActive filter")
                .isTrue();
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(salonMaster));

        // Act + Assert
        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);

        // No booking may be written, and the master service must not even be looked up — the
        // guard is part of the same filter chain as the existence check.
        verify(bookingRepository, never()).saveAndFlush(any());
        verifyNoInteractions(masterServiceRepository);
    }

    @Test
    @DisplayName("booking still succeeds for a salon master whose salon is ACTIVE — the guard must "
            + "not reject every salon-employed master")
    void should_createBooking_when_masterSalonIsActive() {
        Master salonMaster = buildMaster(masterId, MasterType.SALON_MASTER);
        salonMaster.setSalon(com.beautica.salon.entity.Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .isActive(true)
                .build());
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(salonMaster));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, salonMaster, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        stubCreateSlotAvailable();

        BookingDetailResponse result = bookingService.createBooking(clientId, null, validRequest());

        assertThat(result).isNotNull();
        verify(bookingRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("booking still succeeds for an INDEPENDENT_MASTER, who has no salon at all — the "
            + "null branch of the guard is load-bearing, not defensive")
    void should_createBooking_when_masterHasNoSalon() {
        // `master` (the shared fixture) is an INDEPENDENT_MASTER with salon == null. A guard
        // written as `m.getSalon().isActive()` would NPE here; one written as
        // `sal.isActive() == true` would 404 every independent master in the platform.
        assertThat(master.getSalon()).as("precondition: no salon").isNull();
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        stubCreateSlotAvailable();

        BookingDetailResponse result = bookingService.createBooking(clientId, null, validRequest());

        assertThat(result).isNotNull();
        verify(bookingRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("404 NotFoundException is thrown when the requested service does not belong to the master")
    void should_throwNotFoundException_when_serviceDoesNotBelongToMaster() {
        // Arrange — master is found and active; but the service lookup returns empty
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("400 is thrown when the requested start time is in the past")
    void should_throw400_when_startsAtInThePast() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest pastRequest = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).minusMinutes(10),
                null,
                null,
                false
        );

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, pastRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("400 is thrown when the requested start time is exactly 14 minutes from now (below minimum lead time)")
    void should_throw400_when_startsAtIsExactly14MinutesFromNow() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest request = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusMinutes(14),
                null,
                null,
                false
        );

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("booking proceeds past time check when start time is exactly 15 minutes from now (minimum lead time boundary)")
    void should_proceedPastTimeCheck_when_startsAtIsExactly15MinutesFromNow() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        CreateBookingRequest request = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusMinutes(15),
                null,
                null,
                false
        );
        stubCreateSlotAvailable(request.startsAt());

        BookingDetailResponse result = bookingService.createBooking(clientId, null, request);

        assertThat(result).isNotNull();
        verify(bookingRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("400 is thrown when the requested start time is more than 180 days in the future")
    void should_throw400_when_startsAtMoreThan180DaysAhead() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest farFutureRequest = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusDays(181),
                null,
                null,
                false
        );

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, farFutureRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("price and duration are snapshotted from the override values when the master service has overrides")
    void should_snapshotPriceAndDuration_when_masterServiceHasOverrides() {
        MasterServiceAssignment msaWithOverrides = buildMsa(
                masterServiceId, master, serviceDef, new BigDecimal("250.00"), 45);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msaWithOverrides));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msaWithOverrides, BookingStatus.CONFIRMED);
        setField(saved, "priceAtBooking", new BigDecimal("250.00"));
        setField(saved, "durationMinutesAtBooking", 45);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        stubCreateSlotAvailable();

        bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPriceAtBooking()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(captor.getValue().getDurationMinutesAtBooking()).isEqualTo(45);
    }

    @Test
    @DisplayName("price and duration are snapshotted from base values when no overrides are set")
    void should_fallBackToBaseValues_when_noOverrides() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        stubCreateSlotAvailable();

        bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPriceAtBooking()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(captor.getValue().getDurationMinutesAtBooking()).isEqualTo(60);
    }

    @Test
    @DisplayName("new-booking notification is enqueued when the booking is successfully created")
    void should_enqueueNewBookingNotification_when_bookingCreated() {
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(saved));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        stubCreateSlotAvailable();

        bookingService.createBooking(clientId, null, validRequest());

        verify(outboxService).enqueueNewBooking(bookingId);
    }

    // TODO(24.7): confirmBooking() was deleted (track 24.x auto-confirm — no approval step, no
    // /confirm endpoint). Former coverage here (authorized-confirm success, unauthorized-confirm
    // 403, confirm-on-non-PENDING 400) has no replacement — there is nothing left to confirm.

    // ── declineBooking (Phase 24.2 — provider-initiated cancellation, CONFIRMED → DECLINED) ────

    @Test
    @DisplayName("booking moves to DECLINED and slot cache is evicted when authorized actor declines")
    void should_declineBooking_when_authorizedActorDeclines() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, "Unavailable");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.declineBooking(actorId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.DECLINED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.PROVIDER_UNAVAILABLE);
        verify(outboxService).enqueueStatusChanged(bookingId);
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
    }

    @Test
    @DisplayName("ForbiddenException is thrown when an unauthorized actor attempts to decline a booking")
    void should_throwForbidden_when_unauthorizedActorDeclinesBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanCancelBooking(actorId, booking);

        assertThatThrownBy(() -> bookingService.declineBooking(actorId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("400 is thrown when decline is called without a cancellation reason")
    void should_throw400_when_declineCalledWithoutCancellationReason() {
        UUID actorId = UUID.randomUUID();
        StatusUpdateRequest req = new StatusUpdateRequest(null, "some comment");

        assertThatThrownBy(() -> bookingService.declineBooking(actorId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("400 is thrown when declining a booking that is not CONFIRMED (assertTransition guard, track 24.x — no PENDING left to decline from)")
    void should_throw400_when_decliningNonConfirmedBooking() {
        UUID actorId = UUID.randomUUID();
        // COMPLETED exercises the same assertTransition(booking, CONFIRMED, DECLINED) guard any
        // other non-CONFIRMED source status would — a completed visit can never be "declined".
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.declineBooking(actorId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(booking.getStatus())
                .as("status must remain unchanged after a rejected decline")
                .isEqualTo(BookingStatus.COMPLETED);
        verify(outboxService, never()).enqueueStatusChanged(bookingId);
    }

    @Test
    @DisplayName("declineBooking — G5 freshness re-check rejects a STANDALONE booking a concurrent "
            + "client cancel already moved off CONFIRMED between the load and this recheck: 409, no "
            + "save, no mutation, no notification. Coverage gap (backend-qa, cycle-7 batch): G4 gave "
            + "cancelBooking/rescheduleBooking a dedicated unit test for this exact false branch (see "
            + "should_throw409AndNeverSave_when_cancelBookingFreshnessRecheckFailsForStandaloneBooking "
            + "above) but G5 — the reverse direction, closed by routing declineBookingCore/"
            + "completeBooking/notCompleteBooking through the SAME isStillConfirmed seam — had none; "
            + "the only prior coverage was the real-DB, virtual-thread "
            + "BookingProviderTransitionCancelRaceConcurrencyIT, with no fast deterministic signal.")
    void should_throw409AndNeverSave_when_declineBookingFreshnessRecheckFailsForStandaloneBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, "Unavailable");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // Overrides setUp()'s lenient "still CONFIRMED" default: a concurrent client cancel already
        // moved this row off CONFIRMED between the load above and this recheck.
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.declineBooking(actorId, bookingId, req))
                .as("a lost freshness race must surface as a clean 409, not a silent overwrite of the "
                        + "client's cancellation")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(booking.getStatus())
                .as("the in-memory entity must be left untouched — no partial mutation before the throw")
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getProviderComment())
                .as("providerComment must never be written once the recheck aborts")
                .isNull();
        verify(bookingRepository, never()).save(any());
        verify(outboxService, never()).enqueueStatusChanged(any());
    }

    // ── declineBookingForBatch (2026-07-26 schedule-override-conflict perf fix) ──────────────────
    // Package-private batched-decline counterpart of declineBooking, used only by
    // ScheduleOverrideConflictService to decline many standalone bookings for the same master
    // without each one independently re-scanning the master's availability caches.

    @Test
    @DisplayName("declineBookingForBatch runs the identical CONFIRMED->DECLINED mutation as declineBooking")
    void should_declineBooking_when_declineBookingForBatchCalled() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        Booking result = bookingService.declineBookingForBatch(actorId, bookingId, req);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.DECLINED);
        assertThat(result.getCancellationReason()).isEqualTo(CancellationReason.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("declineBookingForBatch never enqueues a notification (D6, 2026-07-26 product decision "
            + "reversal) — the schedule-override-conflict path this method exists for tells the client "
            + "nothing beyond the booking's own status")
    void should_notEnqueueNotification_when_declineBookingForBatchCalled() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.declineBookingForBatch(actorId, bookingId, req);

        verify(outboxService, never()).enqueueStatusChanged(bookingId);
    }

    @Test
    @DisplayName("declineBookingForBatch skips BOTH of declineBooking's own after-commit cache scans — "
            + "the caller (ScheduleOverrideConflictService) performs ONE combined eviction itself instead "
            + "of one pair per declined booking (perf finding 3)")
    void should_skipOwnCacheEviction_when_declineBookingForBatchCalled() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.declineBookingForBatch(actorId, bookingId, req);

        verifyNoInteractions(slotCalculationService);
        verify(salonCatalogCacheEvictor, never()).evict(any());
    }

    @Test
    @DisplayName("declineBookingForBatch still enforces the same 400/403/409 guards as declineBooking")
    void should_throwForbidden_when_unauthorizedActorCallsDeclineBookingForBatch() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanCancelBooking(actorId, booking);

        assertThatThrownBy(() -> bookingService.declineBookingForBatch(actorId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── declineFutureConfirmedBookingsForSalonClosure (Phase 269/293 cascade — security finding 4,
    // 2026-09 audit) ────────────────────────────────────────────────────────────────────────────
    // This method is callable from another package (SalonService) and, before this fix, trusted
    // its salonId argument entirely on the strength of its one caller's own findByIdAndOwnerId
    // check. Defense-in-depth: the method now asserts ownership itself, first, before touching
    // any booking data.

    @Test
    @DisplayName("declineFutureConfirmedBookingsForSalonClosure — ownership self-assertion (finding "
            + "4, 2026-09 audit): the actor must own salonId, checked by THIS method itself rather "
            + "than trusting the caller (SalonService#deactivateSalon) to have checked first")
    void should_throwForbidden_when_actorDoesNotOwnSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(
                () -> bookingService.declineFutureConfirmedBookingsForSalonClosure(actorId, salonId))
                .isInstanceOf(ForbiddenException.class);

        // The guard must run BEFORE any booking data is touched — never a query first, ownership
        // check second.
        verifyNoInteractions(bookingRepository);
    }

    // QA note (Phase 293 audit, 2026-09-04, gap 1): this test stubs
    // bookingRepository.declineConfirmedBulk(...) directly, so it can ONLY pin how
    // declineFutureConfirmedBookingsForSalonClosure REACTS to an empty RETURNING result — it
    // cannot fail for, and proves nothing about, the real native SQL predicate
    // (`WHERE ... AND status = 'CONFIRMED'`) declineConfirmedBulk actually issues. A mutant that
    // deletes that predicate from the @Query in BookingRepository leaves THIS test green. The
    // predicate itself is pinned against a real database by
    // SalonDeactivationCascadeIT#should_leaveNonConfirmedBookingUntouched_when_declineConfirmedBulkCalledDirectly
    // (case 18) and #should_declineOnlyConfirmedRow_when_declineConfirmedBulkCalledWithMixedStatusIds
    // (case 20) — see that class's own Javadoc on case 18, which documents this exact split.
    // Do not read this test's @DisplayName as a claim that IT tests the SQL; it only tests the
    // Java-side response handling once the repository has already decided the outcome.
    @Test
    @DisplayName("declineFutureConfirmedBookingsForSalonClosure — perf re-audit Finding A: a "
            + "standalone booking that leaves CONFIRMED mid-cascade (excluded from the bulk "
            + "UPDATE's RETURNING result) is neither declined nor given a SALON_CLOSED notice — "
            + "the bulk statement's own WHERE ... AND status = CONFIRMED predicate is the "
            + "freshness check, evaluated per row inside the single statement, never a Set "
            + "snapshot taken earlier")
    void should_skipBookingAndItsNotice_when_bookingLeavesConfirmedMidCascade() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID raceLostBookingId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now(clock).plusDays(1);

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(bookingRepository.findConfirmedFutureBySalonId(eq(salonId), any()))
                .thenReturn(List.of(
                        new SalonClosureBookingCandidate(raceLostBookingId, null, masterId, startsAt)));
        Booking raceLostBooking = buildBookingStartingAt(
                raceLostBookingId, client, master, msa, BookingStatus.CONFIRMED, startsAt);
        when(bookingRepository.findAllByIdInWithFullGraph(List.of(raceLostBookingId)))
                .thenReturn(List.of(raceLostBooking));
        // The row left CONFIRMED (e.g. a concurrent client cancel) sometime between the batched
        // read phase and the bulk write — the bulk UPDATE's own WHERE ... AND status = CONFIRMED
        // predicate catches it and excludes it from the RETURNING result.
        when(bookingRepository.declineConfirmedBulk(eq(List.of(raceLostBookingId)), any(), any(), any()))
                .thenReturn(List.of());

        bookingService.declineFutureConfirmedBookingsForSalonClosure(actorId, salonId);

        verify(outboxService, never()).enqueueSalonClosed(any());
    }

    @Test
    @DisplayName("declineFutureConfirmedBookingsForSalonClosure — QA-authored (Phase 293 audit gap "
            + "3, security re-audit INFO): even when a candidate's ONLY booking loses the race and "
            + "is neither declined nor given a SALON_CLOSED notice, its master's availability and "
            + "calendar caches are STILL evicted — eviction is scoped to every masterId in the "
            + "ORIGINAL candidate scan, never filtered down to the ids this call actually "
            + "transitioned. A superset eviction never under-evicts (harmless, per the security "
            + "audit), but the call must still happen unconditionally on the race outcome; this "
            + "test is what makes a mutant that skips eviction for a fully-raced-away master "
            + "observable")
    void should_stillEvictMasterCaches_when_itsOnlyCandidateBookingLosesTheRace() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID raceLostBookingId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now(clock).plusDays(1);

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(bookingRepository.findConfirmedFutureBySalonId(eq(salonId), any()))
                .thenReturn(List.of(
                        new SalonClosureBookingCandidate(raceLostBookingId, null, masterId, startsAt)));
        Booking raceLostBooking = buildBookingStartingAt(
                raceLostBookingId, client, master, msa, BookingStatus.CONFIRMED, startsAt);
        when(bookingRepository.findAllByIdInWithFullGraph(List.of(raceLostBookingId)))
                .thenReturn(List.of(raceLostBooking));
        when(bookingRepository.declineConfirmedBulk(eq(List.of(raceLostBookingId)), any(), any(), any()))
                .thenReturn(List.of());

        bookingService.declineFutureConfirmedBookingsForSalonClosure(actorId, salonId);

        verify(outboxService, never()).enqueueSalonClosed(any());
        // The master WAS in the candidate scan (findConfirmedFutureBySalonId), even though its
        // one and only candidate booking never actually transitioned — eviction still runs for it.
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
        verify(salonCatalogCacheEvictor).evict(salonId);
    }

    @Test
    @DisplayName("declineFutureConfirmedBookingsForSalonClosure — security re-audit Finding A: when "
            + "an appointment visit's deterministic D12 representative (earliest startsAt) itself "
            + "loses the race but a SIBLING item of the SAME visit is actually declined, exactly "
            + "ONE SALON_CLOSED entry is still enqueued — keyed to the surviving sibling, never to "
            + "a booking that this call did not actually decline")
    void should_enqueueOneEntryForSurvivingSibling_when_visitRepresentativeLosesRace() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID earliestBookingId = UUID.randomUUID(); // would-be D12 representative — loses the race
        UUID laterBookingId = UUID.randomUUID();    // survives — becomes the actual representative
        OffsetDateTime earliestStart = OffsetDateTime.now(clock).plusDays(1);
        OffsetDateTime laterStart = earliestStart.plusHours(1);

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(bookingRepository.findConfirmedFutureBySalonId(eq(salonId), any()))
                .thenReturn(List.of(
                        new SalonClosureBookingCandidate(earliestBookingId, appointmentId, masterId, earliestStart),
                        new SalonClosureBookingCandidate(laterBookingId, appointmentId, masterId, laterStart)));
        Booking survivingSibling = buildBookingStartingAt(
                laterBookingId, client, master, msa, BookingStatus.CONFIRMED, laterStart);
        // declineAppointmentItems' own G3 batched freshness recheck filters out whatever raced away
        // internally — this simulates it returning ONLY the surviving sibling, i.e. the
        // earliest-startsAt item (the pre-fix D12 pick) lost its own race.
        when(appointmentTransitionService.declineAppointmentItems(
                eq(actorId), eq(appointmentId), eq(List.of(earliestBookingId, laterBookingId)),
                any(), eq(false), any()))
                .thenReturn(List.of(survivingSibling));

        bookingService.declineFutureConfirmedBookingsForSalonClosure(actorId, salonId);

        verify(outboxService, times(1)).enqueueSalonClosed(laterBookingId);
        verify(outboxService, never()).enqueueSalonClosed(earliestBookingId);
    }

    @Test
    @DisplayName("declineFutureConfirmedBookingsForSalonClosure — perf finding 2, 2026-09 re-audit: "
            + "every appointment-visit in the cascade shares ONE management-access memo instance, "
            + "never a fresh one per visit — the memo is what lets AuthorizationService answer the "
            + "SALON_OWNER ownership question at most once per distinct salon for the WHOLE "
            + "cascade instead of once per visit (see AuthorizationServiceTest for the "
            + "existsByIdAndOwnerId query-count proof at the AuthorizationService layer itself)")
    void should_shareOneManagementAccessMemo_when_cascadeSpansMultipleAppointmentVisits() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID appointmentId1 = UUID.randomUUID();
        UUID appointmentId2 = UUID.randomUUID();
        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();
        OffsetDateTime start1 = OffsetDateTime.now(clock).plusDays(1);
        OffsetDateTime start2 = OffsetDateTime.now(clock).plusDays(2);

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(bookingRepository.findConfirmedFutureBySalonId(eq(salonId), any()))
                .thenReturn(List.of(
                        new SalonClosureBookingCandidate(bookingId1, appointmentId1, masterId, start1),
                        new SalonClosureBookingCandidate(bookingId2, appointmentId2, masterId, start2)));
        Booking declined1 = buildBookingStartingAt(bookingId1, client, master, msa, BookingStatus.CONFIRMED, start1);
        Booking declined2 = buildBookingStartingAt(bookingId2, client, master, msa, BookingStatus.CONFIRMED, start2);
        when(appointmentTransitionService.declineAppointmentItems(
                eq(actorId), eq(appointmentId1), eq(List.of(bookingId1)), any(), eq(false), any()))
                .thenReturn(List.of(declined1));
        when(appointmentTransitionService.declineAppointmentItems(
                eq(actorId), eq(appointmentId2), eq(List.of(bookingId2)), any(), eq(false), any()))
                .thenReturn(List.of(declined2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<AuthorizationService.MemoKey, Boolean>> memoCaptor = ArgumentCaptor.forClass(Map.class);

        bookingService.declineFutureConfirmedBookingsForSalonClosure(actorId, salonId);

        verify(appointmentTransitionService, times(2)).declineAppointmentItems(
                eq(actorId), any(), any(), any(), eq(false), memoCaptor.capture());
        List<Map<AuthorizationService.MemoKey, Boolean>> memos = memoCaptor.getAllValues();
        assertThat(memos.get(0))
                .as("the SAME memo instance is threaded through every appointment-visit call in "
                        + "one cascade — never a fresh map per visit")
                .isSameAs(memos.get(1));
        assertThat(memos.get(0).get(new AuthorizationService.MemoKey(actorId, salonId)))
                .as("pre-seeded true from the ownership self-assertion this method already ran")
                .isTrue();
    }

    // ── declineFutureConfirmedBookingsForMasterRemoval (Phase 298 — master-removal sibling of the
    // salon-closure cascade above; shares declineFutureConfirmed's entire body via D4) ──────────

    @Test
    @DisplayName("declineFutureConfirmedBookingsForMasterRemoval — ownership self-assertion, "
            + "identical rationale to the salon-closure entry point's own: the actor must own "
            + "salonId, checked by THIS method itself")
    void should_throwForbidden_when_actorDoesNotOwnSalon_forMasterRemoval() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID targetMasterId = UUID.randomUUID();
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.declineFutureConfirmedBookingsForMasterRemoval(
                actorId, salonId, targetMasterId))
                .isInstanceOf(ForbiddenException.class);

        // The guard must run BEFORE any booking data is touched, and before the second
        // (master-belongs-to-salon) self-assertion even queries.
        verifyNoInteractions(bookingRepository);
        verify(masterRepository, never()).existsByIdAndSalonId(any(), any());
    }

    @Test
    @DisplayName("declineFutureConfirmedBookingsForMasterRemoval — D4's second self-assertion: "
            + "masterId must actually belong to salonId, so a masterId from a DIFFERENT salon "
            + "cannot ride in on a salon the caller legitimately owns")
    void should_throwForbidden_when_masterDoesNotBelongToSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID targetMasterId = UUID.randomUUID();
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(targetMasterId, salonId)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.declineFutureConfirmedBookingsForMasterRemoval(
                actorId, salonId, targetMasterId))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("declineFutureConfirmedBookingsForMasterRemoval — mutation check: enqueues "
            + "MASTER_REMOVED, never SALON_CLOSED, for a standalone future CONFIRMED booking of "
            + "the removed master — pins that the shared declineFutureConfirmed body is driven by "
            + "the caller-supplied eventType, not hardcoded to the salon-closure event")
    void should_enqueueMasterRemoved_notSalonClosed_when_masterRemovalCascadeRuns() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID targetMasterId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now(clock).plusDays(1);

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(targetMasterId, salonId)).thenReturn(true);
        when(bookingRepository.acquireAdvisoryLockWithTimeout(targetMasterId)).thenReturn(1);
        when(bookingRepository.findConfirmedFutureByMasterId(eq(targetMasterId), any()))
                .thenReturn(List.of(
                        new SalonClosureBookingCandidate(bookingId, null, targetMasterId, startsAt)));
        Booking booking = buildBookingStartingAt(
                bookingId, client, master, msa, BookingStatus.CONFIRMED, startsAt);
        when(bookingRepository.findAllByIdInWithFullGraph(List.of(bookingId)))
                .thenReturn(List.of(booking));
        when(bookingRepository.declineConfirmedBulk(eq(List.of(bookingId)), any(), any(), any()))
                .thenReturn(List.of(bookingId));

        bookingService.declineFutureConfirmedBookingsForMasterRemoval(actorId, salonId, targetMasterId);

        verify(outboxService, times(1)).enqueueMasterRemoved(bookingId);
        verify(outboxService, never()).enqueueSalonClosed(any());
    }

    @Test
    @DisplayName("declineFutureConfirmedBookingsForMasterRemoval — D12: a 3-service future visit "
            + "of the removed master collapses to ONE MASTER_REMOVED entry, keyed to the "
            + "lowest-startsAt booking, never one per booking")
    void should_enqueueOneMasterRemovedEntryPerVisit_when_masterHasMultiServiceVisit() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID targetMasterId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID earliestBookingId = UUID.randomUUID();
        UUID laterBookingId = UUID.randomUUID();
        OffsetDateTime earliestStart = OffsetDateTime.now(clock).plusDays(1);
        OffsetDateTime laterStart = earliestStart.plusHours(1);

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(targetMasterId, salonId)).thenReturn(true);
        when(bookingRepository.acquireAdvisoryLockWithTimeout(targetMasterId)).thenReturn(1);
        when(bookingRepository.findConfirmedFutureByMasterId(eq(targetMasterId), any()))
                .thenReturn(List.of(
                        new SalonClosureBookingCandidate(earliestBookingId, appointmentId, targetMasterId, earliestStart),
                        new SalonClosureBookingCandidate(laterBookingId, appointmentId, targetMasterId, laterStart)));
        Booking earliestBooking = buildBookingStartingAt(
                earliestBookingId, client, master, msa, BookingStatus.CONFIRMED, earliestStart);
        Booking laterBooking = buildBookingStartingAt(
                laterBookingId, client, master, msa, BookingStatus.CONFIRMED, laterStart);
        when(appointmentTransitionService.declineAppointmentItems(
                eq(actorId), eq(appointmentId), eq(List.of(earliestBookingId, laterBookingId)),
                any(), eq(false), any()))
                .thenReturn(List.of(earliestBooking, laterBooking));

        bookingService.declineFutureConfirmedBookingsForMasterRemoval(actorId, salonId, targetMasterId);

        verify(outboxService, times(1)).enqueueMasterRemoved(earliestBookingId);
        verify(outboxService, never()).enqueueMasterRemoved(laterBookingId);
        verify(outboxService, never()).enqueueSalonClosed(any());
    }

    // ── completeBooking ────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking moves to COMPLETED and notification is enqueued when a CONFIRMED booking is completed")
    void should_completeBooking_when_confirmedBookingCompleted() {
        UUID actorId = UUID.randomUUID();
        // Phase 27.1: assertElapsedForComplete requires now >= startsAt — an elapsed fixture.
        Booking booking = buildElapsedBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.completeBooking(actorId, bookingId);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        verify(outboxService).enqueueStatusChanged(bookingId);
        // Phase 18.3: the review prompt is enqueued in the same completion transaction.
        verify(outboxService).enqueueReviewRequested(bookingId);
    }

    @Test
    @DisplayName("availability caches are evicted by master when a booking is completed — COMPLETED leaves "
            + "the `status = 'CONFIRMED'` occupancy predicate, and assertElapsedForComplete only requires "
            + "now >= startsAt, so an in-progress booking completed early frees the unused tail of its window")
    void should_evictMasterAvailabilityCaches_when_bookingCompleted() {
        // Arrange
        UUID actorId = UUID.randomUUID();
        Booking booking = buildElapsedBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        // Act
        bookingService.completeBooking(actorId, bookingId);

        // Assert
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
    }

    @Test
    @DisplayName("guest (LINK / null-client) completion enqueues STATUS_CHANGED but never REVIEW_REQUESTED")
    void should_notEnqueueReviewRequested_when_completingGuestBooking() {
        UUID actorId = UUID.randomUUID();
        // Guest booking: CONFIRMED with a null client (V89 chk_bookings_guest_fields). A guest has
        // no account to review with, so completion must not enqueue the review prompt (which would
        // NPE on booking.getClient() at drain time and dead-letter the outbox row).
        // Phase 27.1: assertElapsedForComplete requires now >= startsAt — an elapsed fixture.
        Booking booking = buildElapsedBooking(bookingId, null, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.completeBooking(actorId, bookingId);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        verify(outboxService).enqueueStatusChanged(bookingId);
        verify(outboxService, never()).enqueueReviewRequested(bookingId);
    }

    @Test
    @DisplayName("ForbiddenException is thrown when an unauthorized actor attempts to complete a booking")
    void should_throwForbidden_when_unauthorizedActorCompletesBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // Phase 18.4: completion authorization goes through enforceCanCompleteBooking
        // (admits SALON_ADMIN), not enforceCanManageBooking.
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanCompleteBooking(actorId, booking);

        assertThatThrownBy(() -> bookingService.completeBooking(actorId, bookingId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("400 is thrown when complete is called on a booking that is not CONFIRMED")
    void should_throw400_when_completingNonConfirmedBooking() {
        UUID actorId = UUID.randomUUID();
        // track 24.x: there is no more PENDING source state — any non-CONFIRMED status is
        // rejected by the same assertTransition guard. CANCELLED exercises that guard.
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CANCELLED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.completeBooking(actorId, bookingId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("completeBooking — G5 freshness re-check rejects a STANDALONE booking a concurrent "
            + "client cancel already moved off CONFIRMED between the load and this recheck: 409, no "
            + "save, no outbox events. Coverage gap (backend-qa, cycle-7 batch) — the completeBooking "
            + "counterpart of declineBooking's identical new test above; see that test's Javadoc for "
            + "why the real-DB concurrency IT alone is not a substitute for this fast, deterministic "
            + "signal.")
    void should_throw409AndNeverSave_when_completeBookingFreshnessRecheckFailsForStandaloneBooking() {
        UUID actorId = UUID.randomUUID();
        // Phase 27.1: assertElapsedForComplete requires now >= startsAt — an elapsed fixture, so the
        // 409 below is proven to come from the freshness recheck, not the elapsed guard.
        Booking booking = buildElapsedBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // Overrides setUp()'s lenient "still CONFIRMED" default: a concurrent client cancel already
        // moved this row off CONFIRMED between the load above and this recheck.
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.completeBooking(actorId, bookingId))
                .as("a lost freshness race must surface as a clean 409, not a silent overwrite of the "
                        + "client's cancellation")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(booking.getStatus())
                .as("the in-memory entity must be left untouched — no partial mutation before the throw")
                .isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any());
        verify(outboxService, never()).enqueueStatusChanged(any());
        verify(outboxService, never()).enqueueReviewRequested(any());
    }

    @Test
    @DisplayName("revenue-dashboard cache is evicted for the actor when a booking is completed")
    void should_evictRevenueDashboardCache_when_bookingCompleted() {
        // Arrange
        UUID actorId = UUID.randomUUID();
        // Phase 27.1: assertElapsedForComplete requires now >= startsAt — an elapsed fixture.
        Booking booking = buildElapsedBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        Cache masterCalendarCacheMock = mock(Cache.class);
        Cache revenueCacheMock = mock(Cache.class);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        when(cacheManager.getCache("master-calendar")).thenReturn(masterCalendarCacheMock);
        when(cacheManager.getCache("revenue-dashboard")).thenReturn(revenueCacheMock);

        // Act
        bookingService.completeBooking(actorId, bookingId);

        // Assert
        verify(cacheManager).getCache("revenue-dashboard");
        verify(revenueCacheMock).clear();
    }

    // ── notCompleteBooking ─────────────────────────────────────────────────────

    @Test
    @DisplayName("booking moves to NOT_COMPLETED with CLIENT_NO_SHOW reason when master records a no-show on an ELAPSED booking")
    void should_markNotCompleted_when_masterRecordsNoShow() {
        UUID actorId = UUID.randomUUID();
        // not-complete has no temporal guard — an elapsed booking is just the conventional
        // no-show fixture here, not a requirement (see the future-booking variant below).
        Booking booking = buildElapsedBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, "No show");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.notCompleteBooking(actorId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.NOT_COMPLETED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.CLIENT_NO_SHOW);
        verify(outboxService).enqueueStatusChanged(bookingId);
    }

    @Test
    @DisplayName("booking moves to NOT_COMPLETED when not-complete is called on a booking that has not started yet (not-complete has no temporal guard)")
    void should_markNotCompleted_when_notCompleteCalledOnFutureBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.notCompleteBooking(actorId, bookingId, req);

        assertThat(booking.getStatus())
                .as("a not-yet-started booking may still be marked not-completed")
                .isEqualTo(BookingStatus.NOT_COMPLETED);
        verify(outboxService).enqueueStatusChanged(bookingId);
    }

    @Test
    @DisplayName("availability caches are evicted by master when a FUTURE booking is marked not-completed — "
            + "NOT_COMPLETED leaves the `status = 'CONFIRMED'` occupancy predicate, so the slot is freed "
            + "and must reappear in the picker at once instead of staying hidden for the availability TTL")
    void should_evictMasterAvailabilityCaches_when_futureBookingMarkedNotCompleted() {
        // Arrange — a future booking: no-show has NO temporal guard, so this is the case where the
        // freed window is genuinely still bookable and the missing eviction was observable.
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, "No show");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        // Act
        bookingService.notCompleteBooking(actorId, bookingId, req);

        // Assert
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
    }

    @Test
    @DisplayName("ForbiddenException is thrown when an unauthorized actor attempts to mark a booking not-completed")
    void should_throwForbidden_when_unauthorizedActorMarksNotCompleted() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanCancelBooking(actorId, booking);

        assertThatThrownBy(() -> bookingService.notCompleteBooking(actorId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("400 is thrown when not-complete is called without a cancellation reason")
    void should_throw400_when_notCompleteCalledWithoutCancellationReason() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(null, "some comment");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.notCompleteBooking(actorId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("400 is thrown when not-complete is called on a booking that is not CONFIRMED (assertTransition guard)")
    void should_throw400_when_notCompleteCalledOnNonConfirmedBooking() {
        UUID actorId = UUID.randomUUID();
        // DECLINED exercises the assertTransition(booking, CONFIRMED, NOT_COMPLETED) guard — a
        // booking the provider already declined can never also be marked a no-show.
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.DECLINED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.notCompleteBooking(actorId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(booking.getStatus())
                .as("status must remain unchanged after a rejected not-complete transition")
                .isEqualTo(BookingStatus.DECLINED);
        verify(outboxService, never()).enqueueStatusChanged(bookingId);
    }

    @Test
    @DisplayName("notCompleteBooking — G5 freshness re-check rejects a STANDALONE booking a concurrent "
            + "client cancel already moved off CONFIRMED between the load and this recheck: 409, no "
            + "save, no outbox events. Coverage gap (backend-qa, cycle-7 batch) — the "
            + "notCompleteBooking counterpart of declineBooking's identical new test above; see that "
            + "test's Javadoc for why the real-DB concurrency IT alone is not a substitute for this "
            + "fast, deterministic signal.")
    void should_throw409AndNeverSave_when_notCompleteBookingFreshnessRecheckFailsForStandaloneBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, "No show");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // Overrides setUp()'s lenient "still CONFIRMED" default: a concurrent client cancel already
        // moved this row off CONFIRMED between the load above and this recheck.
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.notCompleteBooking(actorId, bookingId, req))
                .as("a lost freshness race must surface as a clean 409, not a silent overwrite of the "
                        + "client's cancellation")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(booking.getStatus())
                .as("the in-memory entity must be left untouched — no partial mutation before the throw")
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getProviderComment())
                .as("providerComment must never be written once the recheck aborts")
                .isNull();
        verify(bookingRepository, never()).save(any());
        verify(outboxService, never()).enqueueStatusChanged(any());
    }

    @Test
    @DisplayName("revenue-dashboard cache is evicted for the actor when a booking is marked not-completed")
    void should_evictRevenueDashboardCache_when_bookingMarkedNotCompleted() {
        // Arrange
        UUID actorId = UUID.randomUUID();
        // Elapsed fixture — see should_markNotCompleted_when_masterRecordsNoShow above.
        Booking booking = buildElapsedBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, "No show");
        Cache masterCalendarCacheMock = mock(Cache.class);
        Cache revenueCacheMock = mock(Cache.class);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        when(cacheManager.getCache("master-calendar")).thenReturn(masterCalendarCacheMock);
        when(cacheManager.getCache("revenue-dashboard")).thenReturn(revenueCacheMock);

        // Act
        bookingService.notCompleteBooking(actorId, bookingId, req);

        // Assert
        verify(cacheManager).getCache("revenue-dashboard");
        verify(revenueCacheMock).clear();
    }

    // ── cancelBooking ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("CONFIRMED booking moves to CANCELLED and slot cache is evicted when client cancels")
    void should_cancelBooking_when_clientCancelsConfirmedBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outboxService).enqueueStatusChanged(bookingId);
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
    }

    @Test
    @DisplayName("ForbiddenException is thrown when a different client attempts to cancel another client's booking")
    void should_throwForbidden_when_differentClientAttemptsToCancel() {
        // Role guard is at the controller layer (hasRole CLIENT).
        // At the service layer the ownership check fires: booking.client.id must equal actorId.
        UUID differentClientId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        // differentClientId != client.id → ownership check throws ForbiddenException
        assertThatThrownBy(() -> bookingService.cancelBooking(differentClientId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("ForbiddenException is thrown at the service ownership check when a salon owner attempts to cancel")
    void should_throwForbidden_when_salonOwnerAttemptsToCancel() {
        // A SALON_OWNER UUID that does NOT match the booking's client UUID must be rejected
        // by the ownership check inside cancelBooking (controller already blocks non-CLIENT
        // via @PreAuthorize; this test covers the service-level ownership guard in isolation).
        UUID salonOwnerUserId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(salonOwnerUserId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("400 is thrown when a client attempts to cancel an already COMPLETED booking")
    void should_throw400_when_clientCancelsCompletedBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("400 is thrown when a client attempts to cancel a booking already in DECLINED status")
    void should_throwException_when_cancelBooking_alreadyDeclined() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.DECLINED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("400 is thrown when a client attempts to cancel a booking already in NOT_COMPLETED status")
    void should_throwException_when_cancelBooking_alreadyNotCompleted() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.NOT_COMPLETED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("403 ForbiddenException (not a 500 NPE) is thrown when an authenticated CLIENT attempts to "
            + "cancel a guest (LINK / null-client) booking — regression for the missing null guard on "
            + "booking.getClient().getId() at cancelBooking L400")
    void should_return403NotCrash_when_clientCancelsGuestBooking() {
        // Guest (LINK) booking: CONFIRMED with a null client (V89 chk_bookings_guest_fields — a
        // guest has no account). Before the fix, cancelBooking dereferenced
        // booking.getClient().getId() unconditionally, so any authenticated client hitting
        // PATCH /bookings/{guestBookingId}/cancel would NPE into an unhandled 500 instead of the
        // correct 403 "this isn't your booking" — this asserts the guarded 403 outcome.
        Booking guestBooking = buildBooking(bookingId, null, master, msa, BookingStatus.CONFIRMED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .as("a null-client (guest) booking must be treated as not-owned-by-this-client (403), "
                        + "never as an unhandled NullPointerException/500")
                .isInstanceOf(ForbiddenException.class);
        assertThat(guestBooking.getStatus())
                .as("a rejected cancel attempt must not mutate the guest booking's status")
                .isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any());
        verify(outboxService, never()).enqueueStatusChanged(any());
    }

    // ── track 27.x widening — CLIENT cancel of a multi-service visit CHILD ─────────────────────
    //
    // Prior behaviour (pinned at IT level by BookingAppointmentChildTransitionGuardIT): an
    // appointment child (non-null booking.getAppointment()) was refused with a 409 by
    // assertNotAppointmentChild BEFORE the status guard ran. That call is now removed from
    // cancelBooking specifically (declineBooking/completeBooking/notCompleteBooking still call it
    // unchanged) — these two tests pin the widened contract: the cancel succeeds, and the visit
    // header recompute is delegated to AppointmentTransitionService rather than reimplemented here.

    @Test
    @DisplayName("cancelling an appointment CHILD succeeds (the assertNotAppointmentChild 409 no longer "
            + "fires for cancelBooking) and delegates the header recompute to the two-phase "
            + "AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel (BEFORE this "
            + "child's own save) / #collapseAppointmentHeaderAfterClientItemCancel (AFTER) — cycle-2 "
            + "audit finding 1, canonical appointments-before-bookings lock order")
    void should_cancelAppointmentChildAndDelegateHeaderRecompute_when_clientCancelsOneLegOfAVisit() {
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = Appointment.builder().id(appointmentId).build();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        booking.setAppointment(appointment);
        CancelBookingRequest req = new CancelBookingRequest(
                CancellationReason.CLIENT_CANCELLED, "одну послугу не потрібно");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        when(appointmentTransitionService.lockAppointmentHeaderBeforeClientItemCancel(appointmentId))
                .thenReturn(true);
        // F1 freshness re-check (cycle-6 audit 2026-08-03) — this child is still CONFIRMED as of
        // the post-lock scalar probe (no concurrent writer raced this test's single-threaded call).
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(true);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus())
                .as("the widened cancel must still move THIS child to CANCELLED")
                .isEqualTo(BookingStatus.CANCELLED);

        InOrder inOrder = inOrder(appointmentTransitionService, bookingRepository);
        inOrder.verify(appointmentTransitionService).lockAppointmentHeaderBeforeClientItemCancel(appointmentId);
        inOrder.verify(bookingRepository).save(any());
        inOrder.verify(appointmentTransitionService)
                .collapseAppointmentHeaderAfterClientItemCancel(appointmentId, true, "одну послугу не потрібно");
        verify(outboxService).enqueueStatusChanged(bookingId);
    }

    @Test
    @DisplayName("cancelling a LEGACY standalone booking (appointment_id NULL) never touches "
            + "AppointmentTransitionService — the widening is additive, byte-for-byte unchanged for "
            + "the non-appointment path")
    void should_notInteractWithAppointmentTransitionService_when_clientCancelsLegacyStandaloneBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verifyNoInteractions(appointmentTransitionService);
    }

    @Test
    @DisplayName("cancelBooking — G4 freshness re-check rejects a STANDALONE booking that already left "
            + "CONFIRMED between the load and this recheck: 409, no save, no mutation, no notification. "
            + "Coverage gap (backend-qa, cycle-7 batch): every prior negative-freshness test exercised "
            + "the appointment-child path only (e.g. AppointmentTransitionServiceTest's post-lock "
            + "freshness-loss case) — this is the standalone counterpart for cancelBooking.")
    void should_throw409AndNeverSave_when_cancelBookingFreshnessRecheckFailsForStandaloneBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // Overrides setUp()'s lenient "still CONFIRMED" default: a concurrent writer already moved
        // this row off CONFIRMED between the load above and this recheck.
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .as("a lost freshness race must surface as a clean 409, not a silent overwrite")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(booking.getStatus())
                .as("the in-memory entity must be left untouched — no partial mutation before the throw")
                .isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any());
        verify(outboxService, never()).enqueueStatusChanged(any());
        verifyNoInteractions(appointmentTransitionService);
    }

    // ── phase 30.2 — reschedule header lock (closes the latent lock-order defect) ──────────────
    //
    // rescheduleBooking already moved a single appointment child, but — unlike cancelBooking above
    // — never locked the visit HEADER, inverting the canonical appointments-before-bookings lock
    // order. These two tests are the executable form of the "zero extra statements on the legacy
    // path" guard: an appointment-child reschedule MUST invoke the new header-lock seam, and a
    // standalone (appointment == null) reschedule MUST NEVER invoke it.

    @Test
    @DisplayName("rescheduling an appointment CHILD locks the visit header via "
            + "AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule BEFORE the "
            + "client/master advisory locks — canonical appointments-before-bookings lock order "
            + "(phase 30.2, cycle-2 audit finding 1)")
    void should_lockAppointmentHeaderBeforeItemReschedule_when_reschedulingAppointmentChild() {
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = Appointment.builder().id(appointmentId).build();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        booking.setAppointment(appointment);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(appointmentTransitionService.lockAppointmentHeaderBeforeItemReschedule(appointmentId))
                .thenReturn(true);

        bookingService.rescheduleBooking(clientId, bookingId, req);

        assertThat(booking.getStartsAt()).isEqualTo(newStartsAt);
        InOrder inOrder = inOrder(appointmentTransitionService, bookingRepository);
        inOrder.verify(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(appointmentId);
        inOrder.verify(bookingRepository).acquireAdvisoryLock(masterId);
        inOrder.verify(bookingRepository).saveAndFlush(any());
        // No phase-2 collapse call exists for reschedule (phase 30.2 D2) — the item stays CONFIRMED.
        verify(appointmentTransitionService, never())
                .collapseAppointmentHeaderAfterClientItemCancel(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("rescheduling a LEGACY standalone booking (appointment_id NULL) never touches "
            + "AppointmentTransitionService — zero extra statements on the non-appointment path "
            + "(phase 30.2 D3)")
    void should_notInteractWithAppointmentTransitionService_when_reschedulingLegacyStandaloneBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        bookingService.rescheduleBooking(clientId, bookingId, req);

        assertThat(booking.getStartsAt()).isEqualTo(newStartsAt);
        verifyNoInteractions(appointmentTransitionService);
    }

    @Test
    @DisplayName("rescheduleBooking — G4 freshness re-check rejects a STANDALONE booking that already "
            + "left CONFIRMED between the load and this recheck: 409, no advisory locks, no save, no "
            + "mutation. Coverage gap (backend-qa, cycle-7 batch) — the standalone counterpart of "
            + "cancelBooking's identical new test above; every prior negative-freshness test exercised "
            + "the appointment-child path only.")
    void should_throw409AndNeverAcquireLocksOrSave_when_rescheduleBookingFreshnessRecheckFailsForStandaloneBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        // Overrides setUp()'s lenient "still CONFIRMED" default: a concurrent writer already moved
        // this row off CONFIRMED between the load above and this recheck (which runs BEFORE the
        // client/master advisory locks and existsOverlapExcluding — see rescheduleBooking's Javadoc).
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .as("a lost freshness race must surface as a clean 409, not a silent overwrite")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(booking.getStartsAt())
                .as("the in-memory entity must be left untouched — no partial mutation before the throw")
                .isNotEqualTo(newStartsAt);
        verify(bookingRepository, never()).acquireClientAdvisoryLockWithTimeout(any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
        verifyNoInteractions(appointmentTransitionService);
    }

    // ── elapsed-client guard (track 24.x — read-only-after-elapse) ─────────────
    //
    // BookingService#assertNotElapsedForClient(booking): rejects a CLIENT cancel/reschedule once
    // booking.endsAt is strictly before the injected Clock's instant (throws BookingElapsedException
    // → 409 BOOKING_ALREADY_ELAPSED). The comparison is on the ABSOLUTE instant via the pinned
    // Clock, so a booking's elapsed-ness is deterministic here (Clock.fixed in setUp). These pin the
    // branch, the strict-isBefore boundary, the guard ordering (after the status guard), and the
    // server-authority property (no request field can flip the verdict). The full HTTP contract +
    // provider-still-allowed matrix lives in BookingElapsedClientGuardIT.

    /** Builds a CONFIRMED-by-default booking whose slot ends at an exact instant relative to the pinned Clock. */
    private Booking buildBookingEndingAt(BookingStatus status, Instant endsAtInstant) {
        OffsetDateTime endsAt = OffsetDateTime.ofInstant(endsAtInstant, KYIV);
        Booking b = Booking.builder()
                .client(client)
                .master(master)
                .masterService(msa)
                .status(status)
                .startsAt(endsAt.minusHours(1))
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("200.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        setField(b, "id", bookingId);
        ReflectionTestUtils.setField(b, "createdAt", Instant.now());
        return b;
    }

    /** Matrix #1 (service level). Elapsed CONFIRMED cancel → BookingElapsedException, no mutation, no side effects. */
    @Test
    @DisplayName("cancel — elapsed CONFIRMED booking throws BookingElapsedException (409 BOOKING_ALREADY_ELAPSED); status unchanged, no save/outbox")
    void should_throwBookingElapsed_when_clientCancelsElapsedConfirmedBooking() {
        Booking booking = buildBookingEndingAt(BookingStatus.CONFIRMED, clock.instant().minusSeconds(60));
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .as("a client cancel of an already-ended booking must be a 409 elapsed conflict")
                .isInstanceOf(BookingElapsedException.class)
                .satisfies(ex -> assertThat(((BookingElapsedException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(booking.getStatus())
                .as("a rejected cancel must leave the booking CONFIRMED — the provider still owns resolution")
                .isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any());
        verify(outboxService, never()).enqueueStatusChanged(any());
    }

    /** Matrix #2 (service level). Elapsed CONFIRMED reschedule → BookingElapsedException BEFORE any slot lookup/persist. */
    @Test
    @DisplayName("reschedule — elapsed CONFIRMED booking throws BookingElapsedException before any slot lookup/lock/persist")
    void should_throwBookingElapsed_when_clientReschedulesElapsedConfirmedBooking() {
        Booking booking = buildBookingEndingAt(BookingStatus.CONFIRMED, clock.instant().minusSeconds(60));
        // A perfectly valid FUTURE target time — proves the client cannot escape the guard by
        // supplying a good newStartsAt: the verdict is on the SOURCE booking's persisted endsAt.
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime(), false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BookingElapsedException.class);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        // Guard short-circuits ahead of the whole reschedule critical section (Q6 verify-not-called).
        verify(slotCalculationService, never()).getAvailableSlots(
                any(), any(), any(UUID.class), nullable(MasterServiceAssignment.class));
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any(), anyBoolean());
    }

    /** Matrix #3 (boundary, strictly before). endsAt one nanosecond before now → elapsed → rejected. */
    @Test
    @DisplayName("boundary — endsAt exactly 1ns BEFORE the Clock instant is elapsed → cancel rejected (strict isBefore, lower side)")
    void should_reject_when_endsAtIsOneNanoBeforeNow() {
        Booking booking = buildBookingEndingAt(BookingStatus.CONFIRMED, clock.instant().minusNanos(1));
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .as("endsAt < now (by 1ns) must be treated as elapsed")
                .isInstanceOf(BookingElapsedException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any());
    }

    /** Matrix #3 (boundary, equal). endsAt == now → NOT before → NOT elapsed → cancel ALLOWED (documented isBefore semantics). */
    @Test
    @DisplayName("boundary — endsAt EXACTLY EQUAL to the Clock instant is NOT elapsed → cancel allowed (isBefore is strict; equal passes)")
    void should_allowCancel_when_endsAtExactlyEqualsNow() {
        Booking booking = buildBookingEndingAt(BookingStatus.CONFIRMED, clock.instant());
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus())
                .as("endsAt == now is the last non-elapsed instant (guard uses strict isBefore) — cancel must succeed")
                .isEqualTo(BookingStatus.CANCELLED);
        verify(outboxService).enqueueStatusChanged(bookingId);
    }

    /** Matrix #3 (boundary, strictly after). endsAt one nanosecond after now → not elapsed → cancel allowed. */
    @Test
    @DisplayName("boundary — endsAt 1ns AFTER the Clock instant is not elapsed → cancel allowed (upper side)")
    void should_allowCancel_when_endsAtIsOneNanoAfterNow() {
        Booking booking = buildBookingEndingAt(BookingStatus.CONFIRMED, clock.instant().plusNanos(1));
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outboxService).enqueueStatusChanged(bookingId);
    }

    /** Matrix #7 (ordering). An elapsed but NON-CONFIRMED booking reports the STATUS conflict, not the elapsed code. */
    @Test
    @DisplayName("ordering — elapsed COMPLETED booking cancel reports the STATUS conflict (BAD_REQUEST), NOT BookingElapsedException (status guard runs first)")
    void should_reportStatusConflictNotElapsed_when_cancellingElapsedCompletedBooking() {
        Booking booking = buildBookingEndingAt(BookingStatus.COMPLETED, clock.instant().minusSeconds(60));
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .as("the more specific status conflict must win — the guard sits AFTER the status check")
                // BookingElapsedException extends BusinessException, so also assert it is NOT that subclass.
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(BookingElapsedException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Matrix #7 (ordering, reschedule). Same ordering guarantee on the reschedule path (CONFLICT, not elapsed). */
    @Test
    @DisplayName("ordering — elapsed COMPLETED booking reschedule reports the STATUS conflict (CONFLICT), NOT BookingElapsedException")
    void should_reportStatusConflictNotElapsed_when_reschedulingElapsedCompletedBooking() {
        Booking booking = buildBookingEndingAt(BookingStatus.COMPLETED, clock.instant().minusSeconds(60));
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime(), false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(BookingElapsedException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** Matrix #6 (server authority). The elapsed verdict is invariant to request contents — no client field can flip it. */
    @Test
    @DisplayName("server-authority — cancel of an elapsed booking is 409 regardless of request body contents (reason/comment); no client field flips the verdict")
    void should_rejectRegardlessOfRequestContents_when_cancellingElapsedBooking() {
        Instant elapsedEnd = clock.instant().minusSeconds(60);
        when(bookingRepository.findByIdWithFullGraph(bookingId))
                .thenAnswer(inv -> Optional.of(buildBookingEndingAt(BookingStatus.CONFIRMED, elapsedEnd)));

        // Two materially different bodies — different reason, with/without a free-text note. Neither
        // carries a timestamp, and the outcome is identical: the verdict depends ONLY on the server
        // Clock + persisted endsAt. This is precisely what defeats a device-clock rollback: there is
        // no client-supplied "now" to trust.
        for (CancelBookingRequest req : List.of(
                new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null),
                new CancelBookingRequest(CancellationReason.PROVIDER_UNAVAILABLE, "will not be reached"))) {
            assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                    .as("request contents cannot bypass the server-clock-authoritative elapsed guard")
                    .isInstanceOf(BookingElapsedException.class);
        }
        verify(bookingRepository, never()).save(any());
    }

    /** Matrix #4 (regression, cancel). A FUTURE CONFIRMED booking still cancels — the guard didn't break the normal path. */
    @Test
    @DisplayName("regression — a FUTURE CONFIRMED booking still cancels to CANCELLED (elapsed guard does not touch the normal path)")
    void should_stillCancelFutureBooking_afterElapsedGuardAdded() {
        Booking booking = buildBookingEndingAt(BookingStatus.CONFIRMED, clock.instant().plusSeconds(3600));
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outboxService).enqueueStatusChanged(bookingId);
    }

    /** Matrix #4 (regression, reschedule). A FUTURE CONFIRMED booking still reschedules to the new time. */
    @Test
    @DisplayName("regression — a FUTURE CONFIRMED booking still reschedules to the new time (elapsed guard does not touch the normal path)")
    void should_stillRescheduleFutureBooking_afterElapsedGuardAdded() {
        Booking booking = buildBookingEndingAt(BookingStatus.CONFIRMED, clock.instant().plusSeconds(3600));
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(6).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BookingDetailResponse result = bookingService.rescheduleBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getStartsAt()).isEqualTo(newStartsAt);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(outboxService).enqueueBookingRescheduled(bookingId, false);
    }

    // ── rescheduleBooking (Phase 19.2) ─────────────────────────────────────────

    /**
     * Stubs the working-hours oracle so {@code newStartsAt} resolves to an on-schedule slot,
     * and the advisory lock acquires successfully. Mirrors the create-path critical section.
     */
    private void stubRescheduleSlotAvailable(OffsetDateTime newStartsAt) {
        AvailableSlotResponse slot = new AvailableSlotResponse(
                newStartsAt.atZoneSameInstant(KYIV),
                newStartsAt.plusMinutes(60).atZoneSameInstant(KYIV));
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(LocalDate.class), eq(masterServiceId),
                nullable(MasterServiceAssignment.class)))
                .thenReturn(List.of(slot));
        // lenient: since the Phase 19.4 client-then-master reorder, the client-conflict test
        // that also calls this helper throws before the master lock is ever acquired, making
        // this stub unreachable on that path (by design — that IS the perf fix).
        org.mockito.Mockito.lenient()
                .when(bookingRepository.acquireAdvisoryLock(masterId)).thenReturn(1);
        // Reschedule success builds the enriched BookingDetailResponse via the label seam.
        // lenient: the 409-overlap test stubs the slot/lock but throws before enrichment.
        org.mockito.Mockito.lenient()
                .when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
    }

    @Test
    @DisplayName("CONFIRMED reschedule stays CONFIRMED, moves the time, and notifies the provider (no re-approval — track 24.x)")
    void should_stayConfirmed_when_clientReschedulesConfirmedBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BookingDetailResponse result = bookingService.rescheduleBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getStartsAt()).isEqualTo(newStartsAt);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(outboxService).enqueueBookingRescheduled(bookingId, false);
    }

    // TODO(24.7): a non-CONFIRMED reschedule source (formerly PENDING) has no replacement —
    // rescheduleBooking now rejects any non-CONFIRMED source with 409 (see
    // assertRescheduleRejectsTerminalState below, which already covers COMPLETED/CANCELLED/
    // DECLINED/NOT_COMPLETED as terminal 409 cases).

    @Test
    @DisplayName("403 ForbiddenException is thrown when a different client attempts to reschedule another client's booking")
    void should_throwForbidden_when_nonOwnerClientReschedules() {
        UUID otherClientId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime(), false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(otherClientId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);

        // Guard fires before any slot lookup / lock / persistence
        verify(slotCalculationService, never()).getAvailableSlots(
                any(), any(), any(UUID.class), nullable(MasterServiceAssignment.class));
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any(), anyBoolean());
    }

    @Test
    @DisplayName("403 ForbiddenException is thrown when the booking has no client (guest booking) so no actor can own it")
    void should_throwForbidden_when_reschedulingGuestBooking() {
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        setField(guestBooking, "client", null);
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime(), false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("409 is thrown when rescheduling a COMPLETED booking (terminal source state)")
    void should_throw409_when_reschedulingCompletedBooking() {
        assertRescheduleRejectsTerminalState(BookingStatus.COMPLETED);
    }

    @Test
    @DisplayName("409 is thrown when rescheduling a CANCELLED booking (terminal source state)")
    void should_throw409_when_reschedulingCancelledBooking() {
        assertRescheduleRejectsTerminalState(BookingStatus.CANCELLED);
    }

    @Test
    @DisplayName("409 is thrown when rescheduling a DECLINED booking (terminal source state)")
    void should_throw409_when_reschedulingDeclinedBooking() {
        assertRescheduleRejectsTerminalState(BookingStatus.DECLINED);
    }

    @Test
    @DisplayName("409 is thrown when rescheduling a NOT_COMPLETED booking (terminal source state)")
    void should_throw409_when_reschedulingNotCompletedBooking() {
        assertRescheduleRejectsTerminalState(BookingStatus.NOT_COMPLETED);
    }

    private void assertRescheduleRejectsTerminalState(BookingStatus terminal) {
        Booking booking = buildBooking(bookingId, client, master, msa, terminal);
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime(), false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(booking.getStatus()).isEqualTo(terminal);
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any(), anyBoolean());
    }

    @Test
    @DisplayName("400 is thrown when the new start time is below the 15-minute lead-time floor")
    void should_throw400_when_rescheduleNewTimeBelowLeadTime() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusMinutes(14).toOffsetDateTime(), false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("400 is thrown when the new start time is more than 180 days ahead")
    void should_throw400_when_rescheduleNewTimeMoreThan180DaysAhead() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusDays(181).toOffsetDateTime(), false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("409 'Slot not available' is thrown when the new time is off the master's schedule")
    void should_throw409_when_rescheduleNewTimeOffSchedule() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // No slot matches newStartsAt → off-schedule
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(LocalDate.class), eq(masterServiceId),
                nullable(MasterServiceAssignment.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        // Off-schedule rejected before the advisory lock / overlap check
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("409 is thrown when the new time overlaps another booking, with the booking's own row excluded via existsOverlapExcluding")
    void should_throw409_when_rescheduleOverlapsAnotherBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(true);

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        // Self-exclusion: overlap is checked excluding this booking's own id
        verify(bookingRepository).existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId));
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any(), anyBoolean());
    }

    @Test
    @DisplayName("409 CLIENT_BOOKING_CONFLICT is thrown when the new time overlaps another booking the "
            + "client holds with a DIFFERENT master, and the master-busy check never runs")
    void should_throwClientBookingConflict_when_rescheduleOverlapsClientsOtherBookingWithDifferentMaster() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);

        UUID otherMasterId = UUID.randomUUID();
        Master otherMaster = buildMaster(otherMasterId, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment otherMsa = buildMsa(UUID.randomUUID(), otherMaster, serviceDef, null, null);
        UUID conflictingBookingId = UUID.randomUUID();
        Booking conflicting = buildBooking(conflictingBookingId, client, otherMaster, otherMsa, BookingStatus.CONFIRMED);
        when(bookingRepository.findFirstConflictingClientBookingIdExcluding(
                eq(clientId), any(), any(), eq(bookingId)))
                .thenReturn(Optional.of(conflictingBookingId));
        when(bookingRepository.findByIdWithFullGraph(conflictingBookingId)).thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(ClientBookingConflictException.class)
                .satisfies(ex -> assertThat(((ClientBookingConflictException) ex).getConflictingBookingId())
                        .isEqualTo(conflictingBookingId));

        // Self-exclusion honoured: findFirstConflictingClientBookingIdExcluding was called with
        // this booking's own id, and — client-conflict wins deterministically, with the
        // Phase 19.4 client-then-master reorder — the master lock is never acquired, the
        // master-busy check never runs, and nothing is persisted.
        verify(bookingRepository).findFirstConflictingClientBookingIdExcluding(
                eq(clientId), any(), any(), eq(bookingId));
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).existsOverlapExcluding(any(), any(), any(), any());
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any(), anyBoolean());
    }

    @Test
    @DisplayName("price and frozen duration are preserved on reschedule; endsAt = newStartsAt + durationMinutesAtBooking + buffer")
    void should_freezePriceAndDuration_when_rescheduling() {
        // Original booking: price 200.00, duration 60, buffer 0. Build with a 90-min frozen
        // duration to prove endsAt derives from the frozen value, not a recomputed one.
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        setField(booking, "durationMinutesAtBooking", 90);
        setField(booking, "bufferMinutesAtBooking", 0);
        BigDecimal frozenPrice = booking.getPriceAtBooking();
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt, false);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        bookingService.rescheduleBooking(clientId, bookingId, req);

        assertThat(booking.getPriceAtBooking()).isEqualByComparingTo(frozenPrice);
        assertThat(booking.getDurationMinutesAtBooking()).isEqualTo(90);
        assertThat(booking.getEndsAt()).isEqualTo(newStartsAt.plusMinutes(90));
    }

    // TODO(24.7): the former "confirm after reschedule" test (reschedule reverts to PENDING,
    // then the provider re-confirms) has no replacement — confirmBooking() no longer exists and
    // reschedule no longer reverts. should_stayConfirmed_when_clientReschedulesConfirmedBooking
    // above already covers the reschedule-stays-CONFIRMED behavior.

    @Test
    @DisplayName("decline after reschedule moves the booking to DECLINED (provider cancels the new time)")
    void should_declineAfterReschedule_when_providerCancelsNewTime() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        bookingService.rescheduleBooking(clientId, bookingId, new RescheduleBookingRequest(newStartsAt, false));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        // Provider declines (cancels) the rescheduled booking → DECLINED, via the decline path.
        UUID providerId = UUID.randomUUID();
        StatusUpdateRequest declineReq = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, "Unavailable");
        when(bookingRepository.save(any())).thenReturn(booking);
        bookingService.declineBooking(providerId, bookingId, declineReq);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.DECLINED);
    }

    // ── getMyBookings (Phase 19.3 — enriched BookingDetailResponse) ──────────────

    private com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels emptyLabels() {
        return new com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels(Map.of(), Map.of());
    }

    /**
     * Phase 26.3: {@code BookingService#normalizeBookingSort} is called BEFORE the role dispatch
     * and unconditionally rewrites the caller's {@code Pageable} — defaulting an unsorted sort to
     * {@code startsAt DESC} and always appending an {@code id ASC} tiebreaker — before either the
     * client projection query or the provider ID-page query ever see it. A test that passes in a
     * bare {@code Pageable.unpaged()} must therefore stub/verify the repository call with THIS
     * normalized form, not the original unsorted instance — the two are no longer the same object
     * nor {@code equals()}, by design (see that method's javadoc).
     */
    private static Pageable normalizedUnpaged() {
        return Pageable.unpaged(Sort.by(Sort.Direction.DESC, "startsAt").and(Sort.by(Sort.Direction.ASC, "id")));
    }

    @Test
    @DisplayName("filtered bookings page is returned when salon owner queries with a specific status")
    void should_returnFilteredBookings_when_salonOwnerListsWithStatus() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(
                        List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                actorId, buildAuth(Role.SALON_OWNER), List.of(BookingStatus.CONFIRMED), null, null, null, pageable);

        assertThat(result.totalElements()).isZero();
        verify(bookingRepository)
                .findIdsBySalonIdsFiltered(List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("all bookings page is returned when salon owner queries without a status filter")
    void should_returnAllBookings_when_salonOwnerListsWithoutStatus() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("empty status list behaves exactly as absent — normalised to null, not an empty IN list "
            + "(Phase 26.1: ?status= must not match nothing)")
    void should_normalizeEmptyStatusList_to_null_when_salonOwnerListsWithEmptyStatusList() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), List.of(), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("repeated/duplicate statuses de-duplicate into a bounded EnumSet before hitting the repository "
            + "(Phase 26.1: ?status=CONFIRMED&status=CONFIRMED)")
    void should_deduplicateStatuses_when_salonOwnerRepeatsTheSameStatus() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(
                        List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER),
                List.of(BookingStatus.CONFIRMED, BookingStatus.CONFIRMED), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository)
                .findIdsBySalonIdsFiltered(List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("multiple distinct statuses widen into a single filtered query call carrying the whole EnumSet "
            + "(Phase 26.1: ?status=CANCELLED&status=DECLINED, union in one request)")
    void should_passUnionOfStatuses_when_salonOwnerListsWithMultipleStatuses() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Set<BookingStatus> expected = EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED);

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), expected, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER),
                List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdsFiltered(List.of(salonId), expected, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("mapped detail page is returned when salon owner lists with a non-empty page")
    void should_returnMappedBookings_when_salonOwnerListsWithNonEmptyPage() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking existingBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(existingBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.data()).hasSize(1);
    }

    @Test
    @DisplayName("guest (LINK) booking is mapped without NPE in the salon owner's booking list (GET /bookings/me)")
    void should_returnMappedGuestBooking_when_salonOwnerListsWithNonEmptyPage() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        setField(guestBooking, "client", null);
        setField(guestBooking, "guestName", "Тест");
        setField(guestBooking, "guestSurname", "Гість");

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(guestBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).clientId()).isNull();
        assertThat(result.data().get(0).clientFirstName()).isEqualTo("Тест");
        assertThat(result.data().get(0).clientLastName()).isEqualTo("Гість");
    }

    @Test
    @DisplayName("empty page is returned when a salon owner has no active salons")
    void should_returnEmptyPage_when_salonOwnerHasNoActiveSalons() {
        UUID actorId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        assertThat(result.data()).isEmpty();
        verify(bookingRepository, never()).findIdsBySalonIdsFiltered(any(), any(), any(), any(), any(), any());
    }

    // ── Phase 23.4 — GET /bookings/salon/{salonId}: BookingService#getSalonBookings ───────────
    //
    // Authorization (owner-or-admin OF THIS salon) lives entirely at the controller's
    // @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and
    // @authz.canManageSalon(authentication, #salonId)") gate — see BookingController and
    // BookingService#getSalonBookings' own javadoc for why (Anti-Bug §D: a GET is a read, so the
    // SpEL can* form is the canonical placement; duplicating it here would be the "same check on
    // both layers" anti-pattern §D forbids). These unit tests therefore exercise the service in
    // isolation from any Authentication/role — actorId is an opaque already-authorized UUID.

    @Test
    @DisplayName("BusinessException(400) when 'from' is after 'to' for GET /bookings/salon/{salonId}")
    void should_throwBadRequest_when_fromIsAfterToForSalonBookings() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getSalonBookings(
                        actorId, salonId, null, null,
                        LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 1), null, Pageable.unpaged()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("the 366-day span cap is delegated to ScheduleDateMath.assertSpanWithinMax for "
            + "GET /bookings/salon/{salonId}, not re-implemented inline")
    void should_delegateSpanCap_toScheduleDateMathForSalonBookings() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2027, 6, 1);

        org.mockito.Mockito.doThrow(
                        new BusinessException(HttpStatus.BAD_REQUEST, "Date range exceeds the maximum of 366 days"))
                .when(dateMath).assertSpanWithinMax(from, to);

        assertThatThrownBy(() -> bookingService.getSalonBookings(
                        actorId, salonId, null, null, from, to, null, Pageable.unpaged()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(dateMath).assertToPlusOneDayRepresentable(to);
        verify(dateMath).assertSpanWithinMax(from, to);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("repository is queried with the salonId scope, masterId filter, and a single-status "
            + "EnumSet — never a repeatable list, unlike GET /bookings/me's ?status=")
    void should_passSalonMasterAndStatusFilters_when_provided() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID filterMasterId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findIdsBySalonIdFiltered(
                        salonId, filterMasterId, Set.of(BookingStatus.CONFIRMED), null, null, null,
                        normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getSalonBookings(
                actorId, salonId, filterMasterId, List.of(BookingStatus.CONFIRMED), null, null, null, pageable);

        assertThat(result.totalElements()).isZero();
        verify(bookingRepository).findIdsBySalonIdFiltered(
                salonId, filterMasterId, Set.of(BookingStatus.CONFIRMED), null, null, null,
                normalizedUnpaged());
    }

    @Test
    @DisplayName("omitted masterId/status pass through as null — no dead-branch sentinel, mirroring "
            + "the optional-predicate contract every other BookingRepositoryCustom method carries")
    void should_passNullFilters_when_masterIdAndStatusOmitted() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getSalonBookings(actorId, salonId, null, null, null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("empty id page short-circuits before hydrate — findAllByIdsWithGraph is never called")
    void should_shortCircuit_when_salonHasNoMatchingBookings() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getSalonBookings(actorId, salonId, null, null, null, null, null, pageable);

        assertThat(result.data()).isEmpty();
        verify(bookingRepository, never()).findAllByIdsWithGraph(any());
    }

    @Test
    @DisplayName("mapped detail page is returned when the salon has a non-empty page")
    void should_returnMappedBookings_when_salonHasNonEmptyPage() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking existingBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(existingBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getSalonBookings(actorId, salonId, null, null, null, null, null, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.data()).hasSize(1);
    }

    @Test
    @DisplayName("Phase 320 — providerCanReviewClient on the salon board is the PERFORMING-MASTER "
            + "term alone (AuthorizationService#isPerformingMasterOfBooking), evaluated in memory "
            + "for a COMPLETED booking with a registered client. The batched salon-ownership "
            + "lookup the Phase 319 N+1 fix introduced is gone with the salon arm it resolved: an "
            + "owner or admin may still COMPLETE the booking, but only the master who performed it "
            + "may rate its client, so hasProviderAuthorityOverBooking must never be consulted "
            + "here — neither per row nor batched.")
    void should_computeProviderCanReviewClient_when_bookingIsCompletedWithRegisteredClient() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking completedBooking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(completedBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(clientReviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(authz.isPerformingMasterOfBooking(actorId, completedBooking)).thenReturn(true);
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getSalonBookings(actorId, salonId, null, null, null, null, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).providerCanReviewClient()).isTrue();
        verify(authz).isPerformingMasterOfBooking(actorId, completedBooking);
        verify(authz, never()).hasProviderAuthorityOverBooking(any(), any());
    }

    @Test
    @DisplayName("Phase 320 — the salon board row of a booking the actor did NOT perform reads "
            + "providerCanReviewClient FALSE even though the actor is the SALON_OWNER who may "
            + "complete it: the locked decision hands the review to the performing master alone")
    void should_returnProviderCanReviewClientFalse_when_actorIsNotThePerformingMaster() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking completedBooking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(completedBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(authz.isPerformingMasterOfBooking(actorId, completedBooking)).thenReturn(false);
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getSalonBookings(actorId, salonId, null, null, null, null, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).providerCanReviewClient())
                .as("a non-performer reads false regardless of what salon authority would have said")
                .isFalse();
        verify(authz, never()).hasProviderAuthorityOverBooking(any(), any());
        // The unscoped client-review probe finding (backend-security 2026-09-20): the
        // client_reviews probe is now narrowed to the page's provider
        // authority BEFORE it is issued, so for a non-performing actor it is never issued at all.
        // The stub this test used to carry for it became an UnnecessaryStubbing, which is the
        // strict-stubbing engine reporting exactly the round trip the narrowing removed. Asserted
        // as a verify() so the saving is PINNED rather than merely un-stubbed: re-widening the call
        // site turns this green-by-accident case red.
        verify(clientReviewRepository, never()).findReviewedBookingIds(any());
    }

    @Test
    @DisplayName("the per-row provider-authority check is skipped entirely for a CONFIRMED (not yet "
            + "review-eligible) booking — the cost gate must short-circuit before authz is ever "
            + "consulted, bounding the extra query count to actual review candidates only")
    void should_skipProviderAuthorityCheck_when_bookingIsNotReviewEligible() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking confirmedBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(confirmedBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getSalonBookings(actorId, salonId, null, null, null, null, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).providerCanReviewClient()).isFalse();
        verify(authz, never()).hasProviderAuthorityOverBooking(any(), any());
        // Phase 320 — the cost gate must short-circuit before the performing-master term is
        // evaluated at all. Asserted against isPerformingMasterOfBooking, the collaborator the
        // candidate filter actually guards: without this the assertion above would be vacuous,
        // since hasProviderAuthorityOverBooking is no longer called on this path under ANY input.
        verify(authz, never()).isPerformingMasterOfBooking(any(), any());
        verifyNoInteractions(clientReviewRepository);
    }

    // ── Phase 319 — GET /bookings/salon/{salonId} filter parity with GET /bookings/me ────────
    //
    // The mobile salon «Записи» board previously narrowed status and service CLIENT-side over a
    // page that truncates at 100 (spring.data.web.pageable.max-page-size, Anti-Bug §J), so a salon
    // day with more than 100 bookings filtered a TRUNCATED set. These tests pin the predicates
    // reaching the repository instead, and the normalisation that must happen exactly once.

    @Test
    @DisplayName("a repeatable status list reaches the repository as a multi-value EnumSet — the "
            + "server-side predicate mobile needs, not the one-element Set the pre-319 single "
            + "?status= produced")
    void should_passMultiValueStatusSet_when_statusListHasSeveralValues() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Set<BookingStatus> expected = EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);

        when(bookingRepository.findIdsBySalonIdFiltered(
                        salonId, null, expected, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getSalonBookings(
                actorId, salonId,
                null, List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED),
                null, null, null, Pageable.unpaged());

        assertThat(result.totalElements()).isZero();
        verify(bookingRepository).findIdsBySalonIdFiltered(
                salonId, null, expected, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("a single-element status list still reaches the repository as the same one-element "
            + "EnumSet the pre-319 single ?status= produced — this is what makes the widening "
            + "backward compatible for every existing caller")
    void should_passOneElementStatusSet_when_statusListHasOneValue() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFiltered(
                        salonId, null, Set.of(BookingStatus.CANCELLED), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, null, List.of(BookingStatus.CANCELLED), null, null, null, Pageable.unpaged());

        verify(bookingRepository).findIdsBySalonIdFiltered(
                salonId, null, Set.of(BookingStatus.CANCELLED), null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("an EMPTY status list passes through as null, never an empty Set — an empty Set "
            + "would compile to a dead IN () and EnumSet.copyOf would throw on it outright")
    void should_passNullStatuses_when_statusListIsEmpty() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        // serviceId is null, not List.of(), so this test isolates the STATUS arm: an empty
        // serviceId list has its own pin below (should_passNullServiceIds_when_serviceIdListIsEmpty)
        // and must not be able to keep this one green, nor this one keep that one green.
        bookingService.getSalonBookings(
                actorId, salonId, null, List.of(), null, null, null, Pageable.unpaged());

        verify(bookingRepository).findIdsBySalonIdFiltered(
                salonId, null, null, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("an EMPTY serviceId list passes through as null on the SALON path too, never an "
            + "empty Set — the salon-path twin of the status pin above (backend-perf LOW, QA 2026-09-16)")
    void should_passNullServiceIds_when_serviceIdListIsEmpty() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFiltered(salonId, null, null, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, null, null, null, null, List.of(), Pageable.unpaged());

        var captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(bookingRepository).findIdsBySalonIdFiltered(
                eq(salonId), eq(null), eq(null), eq(null), eq(null), captor.capture(),
                eq(normalizedUnpaged()));
        assertThat(captor.getValue())
                .as("normalizeServiceIdFilter must hand the repository null, never an empty Set — "
                        + "that is the contract EVERY BookingRepositoryCustom javadoc states, and "
                        + "today it is held up ONLY by applyServiceFilter's defensive "
                        + "null-or-empty guard. Nothing pins the service side of it, so a "
                        + "regression to Set.of() would be invisible until a caller that is not "
                        + "that helper (a native query, or a JPQL ':serviceIds IS NULL OR' arm) "
                        + "turned it into a dead IN () matching nothing")
                .isNull();
    }

    @Test
    @DisplayName("a repeatable serviceId list reaches the repository de-duplicated and "
            + "insertion-ordered, matching MasterService ids — the server-side service predicate")
    void should_passDeduplicatedServiceIds_when_serviceIdListProvided() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceA = UUID.randomUUID();
        UUID serviceB = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFiltered(
                        salonId, null, null, null, null, Set.of(serviceA, serviceB), normalizedUnpaged()))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, null, null, null, null,
                List.of(serviceA, serviceB, serviceA), Pageable.unpaged());

        var captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(bookingRepository).findIdsBySalonIdFiltered(
                eq(salonId), eq(null), eq(null), eq(null), eq(null), captor.capture(),
                eq(normalizedUnpaged()));
        assertThat(captor.getValue())
                .as("the duplicate must collapse before the IN list is built")
                .containsExactly(serviceA, serviceB);
    }

    @Test
    @DisplayName("BusinessException(400) when more than 50 distinct serviceId values are supplied — "
            + "the same MAX_SERVICE_ID_FILTER bound GET /bookings/me enforces, hit BEFORE any query "
            + "runs (Anti-Bug §B1: bounded collections only)")
    void should_throwBadRequest_when_salonServiceIdFilterExceedsCap() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(51).toList();

        assertThatThrownBy(() -> bookingService.getSalonBookings(
                        actorId, salonId, null, null, null, null, tooMany, Pageable.unpaged()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    // ── Phase 322 — GET /bookings/salon/{salonId}?partition= (the salon «Архів» read) ─────────
    //
    // Two contracts live here, both copied verbatim from the /bookings/me precedent rather than
    // re-decided:
    //   D2 — partition ABSENT is byte-identical to pre-322. Held at the TYPE level: the 8-arg
    //        overload delegates with partition = null and the non-partition repository sibling is
    //        the one invoked, so the eighteen pre-322 getSalonBookings tests above are themselves
    //        the regression proof and needed no edit.
    //   D3 — partition PRESENT makes `status` IGNORED, never a 400. Enforced by the ternary in
    //        getSalonBookings AND by the repository sibling having no `statuses` parameter at all.

    @Test
    @DisplayName("Phase 322 D2 — partition ABSENT still routes to findIdsBySalonIdFiltered; the "
            + "partition sibling is never touched, which is what makes the absent case byte-identical")
    void should_useNonPartitionSibling_when_salonPartitionIsAbsent() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFiltered(
                        salonId, null, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, null, List.of(BookingStatus.CONFIRMED), null, null, null, null,
                Pageable.unpaged());

        verify(bookingRepository).findIdsBySalonIdFiltered(
                salonId, null, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged());
        verify(bookingRepository, never()).findIdsBySalonIdFilteredByPartition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Phase 322 D2 — the 8-argument overload is not a second implementation: it delegates "
            + "to the 9-argument one with partition = null, reaching the SAME non-partition sibling "
            + "with the SAME arguments as an explicit null does")
    void should_delegateWithNullPartition_when_eightArgOverloadIsCalled() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID filterMasterId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFiltered(
                        salonId, filterMasterId, Set.of(BookingStatus.COMPLETED), null, null, null,
                        normalizedUnpaged()))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, filterMasterId, List.of(BookingStatus.COMPLETED), null, null, null,
                Pageable.unpaged());

        verify(bookingRepository).findIdsBySalonIdFiltered(
                salonId, filterMasterId, Set.of(BookingStatus.COMPLETED), null, null, null,
                normalizedUnpaged());
        verify(bookingRepository, never()).findIdsBySalonIdFilteredByPartition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Phase 322 D3 — partition PRESENT routes to findIdsBySalonIdFilteredByPartition, "
            + "and the supplied `status` is IGNORED rather than rejected: the partition sibling has "
            + "no statuses parameter, so no status predicate is even constructible")
    void should_ignoreStatus_when_salonPartitionIsSupplied() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFilteredByPartition(
                        eq(salonId), eq(null), eq(BookingPartition.HISTORY), any(OffsetDateTime.class),
                        eq(null), eq(null), eq(null), eq(normalizedUnpaged())))
                .thenReturn(Page.empty());

        var result = bookingService.getSalonBookings(
                actorId, salonId, null, List.of(BookingStatus.CONFIRMED), null, null, null,
                BookingPartition.HISTORY, Pageable.unpaged());

        assertThat(result.totalElements()).isZero();
        verify(bookingRepository).findIdsBySalonIdFilteredByPartition(
                eq(salonId), eq(null), eq(BookingPartition.HISTORY), any(OffsetDateTime.class),
                eq(null), eq(null), eq(null), eq(normalizedUnpaged()));
        verify(bookingRepository, never()).findIdsBySalonIdFiltered(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Phase 322 D5 — masterId, from/to and serviceId all compose with partition as AND: "
            + "every one of them reaches the partition sibling alongside it, not instead of it")
    void should_composeMasterIdDateRangeAndServiceIds_when_salonPartitionIsSupplied() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID filterMasterId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2031, 6, 1);
        LocalDate to = LocalDate.of(2031, 6, 10);
        OffsetDateTime expectedFrom = from.atStartOfDay(KYIV).toOffsetDateTime();
        OffsetDateTime expectedToExclusive = to.plusDays(1).atStartOfDay(KYIV).toOffsetDateTime();

        when(bookingRepository.findIdsBySalonIdFilteredByPartition(
                        eq(salonId), eq(filterMasterId), eq(BookingPartition.PAST), any(OffsetDateTime.class),
                        eq(expectedFrom), eq(expectedToExclusive), eq(Set.of(serviceId)),
                        eq(normalizedUnpaged())))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, filterMasterId, null, from, to, List.of(serviceId),
                BookingPartition.PAST, Pageable.unpaged());

        verify(bookingRepository).findIdsBySalonIdFilteredByPartition(
                eq(salonId), eq(filterMasterId), eq(BookingPartition.PAST), any(OffsetDateTime.class),
                eq(expectedFrom), eq(expectedToExclusive), eq(Set.of(serviceId)),
                eq(normalizedUnpaged()));
    }

    @Test
    @DisplayName("Phase 322 D7 — the `now` handed to the partition boundary is the injected Clock's "
            + "own instant, resolved once: never OffsetDateTime.now(), and never a Kyiv-zoned value")
    void should_passClockInstantAsPartitionNow_when_salonPartitionIsSupplied() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFilteredByPartition(
                        any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, null, null, null, null, null,
                BookingPartition.HISTORY, Pageable.unpaged());

        ArgumentCaptor<OffsetDateTime> now = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(bookingRepository).findIdsBySalonIdFilteredByPartition(
                eq(salonId), eq(null), eq(BookingPartition.HISTORY), now.capture(),
                eq(null), eq(null), eq(null), eq(normalizedUnpaged()));
        assertThat(now.getValue().toInstant())
                .as("the partition boundary must be the injected Clock's instant — a wall-clock read "
                        + "would make this untestable and would drift from the awaitingClosure flag")
                .isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("Phase 322 — the 50-entry serviceId cap and the from>to guard still fire BEFORE any "
            + "query when a partition is supplied: partition must not become a validation bypass")
    void should_stillValidate_when_salonPartitionIsSupplied() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(51).toList();

        assertThatThrownBy(() -> bookingService.getSalonBookings(
                        actorId, salonId, null, null, null, null, tooMany,
                        BookingPartition.HISTORY, Pageable.unpaged()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> bookingService.getSalonBookings(
                        actorId, salonId, null, null,
                        LocalDate.of(2031, 7, 31), LocalDate.of(2031, 7, 1), null,
                        BookingPartition.HISTORY, Pageable.unpaged()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
    }

    // ── Phase 319 step 5 — REJECTED FINDING, NOW PINNED AS ACCEPTED BEHAVIOUR ────────────────
    //
    // backend-perf (MEDIUM) and backend-security (LOW) both raised "unbounded date range when
    // serviceId is present": BookingService:1008 (salon) and BookingService:731 (/me) gate the
    // span cap behind `from != null && to != null`, so a serviceId-only request reaches the query
    // with no starts_at bound at all. The architect REJECTED it on 2026-09-16 as NOT A DEFECT —
    // not deferred, not accepted-as-risk. The reasoning is preserved here so it is not
    // re-litigated:
    //
    //   1. ScheduleDateMath.MAX_EXPANSION_SPAN_DAYS (ScheduleDateMath:53-58) exists so callers
    //      "cannot trigger unbounded LOOPS". The loop is /booked-days, which materialises one
    //      entry per date — which is exactly why phase 319's D5 made from/to REQUIRED there and
    //      left them optional on the list. The list has no loop: it is
    //      findIdsBySalonIdFiltered over (salon_id, master_service_id, starts_at DESC) with
    //      LIMIT pushdown.
    //   2. A date range does not bound this query anyway. Work is O(rows matching the filter),
    //      and serviceId is already hard-capped at 50 by @Size(max = 50). A 366-day window on a
    //      busy salon scans MORE than unbounded history on a quiet one.
    //   3. The exposure is self-scoped on both routes: the salon route is
    //      owner/admin-of-this-salon, /me is the caller's own rows. No anonymous and no
    //      cross-tenant amplification.
    //   4. V166 + V167 removed the premise the finding rested on — 58 buffers for the modal
    //      IN(2) case, 300 for the COUNT(*). Both agents scored it before those landed.
    //   5. The surrounding guards are coherent and nothing is missing:
    //      assertToPlusOneDayRepresentable(to) fires independently on `to`; `from.isAfter(to)` is
    //      correctly inside the both-present block (you cannot order against a null); only the
    //      SPAN is conditional, and a span genuinely requires two ends.
    //
    // /me and the salon route STAY IDENTICAL — they are byte-identical today, share the same
    // threat model, and divergence buys nothing.
    //
    // The two @Disabled tests that asserted a 400 here were DELETED rather than left parked:
    // they encoded a REJECTED requirement, and a parked red test is a landmine — the next auditor
    // enables one, it goes red, and they "fix" it by shipping the 400 that breaks the released
    // «Архів» screen (beautica-mobile master_archive_notifier.dart:275 sends serviceIds with no
    // range on every fetch). The two positive pins below replace them: they go red the moment
    // anyone re-adds a range requirement.

    /**
     * Ledger for the rejected span-cap finding on the SALON list. A {@code serviceId} filter with
     * no {@code from}/{@code to} must reach {@link BookingRepository#findIdsBySalonIdFiltered}
     * with BOTH instant bounds {@code null} — neither a 400 nor a silently defaulted window (a
     * default anchored near "now" would also be wrong: the semantics pin
     * {@code BookingSalonBookingsIT#should_paginateServiceFilteredListOverExactOrderedGroundTruth}
     * seeds rows at 2032-03-01 and asserts 200).
     */
    @Test
    @DisplayName("SALON list — ?serviceId with NO date range reaches the repository with both "
            + "instant bounds null; the conditional span cap is accepted behaviour (finding "
            + "REJECTED 2026-09-16), so re-adding a 400 or a default window turns this red")
    void should_reachRepositoryWithNullBounds_when_serviceIdSuppliedWithNoRangeOnSalonList() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        when(bookingRepository.findIdsBySalonIdFiltered(
                        salonId, null, null, null, null, Set.of(serviceId), normalizedUnpaged()))
                .thenReturn(Page.empty());

        bookingService.getSalonBookings(
                actorId, salonId, null, null, null, null, List.of(serviceId), Pageable.unpaged());

        ArgumentCaptor<OffsetDateTime> fromTs = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> toExclusive = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(bookingRepository).findIdsBySalonIdFiltered(
                eq(salonId), eq(null), eq(null), fromTs.capture(), toExclusive.capture(),
                eq(Set.of(serviceId)), eq(normalizedUnpaged()));
        assertThat(fromTs.getValue())
                .as("'from' must reach the query as a null predicate, actual=%s", fromTs.getValue())
                .isNull();
        assertThat(toExclusive.getValue())
                .as("'to' must reach the query as a null predicate, actual=%s", toExclusive.getValue())
                .isNull();
    }

    /**
     * The {@code /me} twin of the pin above. BookingService:731 carries the identical conditional
     * span cap on purpose — the two routes stay symmetric — so this test exists so that a "fix"
     * applied to only one of them cannot pass. It also guards the shipped mobile contract:
     * {@code master_archive_notifier.dart:275} calls {@code getMyBookings(serviceIds: ...)} with
     * no range on every «Архів» fetch.
     */
    @Test
    @DisplayName("GET /bookings/me — ?serviceId with NO date range reaches the repository with "
            + "both instant bounds null; /me and the salon route stay byte-identical, so a range "
            + "requirement re-added to either one turns this red")
    void should_reachRepositoryWithNullBounds_when_serviceIdSuppliedWithNoRangeOnMyBookings() {
        UUID serviceId = UUID.randomUUID();

        when(bookingRepository.findIdsByClientIdFiltered(
                        clientId, null, null, null, Set.of(serviceId), normalizedUnpaged()))
                .thenReturn(Page.empty());

        bookingService.getMyBookings(
                clientId, buildAuth(Role.CLIENT), null, null, null, List.of(serviceId),
                Pageable.unpaged());

        ArgumentCaptor<OffsetDateTime> fromTs = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> toExclusive = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(bookingRepository).findIdsByClientIdFiltered(
                eq(clientId), eq(null), fromTs.capture(), toExclusive.capture(),
                eq(Set.of(serviceId)), eq(normalizedUnpaged()));
        assertThat(fromTs.getValue())
                .as("'from' must reach the query as a null predicate, actual=%s", fromTs.getValue())
                .isNull();
        assertThat(toExclusive.getValue())
                .as("'to' must reach the query as a null predicate, actual=%s", toExclusive.getValue())
                .isNull();
    }

    // ── Phase 319 — GET /bookings/salon/{salonId}/booked-days ────────────────────────────────

    @Test
    @DisplayName("the salon's booked days are read via findBookedDatesBySalonIds scoped to exactly "
            + "this ONE salon — never the caller's whole owned-salon aggregate the /me variant uses")
    void should_querySingleSalonScope_when_listingSalonBookedDays() {
        UUID salonId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        OffsetDateTime fromTs = from.atStartOfDay(com.beautica.common.TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime toExclusive =
                to.plusDays(1).atStartOfDay(com.beautica.common.TimeZones.KYIV).toOffsetDateTime();

        when(bookingRepository.findBookedDatesBySalonIds(List.of(salonId), fromTs, toExclusive))
                .thenReturn(List.of(java.sql.Date.valueOf(LocalDate.of(2026, 7, 5)),
                        java.sql.Date.valueOf(LocalDate.of(2026, 7, 20))));

        List<LocalDate> days = bookingService.getSalonBookedDays(salonId, from, to);

        assertThat(days)
                .as("the raw java.sql.Date rows must be converted in the service, never declared as "
                        + "LocalDate on a native scalar projection (ConverterNotFoundException)")
                .containsExactly(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 20));
        verify(bookingRepository).findBookedDatesBySalonIds(List.of(salonId), fromTs, toExclusive);
    }

    @Test
    @DisplayName("BusinessException(400) when 'from' is missing for salon booked-days — the range "
            + "is REQUIRED, because an unbounded default would scan the salon's whole history")
    void should_throwBadRequest_when_salonBookedDaysFromIsNull() {
        UUID salonId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getSalonBookedDays(salonId, null, LocalDate.of(2026, 7, 31)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("BusinessException(400) when 'to' is missing for salon booked-days")
    void should_throwBadRequest_when_salonBookedDaysToIsNull() {
        UUID salonId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getSalonBookedDays(salonId, LocalDate.of(2026, 7, 1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("BusinessException(400) when 'from' is after 'to' for salon booked-days")
    void should_throwBadRequest_when_salonBookedDaysFromIsAfterTo() {
        UUID salonId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getSalonBookedDays(
                        salonId, LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    /**
     * The ceiling is the 366-day DEFAULT, asserted as the no-max overload of
     * {@link com.beautica.booking.service.ScheduleDateMath#assertSpanWithinMax}.
     *
     * <p><b>The negative verify below is load-bearing.</b> A PR #129 audit finding (the salon
     * booked-days span-cap proposal, backend-perf 2026-09-20) narrowed this endpoint to 62
     * inclusive days by switching to the three-arg overload. That shipped a hard 400 to the
     * already-installed mobile client, which requests 361 days
     * ({@code booked_days_notifier.dart}'s {@code kBookedDaysSpanDays = 180}, today +/- 180), and
     * was reverted the same day. Pinning that the three-arg overload is NEVER reached turns a
     * silent re-narrowing red HERE, at unit speed, instead of in the field — see
     * {@code BookingService#getSalonBookedDays}'s javadoc for why the cap is frozen.
     */
    @Test
    @DisplayName("the 366-day span cap for salon booked-days is delegated to "
            + "ScheduleDateMath.assertSpanWithinMax, not re-implemented inline")
    void should_delegateSpanCap_toScheduleDateMathForSalonBookedDays() {
        UUID salonId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2027, 6, 1);

        org.mockito.Mockito.doThrow(
                        new BusinessException(HttpStatus.BAD_REQUEST, "Date range exceeds the maximum of 366 days"))
                .when(dateMath).assertSpanWithinMax(from, to);

        assertThatThrownBy(() -> bookingService.getSalonBookedDays(salonId, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(dateMath).assertToPlusOneDayRepresentable(to);
        verify(dateMath).assertSpanWithinMax(from, to);
        verify(dateMath, never()).assertSpanWithinMax(eq(from), eq(to), anyLong());
        verifyNoInteractions(bookingRepository);
    }

    // ── Finding 1: SALON_OWNER multi-salon tests ───────────────────────────────

    @Test
    @DisplayName("salon owner with multiple salons receives bookings from all owned salons")
    void should_returnOwnerBookings_across_all_owned_salons() {
        UUID actorId = UUID.randomUUID();
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();
        List<UUID> salonIds = List.of(salonId1, salonId2);
        Pageable pageable = Pageable.unpaged();
        Booking existingBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(salonIds);
        when(bookingRepository.findIdsBySalonIdsFiltered(salonIds, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(existingBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        verify(salonRepository).findIdsByOwnerIdAndIsActiveTrue(actorId);
        verify(bookingRepository).findIdsBySalonIdsFiltered(salonIds, null, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("empty page is returned immediately when owner has no active salons")
    void should_returnEmpty_when_ownerHasNoSalons() {
        UUID actorId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        assertThat(result.data()).isEmpty();
        verify(bookingRepository, never()).findIdsBySalonIdsFiltered(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CLIENT with no status filter queries the enriched client projection")
    void should_returnClientBookings_when_clientListsWithoutStatus() {
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, normalizedUnpaged())).thenReturn(Page.empty());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByClientIdFiltered(clientId, null, null, null, null, normalizedUnpaged());
        verify(bookingRepository, never()).findIdsByClientId(any(), any());
        // Empty ID page short-circuits before the hydrate ever runs (never emit IN ()).
        verify(bookingRepository, never()).hydrateClientBookingDetails(any());
    }

    @Test
    @DisplayName("CLIENT with a status filter passes the status to the enriched client projection")
    void should_returnClientBookings_when_clientListsWithStatus() {
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findIdsByClientIdFiltered(clientId, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                clientId, buildAuth(Role.CLIENT), List.of(BookingStatus.CONFIRMED), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByClientIdFiltered(clientId, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("CLIENT with multiple statuses passes the union to the enriched client projection "
            + "(Phase 26.1: ?status=CANCELLED&status=DECLINED for CLIENT too)")
    void should_returnClientBookings_when_clientListsWithMultipleStatuses() {
        Pageable pageable = Pageable.unpaged();
        Set<BookingStatus> expected = EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED);

        when(bookingRepository.findIdsByClientIdFiltered(clientId, expected, null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                clientId, buildAuth(Role.CLIENT),
                List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByClientIdFiltered(clientId, expected, null, null, null, normalizedUnpaged());
    }

    private com.beautica.booking.repository.ClientBookingDetailProjection clientProjectionRow(
            String masterProfessionalTitle, String locationNote) {
        return new com.beautica.booking.repository.ClientBookingDetailProjection(
                bookingId, clientId, masterId, masterServiceId, "Manicure",
                BookingStatus.CONFIRMED,
                OffsetDateTime.now(clock).plusHours(2),
                OffsetDateTime.now(clock).plusHours(3),
                new BigDecimal("500.00"), 60,
                Instant.now(clock),
                "Client", "User", "Master", "Person",
                masterProfessionalTitle,
                null, null, null,
                "https://cdn.test/avatar.png", Role.INDEPENDENT_MASTER, null,
                null, null, "Khreschatyk", "10",
                locationNote,
                "MANICURE", false,
                null,
                null,
                // clientAvatarUrl — the CLIENT projection path's own photo column.
                "https://cdn.test/client-avatar.png",
                // Phase B1 — a REVIEWED master (count > 0), so the zero-review normalisation is
                // not the branch under test here; the null case has its own test below.
                new BigDecimal("4.75"), 12,
                // Phase B2 salonId — this fixture is an INDEPENDENT_MASTER row, so null is the
                // correct value; the salon case has its own fixture below.
                null);
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) surfaces masterProfessionalTitle and locationNote from the projection when set")
    void should_surfaceTitleAndLocationNote_when_clientProjectionRowHasBoth() {
        Pageable pageable = Pageable.unpaged();
        var row = clientProjectionRow("Перукар-стиліст", "3-й поверх, код 1234");
        when(bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.hydrateClientBookingDetails(List.of(bookingId)))
                .thenReturn(List.of(row));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).masterProfessionalTitle()).isEqualTo("Перукар-стиліст");
        assertThat(result.data().get(0).locationNote()).isEqualTo("3-й поверх, код 1234");
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) surfaces null masterProfessionalTitle/locationNote (not NPE) when the projection row has neither")
    void should_returnNullTitleAndLocationNote_when_clientProjectionRowHasNeither() {
        Pageable pageable = Pageable.unpaged();
        var row = clientProjectionRow(null, null);
        when(bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.hydrateClientBookingDetails(List.of(bookingId)))
                .thenReturn(List.of(row));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).masterProfessionalTitle()).isNull();
        assertThat(result.data().get(0).locationNote()).isNull();
    }

    // ── priceMaxAtBooking (CLIENT projection path) ────────────────────────────────────────────
    //    Since V119 the ceiling is a frozen column on the bookings row, so this path has no rule
    //    left to apply — the service must pass the projected value through UNTOUCHED. Any
    //    re-derivation reintroduced here would let a provider's later service edit rewrite an
    //    agreed band. The creation-time rule that produced the value lives in BookingPriceRange
    //    and is pinned by BookingPriceRangeTest.

    private com.beautica.booking.repository.ClientBookingDetailProjection clientProjectionRowWithCeiling(
            java.math.BigDecimal priceMaxAtBooking) {
        return new com.beautica.booking.repository.ClientBookingDetailProjection(
                bookingId, clientId, masterId, masterServiceId, "Manicure",
                BookingStatus.CONFIRMED,
                OffsetDateTime.now(clock).plusHours(2),
                OffsetDateTime.now(clock).plusHours(3),
                new BigDecimal("300.00"), 60,
                Instant.now(clock),
                "Client", "User", "Master", "Person",
                null,
                null, null, null,
                "https://cdn.test/avatar.png", Role.INDEPENDENT_MASTER, null,
                null, null, "Khreschatyk", "10",
                null,
                "MANICURE", false,
                priceMaxAtBooking,
                null,
                // clientAvatarUrl — irrelevant to this fixture's price-ceiling assertions.
                null,
                // Phase B1 masterAvgRating/masterReviewCount — irrelevant here too.
                new BigDecimal("4.20"), 3,
                // Phase B2 salonId — irrelevant to price-ceiling assertions.
                null);
    }

    private com.beautica.booking.dto.BookingDetailResponse firstClientRowFor(
            com.beautica.booking.repository.ClientBookingDetailProjection row) {
        when(bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.hydrateClientBookingDetails(List.of(bookingId)))
                .thenReturn(List.of(row));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getMyBookings(
                clientId, buildAuth(Role.CLIENT), null, null, null, null, Pageable.unpaged());

        assertThat(result.data()).hasSize(1);
        return result.data().get(0);
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) passes a frozen priceMaxAtBooking through untouched")
    void should_passFrozenCeilingThrough_when_clientProjectionRowHasOne() {
        var row = clientProjectionRowWithCeiling(new BigDecimal("500.00"));

        var booking = firstClientRowFor(row);

        assertThat(booking.priceMaxAtBooking()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) leaves a null priceMaxAtBooking null — never re-derives a ceiling from live service state")
    void should_returnNullPriceMax_when_clientProjectionRowHasNoFrozenCeiling() {
        var row = clientProjectionRowWithCeiling(null);

        var booking = firstClientRowFor(row);

        assertThat(booking.priceMaxAtBooking()).isNull();
    }

    // ── Phase B1 — master rating on the CLIENT projection path ────────────────────────────────
    //    The projection selects masters.avg_rating RAW; the service must apply the SAME
    //    zero-review-to-null rule the entity path applies, via the one shared helper, so
    //    GET /bookings/me and GET /bookings/{id} can never disagree about a master's rating.

    private com.beautica.booking.repository.ClientBookingDetailProjection clientProjectionRowWithRating(
            java.math.BigDecimal masterAvgRating, int masterReviewCount) {
        return new com.beautica.booking.repository.ClientBookingDetailProjection(
                bookingId, clientId, masterId, masterServiceId, "Manicure",
                BookingStatus.CONFIRMED,
                OffsetDateTime.now(clock).plusHours(2),
                OffsetDateTime.now(clock).plusHours(3),
                new BigDecimal("500.00"), 60,
                Instant.now(clock),
                "Client", "User", "Master", "Person",
                null,
                null, null, null,
                "https://cdn.test/avatar.png", Role.INDEPENDENT_MASTER, null,
                null, null, "Khreschatyk", "10",
                null,
                "MANICURE", false,
                null,
                null,
                null,
                masterAvgRating, masterReviewCount,
                // Phase B2 salonId — irrelevant to the rating normalisation.
                null);
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) surfaces the master's stored avgRating/reviewCount from the projection")
    void should_surfaceMasterRating_when_clientProjectionRowHasReviews() {
        var booking = firstClientRowFor(clientProjectionRowWithRating(new BigDecimal("4.75"), 12));

        assertThat(booking.masterAvgRating()).isEqualByComparingTo(new BigDecimal("4.75"));
        assertThat(booking.masterReviewCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) nulls the master's avgRating when reviewCount is 0 — the "
            + "0.00 the column stores for an unreviewed master must never reach the wire")
    void should_returnNullMasterAvgRating_when_clientProjectionRowHasNoReviews() {
        var booking = firstClientRowFor(clientProjectionRowWithRating(new BigDecimal("0.00"), 0));

        assertThat(booking.masterAvgRating()).isNull();
        assertThat(booking.masterReviewCount()).isZero();
    }

    // ── Phase B2 — the booking's salon snapshot on the CLIENT projection path ──────────────────
    //    The projection selects b.salon.id (the BOOKING's own FK), not s.id off the master's live
    //    salon join. The service must pass it through untouched so GET /bookings/me and
    //    GET /bookings/{id} carry the same key the mobile client uses to invalidate its
    //    salon-scoped review caches. Phase 242: salonName now rides the SAME b.salon alias, so
    //    the "two independent salon sources" framing these tests were written around is retired.

    private com.beautica.booking.repository.ClientBookingDetailProjection clientProjectionRowWithSalon(
            UUID salonId, String salonName) {
        return new com.beautica.booking.repository.ClientBookingDetailProjection(
                bookingId, clientId, masterId, masterServiceId, "Manicure",
                BookingStatus.CONFIRMED,
                OffsetDateTime.now(clock).plusHours(2),
                OffsetDateTime.now(clock).plusHours(3),
                new BigDecimal("500.00"), 60,
                Instant.now(clock),
                "Client", "User", "Master", "Person",
                null,
                null, null, null,
                "https://cdn.test/avatar.png", Role.SALON_MASTER, salonName,
                null, null, "Khreschatyk", "10",
                null,
                "MANICURE", false,
                null,
                null,
                null,
                new BigDecimal("4.20"), 3,
                salonId);
    }

    /**
     * Phase 242 retired the "the two can disagree" case. This test used to have a sibling,
     * {@code should_keepSalonIdAndSalonNameIndependent_when_theyDisagree}, built on the premise
     * that {@code salonId} came from {@code b.salon} while {@code salonName} came from
     * {@code m.salon}, so a post-rotation row carried two different salons. Both now ride the same
     * {@code LEFT JOIN b.salon} alias and cannot disagree — the premise is gone, and with it the
     * only thing that distinguished the two tests (they were already flagged as near-duplicates by
     * backend-qa on phase 241). One passthrough test remains: the SERVICE must hand both fields
     * through untouched, deriving neither from the other.
     *
     * <p>Whether the QUERY selects the right salon is not this test's job — that is
     * {@code ClientBookingDetailProjectionTest}'s rotation test, against real Postgres.
     */
    @Test
    @DisplayName("getMyBookings (CLIENT) passes the projection's salonId AND salonName through "
            + "untouched, deriving neither from the other")
    void should_surfaceSalonId_when_clientProjectionRowCarriesSalon() {
        UUID salonId = UUID.randomUUID();

        var booking = firstClientRowFor(clientProjectionRowWithSalon(salonId, "Salon Bookings"));

        assertThat(booking.salonId()).isEqualTo(salonId);
        assertThat(booking.salonName()).isEqualTo("Salon Bookings");
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) leaves salonId null for an independent master's booking — "
            + "the row is still returned, never filtered out")
    void should_returnNullSalonId_when_clientProjectionRowHasNoSalon() {
        var booking = firstClientRowFor(clientProjectionRowWithSalon(null, null));

        assertThat(booking.salonId()).isNull();
        assertThat(booking.id()).isEqualTo(bookingId);
    }

    // ── categoryKey — mobile category-icon resolver slug, CLIENT projection path ───────────────
    //    toDetailResponse derives categoryKey from p.categoryName() via the SAME shared
    //    BookingDetailResponse.categoryKeyOrNull helper the entity path uses (see
    //    BookingDetailResponseTest), so the two mappers cannot disagree. QA-authored — self-
    //    reported gap: every clientProjectionRow* fixture above hardcodes categoryName to the
    //    already-slug-form "MANICURE", which proves the field is passed through but proves
    //    nothing about the normalisation actually running.

    private com.beautica.booking.repository.ClientBookingDetailProjection clientProjectionRowWithCategory(
            String categoryName) {
        return new com.beautica.booking.repository.ClientBookingDetailProjection(
                bookingId, clientId, masterId, masterServiceId, "Manicure",
                BookingStatus.CONFIRMED,
                OffsetDateTime.now(clock).plusHours(2),
                OffsetDateTime.now(clock).plusHours(3),
                new BigDecimal("500.00"), 60,
                Instant.now(clock),
                "Client", "User", "Master", "Person",
                null,
                null, null, null,
                "https://cdn.test/avatar.png", Role.INDEPENDENT_MASTER, null,
                null, null, "Khreschatyk", "10",
                null,
                categoryName, false,
                null,
                null,
                null,
                new BigDecimal("4.20"), 3,
                // Phase B2 salonId — irrelevant to the categoryKey normalisation.
                null);
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) normalizes a raw projected category into categoryKey — "
            + "trim + uppercase + collapse non-alphanumeric runs to '_', the SAME transform the "
            + "entity path applies, via the shared helper")
    void should_normalizeCategoryKey_when_clientProjectionRowHasRawCategory() {
        var booking = firstClientRowFor(clientProjectionRowWithCategory("  Nail Care & Spa!!  "));

        assertThat(booking.categoryKey())
                .as("actual=%s", booking.categoryKey())
                .isEqualTo("NAIL_CARE_SPA");
        assertThat(booking.categoryName())
                .as("categoryName stays the raw projected value — only categoryKey is normalized")
                .isEqualTo("  Nail Care & Spa!!  ");
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) leaves categoryKey NULL — never findTimeline's \"UNKNOWN\" "
            + "sentinel and never an empty string — when the projected category is null")
    void should_returnNullCategoryKey_when_clientProjectionRowHasNoCategory() {
        var booking = firstClientRowFor(clientProjectionRowWithCategory(null));

        assertThat(booking.categoryKey())
                .as("a booking card must render NO icon for an uncategorised service — a non-null "
                        + "placeholder here would paint the wrong glyph, not a generic one")
                .isNull();
    }

    private com.beautica.booking.repository.ClientBookingDetailProjection clientProjectionRowWithId(
            UUID id, String serviceName) {
        return new com.beautica.booking.repository.ClientBookingDetailProjection(
                id, clientId, masterId, masterServiceId, serviceName,
                BookingStatus.CONFIRMED,
                OffsetDateTime.now(clock).plusHours(2),
                OffsetDateTime.now(clock).plusHours(3),
                new BigDecimal("500.00"), 60,
                Instant.now(clock),
                "Client", "User", "Master", "Person",
                null,
                null, null, null,
                "https://cdn.test/avatar.png", Role.INDEPENDENT_MASTER, null,
                null, null, "Khreschatyk", "10",
                null,
                "MANICURE", false,
                null,
                null,
                // clientAvatarUrl — irrelevant to this fixture's ordering assertions.
                null,
                // Phase B1 masterAvgRating/masterReviewCount — irrelevant to ordering.
                new BigDecimal("4.20"), 3,
                // Phase B2 salonId — irrelevant to ordering.
                null);
    }

    // ── Phase 26.7.1 security finding (LOW): the CLIENT branch's order re-imposition had no
    //    dedicated test — `listClientBookings` maps the hydrate result into a Map<UUID,...> and
    //    re-walks idPage.getContent() specifically because `WHERE b.id IN :ids` does not
    //    guarantee row order, mirroring `listProviderBookings`' existing pattern for
    //    findAllByIdsWithGraph. The two tests below pin that re-walk actually re-imposes the
    //    ID-page order (not the hydrate's own order) and that a missing hydrate row is skipped,
    //    not an NPE. ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMyBookings (CLIENT) re-imposes the ID-page's order onto the hydrate result — "
            + "IN :ids does not guarantee row order, so a hydrate that returns rows reversed "
            + "relative to the ID page must still be re-sequenced to the ID page's order "
            + "(security LOW — Phase 26.7.1 order re-imposition on the CLIENT branch)")
    void should_reorderClientBookings_when_hydrateReturnsRowsOutOfIdPageOrder() {
        Pageable pageable = Pageable.unpaged();
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        var rowA = clientProjectionRowWithId(idA, "Manicure A");
        var rowB = clientProjectionRowWithId(idB, "Pedicure B");

        // The ID page's order is [idB, idA] — this is the order production code must preserve
        // in the response, regardless of what order the hydrate below returns rows in.
        when(bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(idB, idA)));
        // The hydrate returns the SAME two rows but in the OPPOSITE order — simulating
        // `WHERE b.id IN :ids` not preserving row order (rowA, whose id (idA) is second in the
        // id page, comes back FIRST from the hydrate).
        when(bookingRepository.hydrateClientBookingDetails(List.of(idB, idA)))
                .thenReturn(List.of(rowA, rowB));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, null, pageable);

        assertThat(result.data())
                .as("response order must follow the ID PAGE's order [idB, idA], NOT the hydrate "
                        + "result's order [idA, idB] — proves the Map<UUID,...> re-walk actually "
                        + "re-imposes id-page order rather than passing the hydrate's row order through")
                .extracting(BookingDetailResponse::id)
                .containsExactly(idB, idA);
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) silently skips an ID-page entry whose hydrate row is "
            + "absent (e.g. the booking was deleted mid-request) instead of throwing an NPE "
            + "(defensive filter(Objects::nonNull) branch)")
    void should_skipMissingHydrateRow_when_idPageContainsAnIdAbsentFromHydrateResult() {
        Pageable pageable = Pageable.unpaged();
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        var rowA = clientProjectionRowWithId(idA, "Manicure A");

        // The ID page lists BOTH ids, but the hydrate only returns idA's row — idB's booking
        // vanished between the two queries (e.g. deleted mid-request).
        when(bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(idA, idB)));
        when(bookingRepository.hydrateClientBookingDetails(List.of(idA, idB)))
                .thenReturn(List.of(rowA));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, null, pageable);

        assertThat(result.data())
                .as("the id missing from the hydrate must be skipped, not surfaced as a null "
                        + "element or thrown as an NPE")
                .extracting(BookingDetailResponse::id)
                .containsExactly(idA);
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER receives bookings from the master-scoped repository method")
    void should_returnMasterBookings_when_independentMasterLists() {
        Pageable pageable = Pageable.unpaged();
        User masterUser = buildUser(UUID.randomUUID(), Role.INDEPENDENT_MASTER);
        UUID masterUserId = masterUser.getId();

        when(masterRepository.findByUserId(masterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, null, null, null, null, normalizedUnpaged())).thenReturn(Page.empty());

        var result = bookingService.getMyBookings(masterUserId, buildAuth(Role.INDEPENDENT_MASTER), null, null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, null, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("SALON_MASTER receives only status-matched bookings from the master-scoped repository method")
    void should_returnMasterBookings_when_salonMasterListsWithStatus() {
        Pageable pageable = Pageable.unpaged();
        User salonMasterUser = buildUser(UUID.randomUUID(), Role.SALON_MASTER);
        UUID salonMasterUserId = salonMasterUser.getId();

        when(masterRepository.findByUserId(salonMasterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                salonMasterUserId, buildAuth(Role.SALON_MASTER), List.of(BookingStatus.CONFIRMED), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER with multiple statuses passes the union to the master-scoped repository method "
            + "(Phase 26.1: ?status=CANCELLED&status=DECLINED)")
    void should_returnMasterBookings_when_independentMasterListsWithMultipleStatuses() {
        Pageable pageable = Pageable.unpaged();
        User masterUser = buildUser(UUID.randomUUID(), Role.INDEPENDENT_MASTER);
        UUID masterUserId = masterUser.getId();
        Set<BookingStatus> expected = EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED);

        when(masterRepository.findByUserId(masterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, expected, null, null, null, normalizedUnpaged())).thenReturn(Page.empty());

        var result = bookingService.getMyBookings(masterUserId, buildAuth(Role.INDEPENDENT_MASTER),
                List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, expected, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("repeated/duplicate statuses de-duplicate to a single-element EnumSet before hitting the "
            + "master-scoped repository method (Phase 26.1: ?status=CONFIRMED&status=CONFIRMED)")
    void should_deduplicateStatuses_when_independentMasterRepeatsTheSameStatus() {
        Pageable pageable = Pageable.unpaged();
        User masterUser = buildUser(UUID.randomUUID(), Role.INDEPENDENT_MASTER);
        UUID masterUserId = masterUser.getId();

        when(masterRepository.findByUserId(masterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(masterUserId, buildAuth(Role.INDEPENDENT_MASTER),
                List.of(BookingStatus.CONFIRMED, BookingStatus.CONFIRMED), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("ForbiddenException is thrown when SALON_ADMIN calls getMyBookings")
    void should_throwForbidden_when_salonAdminListsBookings() {
        UUID salonAdminId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        assertThatThrownBy(() -> bookingService.getMyBookings(salonAdminId, buildAuth(Role.SALON_ADMIN), null, null, null, null, pageable))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── getMyBookedDays (Phase 26.5 — GET /bookings/me/booked-days) ──────────────────────────────

    @Test
    @DisplayName("CLIENT scope: repository result (already distinct/ascending from the native query) "
            + "is returned as-is, scoped by the caller's own client id — no in-Java re-reduction")
    void should_returnClientScopedDates_when_clientRequestsBookedDays() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        List<LocalDate> expected = List.of(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 20));
        OffsetDateTime fromTs = from.atStartOfDay(KYIV).toOffsetDateTime();
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay(KYIV).toOffsetDateTime();

        // Repository returns the raw JDBC java.sql.Date (see BookingRepository's Phase 26.5
        // javadoc — this is what production reproduces the ConverterNotFoundException with);
        // BookingService converts to LocalDate via java.sql.Date::toLocalDate.
        List<java.sql.Date> stubbed = expected.stream().map(java.sql.Date::valueOf).toList();
        when(bookingRepository.findBookedDatesByClientId(clientId, fromTs, toExclusive)).thenReturn(stubbed);

        var result = bookingService.getMyBookedDays(clientId, buildAuth(Role.CLIENT), from, to);

        assertThat(result).isEqualTo(expected);
        verify(bookingRepository).findBookedDatesByClientId(clientId, fromTs, toExclusive);
        verifyNoInteractions(masterRepository, salonRepository);
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER/SALON_MASTER scope: masterId is resolved from the JWT principal "
            + "(masterRepository.findByUserId), never taken from a request parameter")
    void should_returnMasterScopedDates_when_independentMasterRequestsBookedDays() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 10);
        List<LocalDate> expected = List.of(LocalDate.of(2026, 8, 3));
        OffsetDateTime fromTs = from.atStartOfDay(KYIV).toOffsetDateTime();
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay(KYIV).toOffsetDateTime();
        UUID actorUserId = UUID.randomUUID();
        List<java.sql.Date> stubbed = expected.stream().map(java.sql.Date::valueOf).toList();

        when(masterRepository.findByUserId(actorUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findBookedDatesByMasterId(masterId, fromTs, toExclusive)).thenReturn(stubbed);

        var result = bookingService.getMyBookedDays(actorUserId, buildAuth(Role.INDEPENDENT_MASTER), from, to);

        assertThat(result).isEqualTo(expected);
        verify(bookingRepository).findBookedDatesByMasterId(masterId, fromTs, toExclusive);
        verifyNoInteractions(salonRepository);
    }

    @Test
    @DisplayName("NotFoundException when a master-role caller has no Master profile row")
    void should_throwNotFound_when_masterRoleCallerHasNoMasterProfile() {
        UUID actorUserId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 10);

        when(masterRepository.findByUserId(actorUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getMyBookedDays(actorUserId, buildAuth(Role.SALON_MASTER), from, to))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("SALON_OWNER scope: aggregates across every owned active salon (salonRepository"
            + ".findIdsByOwnerIdAndIsActiveTrue), mirroring getMyBookings' provider dispatch")
    void should_returnSalonScopedDates_when_salonOwnerRequestsBookedDays() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        List<LocalDate> expected = List.of(LocalDate.of(2026, 9, 12));
        OffsetDateTime fromTs = from.atStartOfDay(KYIV).toOffsetDateTime();
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay(KYIV).toOffsetDateTime();
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        List<java.sql.Date> stubbed = expected.stream().map(java.sql.Date::valueOf).toList();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of(salonId));
        when(bookingRepository.findBookedDatesBySalonIds(List.of(salonId), fromTs, toExclusive)).thenReturn(stubbed);

        var result = bookingService.getMyBookedDays(ownerId, buildAuth(Role.SALON_OWNER), from, to);

        assertThat(result).isEqualTo(expected);
        verify(bookingRepository).findBookedDatesBySalonIds(List.of(salonId), fromTs, toExclusive);
    }

    @Test
    @DisplayName("SALON_OWNER with no active salons short-circuits to an empty list without querying bookings")
    void should_returnEmptyList_when_salonOwnerHasNoActiveSalonsForBookedDays() {
        UUID ownerId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());

        var result = bookingService.getMyBookedDays(ownerId, buildAuth(Role.SALON_OWNER), from, to);

        assertThat(result).isEmpty();
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("ForbiddenException when SALON_ADMIN calls getMyBookedDays — same boundary as getMyBookings")
    void should_throwForbidden_when_salonAdminRequestsBookedDays() {
        UUID salonAdminId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);

        assertThatThrownBy(() -> bookingService.getMyBookedDays(salonAdminId, buildAuth(Role.SALON_ADMIN), from, to))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("BusinessException(400) when 'from' is null — the range is required, unlike getMyBookings' "
            + "optional from/to")
    void should_throwBadRequest_when_fromIsNullForBookedDays() {
        assertThatThrownBy(() -> bookingService.getMyBookedDays(
                        clientId, buildAuth(Role.CLIENT), null, LocalDate.of(2026, 7, 31)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("BusinessException(400) when 'to' is null")
    void should_throwBadRequest_when_toIsNullForBookedDays() {
        assertThatThrownBy(() -> bookingService.getMyBookedDays(
                        clientId, buildAuth(Role.CLIENT), LocalDate.of(2026, 7, 1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("BusinessException(400) when 'from' is after 'to'")
    void should_throwBadRequest_when_fromIsAfterToForBookedDays() {
        assertThatThrownBy(() -> bookingService.getMyBookedDays(
                        clientId, buildAuth(Role.CLIENT), LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("the 366-day span cap is delegated to ScheduleDateMath.assertSpanWithinMax, not "
            + "re-implemented inline — a stub throwing from that guard propagates through getMyBookedDays "
            + "before any repository call is made")
    void should_delegateSpanCap_toScheduleDateMathForBookedDays() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2027, 6, 1);

        org.mockito.Mockito.doThrow(new BusinessException(HttpStatus.BAD_REQUEST, "Date range exceeds the maximum of 366 days"))
                .when(dateMath).assertSpanWithinMax(from, to);

        assertThatThrownBy(() -> bookingService.getMyBookedDays(clientId, buildAuth(Role.CLIENT), from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(dateMath).assertToPlusOneDayRepresentable(to);
        verify(dateMath).assertSpanWithinMax(from, to);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("from/to convert to the same half-open Kyiv-zoned instant range as getMyBookings' "
            + "date filter (from.atStartOfDay(KYIV), to.plusDays(1).atStartOfDay(KYIV)) — a dot here and "
            + "a non-empty GET /bookings/me?from=D&to=D for the same date must agree")
    void should_useHalfOpenKyivRange_matchingGetMyBookings_forBookedDays() {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 1);
        OffsetDateTime expectedFromTs = OffsetDateTime.parse("2026-03-01T00:00:00+02:00");
        OffsetDateTime expectedToExclusive = OffsetDateTime.parse("2026-03-02T00:00:00+02:00");

        when(bookingRepository.findBookedDatesByClientId(clientId, expectedFromTs, expectedToExclusive))
                .thenReturn(List.of());

        bookingService.getMyBookedDays(clientId, buildAuth(Role.CLIENT), from, to);

        verify(bookingRepository).findBookedDatesByClientId(clientId, expectedFromTs, expectedToExclusive);
    }

    // ── actor-role resolution at BOTH /bookings/me entry points ────────────────────────────────
    //
    // getMyBookings and getMyBookedDays are the only two BookingService methods that derive the
    // caller's role from the Authentication rather than from a loaded entity, and both now route
    // that read through AuthenticationUtils.role. The local resolveActorRole they used to share
    // was:
    //
    //     auth.getAuthorities().stream().findFirst()
    //         .map(a -> Role.valueOf(a.getAuthority().replace("ROLE_", "")))
    //         .orElseThrow(() -> new ForbiddenException("Access denied"));
    //
    // which differed from the replacement in four observable ways, none of which had a test:
    //   1. an unrecognised ROLE_* string threw a raw IllegalArgumentException out of
    //      Role.valueOf -> 500, not 403;
    //   2. a multi-role principal was resolved by getAuthorities() iteration order, silently
    //      granting whichever scope happened to come first;
    //   3. a valid role positioned AFTER an unrecognised authority was never seen at all,
    //      because findFirst() looked at exactly one element;
    //   4. a null Authentication NPE'd on getAuthorities() -> 500, not 403.
    //
    // The tests below pin all four at BOTH entry points rather than only at
    // AuthenticationUtilsTest, because the two methods invoke the resolver at DIFFERENT points in
    // their control flow (getMyBookings resolves first thing; getMyBookedDays resolves only after
    // its from/to validation), so a regression that reintroduced a local extractor in one of them
    // would leave AuthenticationUtilsTest fully green.

    /** A UPAT carrying an arbitrary authority set — the shape JwtAuthenticationFilter produces. */
    private Authentication authWithAuthorities(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com",
                null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** A non-UPAT Authentication — the shape AuthenticationUtils rejects outright. */
    private Authentication nonUpatAuth() {
        return new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    private static final LocalDate BOOKED_DAYS_FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate BOOKED_DAYS_TO = LocalDate.of(2026, 7, 31);

    @Test
    @DisplayName("getMyBookings — ForbiddenException, and no scope query at all, when the principal "
            + "carries two distinct ROLE_* authorities; the old findFirst() extractor would have "
            + "silently served whichever scope iteration order surfaced first")
    void should_throwForbidden_when_getMyBookingsPrincipalCarriesTwoRoles() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, authWithAuthorities("ROLE_CLIENT", "ROLE_SALON_OWNER"),
                        null, null, null, null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Ambiguous role");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookings — ForbiddenException (403), never a raw IllegalArgumentException "
            + "(500), when the sole authority is a ROLE_* string that is not a known Role")
    void should_throwForbidden_when_getMyBookingsAuthorityIsUnrecognisedRole() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, authWithAuthorities("ROLE_SUPERHERO"),
                        null, null, null, null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No role assigned");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookings — resolves the valid role even when an unrecognised ROLE_* authority "
            + "precedes it, and dispatches to the CLIENT scope query; the old findFirst() extractor "
            + "never looked past the first authority")
    void should_dispatchClientScope_when_getMyBookingsAuthHasUnrecognisedAuthorityFirst() {
        when(bookingRepository.findIdsByClientIdFiltered(
                clientId, null, null, null, null, normalizedUnpaged())).thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                clientId, authWithAuthorities("ROLE_SUPERHERO", "ROLE_CLIENT"),
                null, null, null, null, Pageable.unpaged());

        assertThat(result.data()).isEmpty();
        verify(bookingRepository).findIdsByClientIdFiltered(
                clientId, null, null, null, null, normalizedUnpaged());
        verifyNoInteractions(masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookings — ForbiddenException (403), never a NullPointerException (500), when "
            + "the Authentication is null")
    void should_throwForbidden_when_getMyBookingsAuthIsNull() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, null, null, null, null, null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("Not authenticated");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookings — ForbiddenException when the Authentication is not the "
            + "UsernamePasswordAuthenticationToken JwtAuthenticationFilter installs, even though it "
            + "does carry a ROLE_* authority")
    void should_throwForbidden_when_getMyBookingsAuthIsNotUsernamePasswordToken() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, nonUpatAuth(), null, null, null, null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Not authenticated");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookedDays — ForbiddenException, and no scope query at all, when the principal "
            + "carries two distinct ROLE_* authorities (same boundary getMyBookings enforces)")
    void should_throwForbidden_when_getMyBookedDaysPrincipalCarriesTwoRoles() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookedDays(
                        actorId, authWithAuthorities("ROLE_CLIENT", "ROLE_SALON_OWNER"),
                        BOOKED_DAYS_FROM, BOOKED_DAYS_TO))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Ambiguous role");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookedDays — ForbiddenException (403), never a raw IllegalArgumentException "
            + "(500), when the sole authority is a ROLE_* string that is not a known Role")
    void should_throwForbidden_when_getMyBookedDaysAuthorityIsUnrecognisedRole() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookedDays(
                        actorId, authWithAuthorities("ROLE_SUPERHERO"), BOOKED_DAYS_FROM, BOOKED_DAYS_TO))
                .isInstanceOf(ForbiddenException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No role assigned");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookedDays — resolves the valid role even when an unrecognised ROLE_* authority "
            + "precedes it, and dispatches to the CLIENT scope query")
    void should_dispatchClientScope_when_getMyBookedDaysAuthHasUnrecognisedAuthorityFirst() {
        OffsetDateTime fromTs = BOOKED_DAYS_FROM.atStartOfDay(KYIV).toOffsetDateTime();
        OffsetDateTime toExclusive = BOOKED_DAYS_TO.plusDays(1).atStartOfDay(KYIV).toOffsetDateTime();
        when(bookingRepository.findBookedDatesByClientId(clientId, fromTs, toExclusive))
                .thenReturn(List.of(java.sql.Date.valueOf(LocalDate.of(2026, 7, 5))));

        var result = bookingService.getMyBookedDays(
                clientId, authWithAuthorities("ROLE_SUPERHERO", "ROLE_CLIENT"),
                BOOKED_DAYS_FROM, BOOKED_DAYS_TO);

        assertThat(result).containsExactly(LocalDate.of(2026, 7, 5));
        verify(bookingRepository).findBookedDatesByClientId(clientId, fromTs, toExclusive);
        verifyNoInteractions(masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookedDays — ForbiddenException (403), never a NullPointerException (500), when "
            + "the Authentication is null")
    void should_throwForbidden_when_getMyBookedDaysAuthIsNull() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookedDays(
                        actorId, null, BOOKED_DAYS_FROM, BOOKED_DAYS_TO))
                .isInstanceOf(ForbiddenException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("Not authenticated");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    @Test
    @DisplayName("getMyBookedDays — ForbiddenException when the Authentication is not the "
            + "UsernamePasswordAuthenticationToken JwtAuthenticationFilter installs, even though it "
            + "does carry a ROLE_* authority")
    void should_throwForbidden_when_getMyBookedDaysAuthIsNotUsernamePasswordToken() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> bookingService.getMyBookedDays(
                        actorId, nonUpatAuth(), BOOKED_DAYS_FROM, BOOKED_DAYS_TO))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Not authenticated");

        verifyNoInteractions(bookingRepository, masterRepository, salonRepository);
    }

    // ── getMyBookings — sort whitelist tripwire (Phase 26.3, backend-security gap) ──────────────
    //
    // normalizeBookingSort is a security boundary (see its javadoc): both the CLIENT projection
    // query and the provider ID-page query JOIN through b.master m JOIN m.user, so an unvalidated
    // Sort.Order#getProperty() dot-path is legal JPQL/Criteria and would let an authenticated
    // caller order their OWN results by an arbitrary column on that join — including
    // User.passwordHash, a real mapped column (User.java:41-42). Pre-Phase-26.3, that ordering
    // oracle was live: ?sort=master.user.passwordHash,asc would have compiled and executed. These
    // two tests are the ONLY assertions in the suite that the whitelist actually rejects an
    // unlisted property before either repository method is ever reached — backend-security found
    // the control rested entirely on manual code review with no regression net.

    @Test
    @DisplayName("BusinessException(400) is thrown, and NEITHER repository is ever touched, when sort "
            + "targets the dotted join path master.user.passwordHash — the credential side channel "
            + "this whitelist exists to close (an authenticated actor ordering their own bookings by "
            + "their employees' password-hash column, never visible in the response body itself)")
    void should_rejectSortProperty_when_dottedPathTargetsPasswordHashColumn() {
        UUID actorId = UUID.randomUUID();
        Pageable maliciousPageable =
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "master.user.passwordHash"));

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, buildAuth(Role.CLIENT), null, null, null, null, maliciousPageable))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("master.user.passwordHash")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
        verify(salonRepository, never()).findIdsByOwnerIdAndIsActiveTrue(any());
        verify(masterRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("BusinessException(400) is thrown, and NEITHER repository is ever touched, when sort "
            + "targets a plain unrecognised property that is not a dotted path at all — the whitelist "
            + "must reject any non-listed property, not only association traversals")
    void should_rejectSortProperty_when_propertyNotOnWhitelistAtAll() {
        UUID actorId = UUID.randomUUID();
        Pageable badPageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "foo"));

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, buildAuth(Role.CLIENT), null, null, null, null, badPageable))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("foo")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
        verify(salonRepository, never()).findIdsByOwnerIdAndIsActiveTrue(any());
        verify(masterRepository, never()).findByUserId(any());
    }

    // ── getMyBookings — sort cardinality bounds (Phase 26.3 audit F4; Phase 26.8 audit) ─────────
    //
    // normalizeBookingSort applies TWO independent cardinality guards, and the tests below pin
    // each in isolation. Every order used here repeats the SOLE whitelisted property as of Phase
    // 26.8 (startsAt — priceAtBooking was retired from SORTABLE_BOOKING_PROPERTIES once its only
    // caller, the provider sort sheet, was deleted by mobile Phase 7.8), so a rejection can ONLY
    // be attributed to cardinality, never to an unrecognised property name:
    //
    //   1. MAX_SORT_ORDERS = 3 — the outer length guard, checked FIRST so a pathological sort list
    //      is rejected before any per-order work. Distinguished by its "Too many sort properties"
    //      message.
    //   2. no repeated property — added by the Phase 26.8 audit. Each distinct (property,
    //      direction) sequence compiles to a textually distinct ORDER BY hence its own plan-cache
    //      entry, so repeats (which SQL ignores anyway — the first term for a column wins) were
    //      minting up to 14 plans where 2 suffice. Distinguished by "Duplicate sort property".
    //
    // Ordering between the two matters and is asserted: at 4 repeated orders the LENGTH guard must
    // win, otherwise MAX_SORT_ORDERS would be unreachable dead configuration.

    @Test
    @DisplayName("BusinessException(400) is thrown, and NEITHER repository is ever touched, when sort "
            + "carries 4 orders — all four repeat the sole whitelisted property (startsAt asc, desc, "
            + "asc, desc) so the rejection is attributable ONLY to exceeding MAX_SORT_ORDERS=3, never "
            + "to the property whitelist")
    void should_rejectSort_when_fourSortOrdersExceedTheCountBound() {
        UUID actorId = UUID.randomUUID();
        Sort fourOrders = Sort.by(Sort.Direction.ASC, "startsAt")
                .and(Sort.by(Sort.Direction.DESC, "startsAt"))
                .and(Sort.by(Sort.Direction.ASC, "startsAt"))
                .and(Sort.by(Sort.Direction.DESC, "startsAt"));
        Pageable tooManyOrdersPageable = PageRequest.of(0, 20, fourOrders);

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, buildAuth(Role.CLIENT), null, null, null, null, tooManyOrdersPageable))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many sort properties")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
        verify(salonRepository, never()).findIdsByOwnerIdAndIsActiveTrue(any());
        verify(masterRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("BusinessException(400) 'Duplicate sort property' is thrown, and NEITHER repository is "
            + "ever touched, when the SAME whitelisted property is sent twice (startsAt asc, startsAt "
            + "desc) — under MAX_SORT_ORDERS=3, so the rejection is attributable ONLY to the repeat")
    void should_rejectSort_when_theSameWhitelistedPropertyIsRepeated() {
        UUID actorId = UUID.randomUUID();
        Sort repeatedProperty = Sort.by(Sort.Direction.ASC, "startsAt")
                .and(Sort.by(Sort.Direction.DESC, "startsAt"));
        Pageable repeatedPropertyPageable = PageRequest.of(0, 20, repeatedProperty);

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, buildAuth(Role.CLIENT), null, null, null, null, repeatedPropertyPageable))
                .isInstanceOf(BusinessException.class)
                // Not "Too many sort properties": two orders is within MAX_SORT_ORDERS, so only the
                // repeat guard can be firing. A generic 400 assertion would pass even if the length
                // guard had been silently tightened to 1 instead.
                .hasMessageContaining("Duplicate sort property")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
        verify(salonRepository, never()).findIdsByOwnerIdAndIsActiveTrue(any());
        verify(masterRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("a single order on the sole whitelisted property is accepted — the CLIENT projection "
            + "query is reached with (startsAt asc + mandatory id tiebreaker), proving the two "
            + "cardinality guards reject only repeats/overflow and never the one legitimate shape")
    void should_acceptSort_when_singleWhitelistedPropertySupplied() {
        Sort singleOrder = Sort.by(Sort.Direction.ASC, "startsAt");
        Pageable requestedPageable = Pageable.unpaged(singleOrder);
        Sort expectedNormalizedSort = singleOrder.and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable expectedNormalizedPageable = Pageable.unpaged(expectedNormalizedSort);

        when(bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, expectedNormalizedPageable))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, null, requestedPageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByClientIdFiltered(clientId, null, null, null, null, expectedNormalizedPageable);
    }

    // ── getMyBookings — serviceId cap (Phase 26.4 audit, MAX_SERVICE_ID_FILTER
    //    defense-in-depth) — the controller's @Size(max = 50) is the first line of defense; this
    //    isolates the SERVICE-level bound so a future caller that reaches getMyBookings directly
    //    (bypassing the controller layer, e.g. a new internal caller) still cannot build an
    //    unbounded IN-list. ──────────────────────────────────────────────────────

    @Test
    @DisplayName("BusinessException(400) is thrown, and NEITHER repository is ever touched, when "
            + "51 distinct serviceId values are supplied directly to the service — the "
            + "service-level MAX_SERVICE_ID_FILTER=50 bound is defense-in-depth independent of the "
            + "controller's @Size(max = 50)")
    void should_rejectServiceIdFilter_when_51DistinctValuesExceedTheCountBound() {
        List<UUID> fiftyOneIds = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOneIds.add(UUID.randomUUID());
        }
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        clientId, buildAuth(Role.CLIENT), null, null, null, fiftyOneIds, pageable))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
        verify(salonRepository, never()).findIdsByOwnerIdAndIsActiveTrue(any());
        verify(masterRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("exactly 50 distinct serviceId values are accepted — the CLIENT projection query is "
            + "reached with the normalized, de-duplicated 50-element set, proving the bound is "
            + "\">50 rejects\", not \">=50 rejects\" (no off-by-one against MAX_SERVICE_ID_FILTER=50)")
    void should_acceptServiceIdFilter_when_exactly50DistinctValuesAtTheCountBound() {
        List<UUID> fiftyIds = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fiftyIds.add(UUID.randomUUID());
        }
        java.util.Set<UUID> expectedServiceIds = new java.util.LinkedHashSet<>(fiftyIds);
        Pageable pageable = Pageable.unpaged();
        Pageable expectedNormalizedPageable = Pageable.unpaged(DEFAULT_BOOKING_SORT_WITH_TIEBREAKER());

        when(bookingRepository.findIdsByClientIdFiltered(
                        eq(clientId), eq(null), eq(null), eq(null), eq(expectedServiceIds), eq(expectedNormalizedPageable)))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                clientId, buildAuth(Role.CLIENT), null, null, null, fiftyIds, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByClientIdFiltered(
                eq(clientId), eq(null), eq(null), eq(null), eq(expectedServiceIds), eq(expectedNormalizedPageable));
    }

    /** Mirrors {@code BookingService.normalizeBookingSort}'s default-sort-plus-tiebreaker shape
     * for an unsorted {@code Pageable.unpaged()} input: {@code startsAt DESC} then {@code id ASC}. */
    private static Sort DEFAULT_BOOKING_SORT_WITH_TIEBREAKER() {
        return Sort.by(Sort.Direction.DESC, "startsAt").and(Sort.by(Sort.Direction.ASC, "id"));
    }

    // ── getBooking ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BookingDetailResponse is returned when an authorized actor requests their own booking")
    void should_returnBooking_when_getBookingCalledByOwner() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // CONFIRMED (not COMPLETED) short-circuits canReview before the review-existence query —
        // no reviewRepository stub needed here.
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(bookingId);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.canReview()).isFalse();
        verify(authz).enforceCanViewBooking(clientId, booking);
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("ForbiddenException is thrown when client B attempts to read client A's booking")
    void should_throw403_when_clientBReadsClientABooking() {
        UUID clientBId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanViewBooking(clientBId, booking);

        assertThatThrownBy(() -> bookingService.getBooking(clientBId, bookingId))
                .isInstanceOf(ForbiddenException.class);

        verify(authz).enforceCanViewBooking(clientBId, booking);
    }

    @Test
    @DisplayName("BookingDetailResponse is returned instead of NPEing when a provider views their own guest (LINK) booking")
    void should_returnBookingDetail_when_ownerViewsGuestBooking() {
        UUID providerActorId = UUID.randomUUID();
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        setField(guestBooking, "client", null);
        setField(guestBooking, "guestName", "Оксана");
        setField(guestBooking, "guestSurname", "Мельник");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));
        // No client (guest/LINK booking) short-circuits canReview before the review-existence
        // query — no reviewRepository stub needed here.
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(providerActorId, bookingId);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(bookingId);
        assertThat(result.clientId()).isNull();
        assertThat(result.clientFirstName()).isEqualTo("Оксана");
        assertThat(result.clientLastName()).isEqualTo("Мельник");
        verify(authz).enforceCanViewBooking(providerActorId, guestBooking);
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("canReview is false for a COMPLETED guest (LINK) booking with no existing review — "
            + "no account exists to leave one, and the review-existence check is never reached")
    void should_returnCanReviewFalse_when_completedGuestBookingHasNoClient() {
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        setField(guestBooking, "client", null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));
        // No client short-circuits canReview even though the booking is COMPLETED — the
        // review-existence query never fires, so no reviewRepository stub is needed here.
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(UUID.randomUUID(), bookingId);

        assertThat(result.canReview()).isFalse();
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    // ── getBooking — canReview truth table (Phase 19.3) ──────────────────────────
    //
    // canReview = (status == COMPLETED) && no existing Review for the booking.
    // Each row of the truth table stubs reviewRepository.existsByBookingId and the
    // status, then asserts the single observable predicate on the response.

    private BookingDetailResponse getBookingWith(BookingStatus status, boolean reviewExists) {
        Booking booking = buildBooking(bookingId, client, master, msa, status);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // Only a COMPLETED booking can reach the review-existence query (canReview's short
        // circuit skips it otherwise) — stub it only when the caller needs it to fire.
        if (status == BookingStatus.COMPLETED) {
            // Phase 317 — the detail path's ONE review lookup is now findViewByBookingId: its
            // PRESENCE is canReview's reviewExists input and its body is reviewByClient. The
            // statement count and the short-circuit in front of it are unchanged.
            when(reviewRepository.findViewByBookingId(bookingId)).thenReturn(
                    reviewExists
                            ? Optional.of(new com.beautica.review.repository.BookingReviewView((short) 5, "Гарно"))
                            : Optional.empty());
        }
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        return bookingService.getBooking(clientId, bookingId);
    }

    @Test
    @DisplayName("canReview is false for a DECLINED booking (not COMPLETED), and the "
            + "review-existence check is never reached")
    void should_returnCanReviewFalse_when_bookingDeclined() {
        assertThat(getBookingWith(BookingStatus.DECLINED, false).canReview()).isFalse();
        // A DECLINED booking is never review-eligible, so the existence check is irrelevant
        // to the outcome — the predicate short-circuits to false on status before the query runs.
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("canReview is false for a CONFIRMED booking (not COMPLETED), and the "
            + "review-existence check is never reached")
    void should_returnCanReviewFalse_when_bookingConfirmed() {
        assertThat(getBookingWith(BookingStatus.CONFIRMED, false).canReview()).isFalse();
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("canReview is true for a COMPLETED booking with no existing review")
    void should_returnCanReviewTrue_when_bookingCompletedAndNoReview() {
        assertThat(getBookingWith(BookingStatus.COMPLETED, false).canReview()).isTrue();
    }

    @Test
    @DisplayName("canReview is false for a COMPLETED booking that already has a review")
    void should_returnCanReviewFalse_when_bookingCompletedAndReviewExists() {
        assertThat(getBookingWith(BookingStatus.COMPLETED, true).canReview()).isFalse();
    }

    // ── getBooking — providerCanReviewClient truth table (track 27.x / Phase 27.5, narrowed 320) ──
    //
    // providerCanReviewClient = !authz.isOwningClientViewer(actor, booking)
    //     && authz.isPerformingMasterOfBooking(actor, booking)
    //     && BookingClosureRule.isProviderReviewEligible(status)
    //     && booking.getClient() != null
    //     && !clientReviewRepository.existsByBookingId(booking.getId())
    //
    // isProviderReviewEligible = status == COMPLETED, STRICTLY — unlike the client-side
    // canReview/ReviewService path's BookingClosureRule#isReviewEligible, this direction does NOT
    // widen to an elapsed-but-unclosed CONFIRMED booking: the provider controls their own closing
    // action (PATCH .../complete), so a rating must follow it, never substitute for it. See the
    // dedicated ELAPSED-still-false / FUTURE-false / NOT_COMPLETED-still-false cases below.
    //
    // ProviderCanReviewClientIT already pins this end-to-end through real HTTP + role/authority
    // resolution. These cases isolate BookingService#computeProviderCanReviewClient itself via a
    // mocked AuthorizationService/ClientReviewRepository — fast unit coverage of the same 4-way
    // AND, and (unlike the IT) able to pin the short-circuit ordering: a later collaborator must
    // never be consulted once an earlier condition has already failed.
    //
    // PHASE 320 — the authority term is the PERFORMING MASTER, and nothing else. It used to be
    // isPerformingMasterOfBooking UNIONED with hasProviderAuthorityOverBooking, which is why these
    // cases stubbed the latter. The locked product decision — "salon owner or salon admin can
    // complete the booking, and after it only salon master can leave the feedback" — deleted that
    // disjunct, so a SALON_OWNER/SALON_ADMIN who is not the performer now reads false here. The
    // stubs moved with the predicate; nothing else about this truth table changed. Owner and admin
    // keep /complete, /not-complete, /decline and /reschedule — those run off the UNTOUCHED
    // hasProviderAuthorityOverBooking, which must never reappear on this path.

    @Test
    @DisplayName("providerCanReviewClient is true when the actor IS the performing master of a COMPLETED, non-guest, unreviewed booking")
    void should_returnProviderCanReviewClientTrue_when_authorityCompletedNoReview() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(reviewRepository.findViewByBookingId(bookingId)).thenReturn(Optional.empty());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(clientId, booking)).thenReturn(true);
        when(clientReviewRepository.existsByBookingId(bookingId)).thenReturn(false);

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result.providerCanReviewClient()).isTrue();
        verify(clientReviewRepository).existsByBookingId(bookingId);
    }

    @Test
    @DisplayName("providerCanReviewClient is false when the actor is NOT the performing master, even on a COMPLETED unreviewed booking — a SALON_OWNER/SALON_ADMIN who may COMPLETE it still reads false (Phase 320) — and the review-existence check is never reached")
    void should_returnProviderCanReviewClientFalse_when_actorLacksProviderAuthority() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(reviewRepository.findViewByBookingId(bookingId)).thenReturn(Optional.empty());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(clientId, booking)).thenReturn(false);

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result.providerCanReviewClient())
                .as("a CLIENT, a peer SALON_MASTER, a foreign viewer, and (Phase 320) the SALON_OWNER or SALON_ADMIN of the booking's own salon must all be denied the provider-review CTA — only the performing master gets it")
                .isFalse();
        verify(clientReviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("providerCanReviewClient is false for a CONFIRMED booking whose endsAt has NOT "
            + "yet elapsed (not COMPLETED, not awaiting closure), even for the performing master — "
            + "and the review-existence check is never reached")
    void should_returnProviderCanReviewClientFalse_when_bookingConfirmedAndNotYetElapsed() {
        // buildBooking pins startsAt = now+2h / endsAt = now+3h — a genuinely FUTURE booking.
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // Not review-eligible short-circuits canReview before its own review-existence
        // query too — no reviewRepository stub needed here.
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(clientId, booking)).thenReturn(true);

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result.providerCanReviewClient()).isFalse();
        verify(clientReviewRepository, never()).existsByBookingId(any());
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("providerCanReviewClient is false for a CONFIRMED booking whose endsAt has already "
            + "ELAPSED, even though the client-side canReview/ReviewService path would treat this "
            + "same shape as reviewable — the provider direction requires COMPLETED strictly, and "
            + "the review-existence check is never reached")
    void should_returnProviderCanReviewClientFalse_when_bookingConfirmedButElapsed() {
        // buildBookingStartingAt with a past startsAt yields endsAt = startsAt + 1h, still
        // strictly before "now" (clock is fixed at Instant.now() in setUp) for a 3h-ago start.
        Booking booking = buildBookingStartingAt(bookingId, client, master, msa, BookingStatus.CONFIRMED,
                ZonedDateTime.now(clock).minusHours(3).toOffsetDateTime());
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(clientId, booking)).thenReturn(true);

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result.providerCanReviewClient())
                .as("an elapsed-but-unclosed CONFIRMED booking must NOT offer the provider-review "
                        + "CTA — the provider must actually close the booking before rating the "
                        + "client")
                .isFalse();
        verify(clientReviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("providerCanReviewClient stays false for a NOT_COMPLETED (no-show) booking even "
            + "with an elapsed endsAt and the performing master asking — guards against over-widening "
            + "isProviderReviewEligible beyond COMPLETED")
    void should_returnProviderCanReviewClientFalse_when_bookingNotCompletedNoShow() {
        Booking booking = buildBookingStartingAt(bookingId, client, master, msa, BookingStatus.NOT_COMPLETED,
                ZonedDateTime.now(clock).minusHours(3).toOffsetDateTime());
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(clientId, booking)).thenReturn(true);

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result.providerCanReviewClient())
                .as("NOT_COMPLETED is an explicit no-show marking, never reviewable regardless of endsAt")
                .isFalse();
        verify(clientReviewRepository, never()).existsByBookingId(any());
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("providerCanReviewClient is false for a COMPLETED guest (LINK, null-client) booking — no account exists to review, and the review-existence check is never reached")
    void should_returnProviderCanReviewClientFalse_when_bookingHasNoClient() {
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        setField(guestBooking, "client", null);
        setField(guestBooking, "guestName", "Гість");
        setField(guestBooking, "guestSurname", "Тестовий");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));
        // No client short-circuits canReview even though the booking is COMPLETED — no
        // reviewRepository stub needed here.
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(clientId, guestBooking)).thenReturn(true);

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result.providerCanReviewClient()).isFalse();
        verify(clientReviewRepository, never()).existsByBookingId(any());
        verify(reviewRepository, never()).existsByBookingId(any());
    }

    @Test
    @DisplayName("providerCanReviewClient is false once a ClientReview already exists for the booking, even for the performing master on a COMPLETED booking")
    void should_returnProviderCanReviewClientFalse_when_clientReviewAlreadyExists() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(reviewRepository.findViewByBookingId(bookingId)).thenReturn(Optional.empty());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(clientId, booking)).thenReturn(true);
        when(clientReviewRepository.existsByBookingId(bookingId)).thenReturn(true);

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result.providerCanReviewClient())
                .as("a booking that already has a client review must never re-offer the CTA")
                .isFalse();
    }

    // ── listProviderBookings — loadProviderReviewBatch pre-filter (audit-fix cycle 1, item 1) ──
    //
    // The pre-filter in BookingService#loadProviderReviewBatch narrows the page to
    // (client != null && BookingClosureRule.isProviderReviewEligible(status)) BEFORE evaluating
    // AuthorizationService#isPerformingMasterOfBooking and issuing
    // ClientReviewRepository#findReviewedBookingIds. Every existing providerCanReviewClient test
    // exercises the FINAL conjunction (which re-checks isProviderReviewEligible on its own), so a
    // pre-filter mutation is invisible there. These two tests instead assert on the pre-filter's
    // only observable effect: which bookings reach the authority term and the review-existence
    // query.
    //
    // Phase 320 — the batched AuthorizationService#filterBookingIdsWithProviderAuthority these used
    // to assert against is deleted along with the salon arm of the review predicate. The pre-filter
    // is unchanged and still observable, now through the in-memory performing-master term.

    @Test
    @DisplayName("loadProviderReviewBatch's pre-filter submits ONLY the COMPLETED booking to the "
            + "authority term and to the review-existence query, excluding a sibling "
            + "elapsed-but-unclosed CONFIRMED booking in the same page")
    void should_passOnlyCompletedBookingId_toProviderReviewBatchQueries_when_pageMixesCompletedAndElapsedConfirmed() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking completedBooking = buildBooking(UUID.randomUUID(), client, master, msa, BookingStatus.COMPLETED);
        Booking elapsedConfirmedBooking = buildBookingStartingAt(UUID.randomUUID(), client, master, msa,
                BookingStatus.CONFIRMED, ZonedDateTime.now(clock).minusHours(3).toOffsetDateTime());
        List<UUID> pageIds = List.of(completedBooking.getId(), elapsedConfirmedBooking.getId());

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(pageIds));
        when(bookingRepository.findAllByIdsWithGraph(pageIds))
                .thenReturn(List.of(completedBooking, elapsedConfirmedBooking));
        when(reviewRepository.findReviewedBookingIds(pageIds)).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        when(authz.isPerformingMasterOfBooking(actorId, completedBooking)).thenReturn(true);
        when(clientReviewRepository.findReviewedBookingIds(List.of(completedBooking.getId())))
                .thenReturn(List.of());

        bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        verify(authz).isPerformingMasterOfBooking(actorId, completedBooking);
        verify(authz, never()).isPerformingMasterOfBooking(actorId, elapsedConfirmedBooking);
        verify(clientReviewRepository).findReviewedBookingIds(List.of(completedBooking.getId()));
    }

    @Test
    @DisplayName("loadProviderReviewBatch short-circuits BEFORE the authority term and the review "
            + "query when every row in the page is CONFIRMED (none COMPLETED) — the "
            + "empty-candidate fast path must evaluate neither")
    void should_skipBothBatchedQueries_when_noRowInPageIsProviderReviewEligible() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking futureConfirmedBooking = buildBooking(UUID.randomUUID(), client, master, msa, BookingStatus.CONFIRMED);
        Booking elapsedConfirmedBooking = buildBookingStartingAt(UUID.randomUUID(), client, master, msa,
                BookingStatus.CONFIRMED, ZonedDateTime.now(clock).minusHours(3).toOffsetDateTime());
        List<UUID> pageIds = List.of(futureConfirmedBooking.getId(), elapsedConfirmedBooking.getId());

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(pageIds));
        when(bookingRepository.findAllByIdsWithGraph(pageIds))
                .thenReturn(List.of(futureConfirmedBooking, elapsedConfirmedBooking));
        when(reviewRepository.findReviewedBookingIds(pageIds)).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, null, pageable);

        verify(authz, never()).isPerformingMasterOfBooking(any(), any());
        verify(clientReviewRepository, never()).findReviewedBookingIds(any());
    }

    // ── getBooking — enriched fields (Phase 19.3) ────────────────────────────────

    /** Builds a master whose own User row carries an avatar + address + role. */
    private Master buildEnrichedMaster(MasterType type, Role userRole, String avatarUrl,
                                       UUID cityId, UUID districtId, String street, String buildingNo) {
        User masterUser = new User("master@example.com", "hash", userRole, "Olena", "Koval", "+380509999999");
        setField(masterUser, "id", UUID.randomUUID());
        masterUser.setAvatarUrl(avatarUrl);
        masterUser.setCityId(cityId);
        masterUser.setDistrictId(districtId);
        masterUser.setStreet(street);
        masterUser.setBuildingNo(buildingNo);
        Master m = Master.builder().user(masterUser).masterType(type).isActive(true).build();
        setField(m, "id", masterId);
        return m;
    }

    @Test
    @DisplayName("getBooking populates the master avatar, type, own-locality address, category and a null salonName for an independent-master booking")
    void should_populateEnrichedFields_when_independentMasterBooking() {
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        Master enriched = buildEnrichedMaster(
                MasterType.INDEPENDENT_MASTER, Role.INDEPENDENT_MASTER,
                "https://cdn.test/avatar.png", cityId, districtId, "Khreschatyk", "10");
        enriched.getUser().setLocationNote("Ring the bell twice");
        MasterServiceAssignment enrichedMsa = buildMsa(masterServiceId, enriched, serviceDef, null, null);
        Booking booking = buildBooking(bookingId, client, enriched, enrichedMsa, BookingStatus.COMPLETED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(reviewRepository.findViewByBookingId(bookingId)).thenReturn(Optional.empty());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(
                new com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels(
                        Map.of(cityId, "Kyiv"), Map.of(districtId, "Shevchenkivskyi")));

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result)
                .extracting(
                        BookingDetailResponse::masterAvatarUrl,
                        BookingDetailResponse::masterType,
                        BookingDetailResponse::salonName,
                        BookingDetailResponse::cityLabel,
                        BookingDetailResponse::districtLabel,
                        BookingDetailResponse::street,
                        BookingDetailResponse::buildingNo,
                        BookingDetailResponse::locationNote,
                        BookingDetailResponse::canReview)
                .containsExactly(
                        "https://cdn.test/avatar.png",
                        Role.INDEPENDENT_MASTER,
                        null,
                        "Kyiv",
                        "Shevchenkivskyi",
                        "Khreschatyk",
                        "10",
                        "Ring the bell twice",
                        true);
    }

    @Test
    @DisplayName("getBooking surfaces the BOOKED salon's name and address/labels — never the "
            + "master's own row, and never the salon the master has since rotated to")
    void should_populateSalonFields_when_salonEmployedMasterBooking() {
        UUID salonCityId = UUID.randomUUID();
        UUID salonDistrictId = UUID.randomUUID();
        // Master's own user row carries DIFFERENT locality to prove the salon link wins.
        Master enriched = buildEnrichedMaster(
                MasterType.SALON_MASTER, Role.SALON_MASTER,
                "https://cdn.test/salon-master.png", UUID.randomUUID(), UUID.randomUUID(), "OwnStreet", "99");
        // The master's own note is DIFFERENT to prove the salon wins, never the master's own note.
        enriched.getUser().setLocationNote("Master's own note - must NOT surface");
        com.beautica.salon.entity.Salon salon = com.beautica.salon.entity.Salon.builder()
                .name("Glamour Studio")
                .cityId(salonCityId)
                .districtId(salonDistrictId)
                .street("Volodymyrska")
                .buildingNo("55")
                .locationNote("3rd floor, door code 1234")
                .isActive(true)
                .build();
        // Phase 242 — the master has SINCE ROTATED to another salon, whose address and door code
        // must not surface anywhere. masters.salon_id moves; bookings.salon_id is a snapshot and
        // does not. Stamping only master.salon (as this fixture used to) would let a
        // master.getSalon()-sourced implementation pass.
        com.beautica.salon.entity.Salon rotatedToSalon = com.beautica.salon.entity.Salon.builder()
                .name("Rotated-To Studio - must NOT surface")
                .cityId(UUID.randomUUID())
                .districtId(UUID.randomUUID())
                .street("RotatedStreet - must NOT surface")
                .buildingNo("77")
                .locationNote("Rotated-to door code 9999 - must NOT surface")
                .isActive(true)
                .build();
        setField(enriched, "salon", rotatedToSalon);
        MasterServiceAssignment enrichedMsa = buildMsa(masterServiceId, enriched, serviceDef, null, null);
        Booking booking = buildBooking(bookingId, client, enriched, enrichedMsa, BookingStatus.CONFIRMED);
        setField(booking, "salon", salon);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // CONFIRMED (not COMPLETED) short-circuits canReview before the review-existence query —
        // no reviewRepository stub needed here.
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(
                new com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels(
                        Map.of(salonCityId, "Lviv"), Map.of(salonDistrictId, "Halytskyi")));

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result)
                .extracting(
                        BookingDetailResponse::salonName,
                        BookingDetailResponse::masterType,
                        BookingDetailResponse::cityLabel,
                        BookingDetailResponse::districtLabel,
                        BookingDetailResponse::street,
                        BookingDetailResponse::buildingNo,
                        BookingDetailResponse::locationNote)
                .containsExactly(
                        "Glamour Studio",
                        Role.SALON_MASTER,
                        "Lviv",
                        "Halytskyi",
                        "Volodymyrska",
                        "55",
                        "3rd floor, door code 1234");
        // The negatives, explicitly: neither the rotated-to salon nor the master's personal row
        // may reach any of these fields. A positive-only check passes on a wrong-source
        // implementation whenever two fixtures happen to agree.
        assertThat(result.locationNote())
                .isNotEqualTo("Rotated-to door code 9999 - must NOT surface")
                .isNotEqualTo("Master's own note - must NOT surface");
        assertThat(result.salonName()).isNotEqualTo("Rotated-To Studio - must NOT surface");
        assertThat(result.street()).isNotEqualTo("RotatedStreet - must NOT surface")
                .isNotEqualTo("OwnStreet");
    }

    @Test
    @DisplayName("locationNote and masterProfessionalTitle are null (not NPE) on a guest (LINK) booking whose master has set neither")
    void should_returnNullLocationNoteAndTitle_when_guestBookingAndMasterHasNeither() {
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        setField(guestBooking, "client", null);
        setField(guestBooking, "guestName", "Оксана");
        setField(guestBooking, "guestSurname", "Мельник");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));
        // No client (guest/LINK booking) short-circuits canReview before the review-existence
        // query — no reviewRepository stub needed here.
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(UUID.randomUUID(), bookingId);

        assertThat(result.clientId()).isNull();
        assertThat(result.masterProfessionalTitle()).isNull();
        assertThat(result.locationNote()).isNull();
    }

    // ── cancelAppointmentItem (phase 30.6) — appointment-scoped per-item cancel ─────────────────
    //
    // Reached via PATCH /appointments/{appointmentId}/services/{bookingId}/cancel. Adds exactly
    // two guards (visit ownership, path consistency) then delegates to cancelBooking VERBATIM —
    // these tests pin the guard ordering and the non-forked delegation, never re-testing
    // cancelBooking's own body (that is already covered above).

    @Test
    @DisplayName("cancelAppointmentItem — a foreign visit (client_id belongs to someone else) is "
            + "a 403 and cancelBooking's own load is never reached")
    void should_throwForbidden_when_cancelAppointmentItemCalledOnForeignVisit() {
        UUID appointmentId = UUID.randomUUID();
        UUID foreignClientId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(foreignClientId));

        assertThatThrownBy(() -> bookingService.cancelAppointmentItem(clientId, appointmentId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);

        // Perf audit F2 (cross-batch): the dedicated existsByIdAndAppointmentId probe was removed —
        // path consistency now folds into this SAME findByIdWithFullGraph call, so asserting it is
        // never reached also proves the (now-deleted) exists probe's job never ran either.
        verify(bookingRepository, never()).findByIdWithFullGraph(any());
    }

    @Test
    @DisplayName("cancelAppointmentItem — a missing/guest appointment id collapses to the SAME "
            + "uniform 403 as a foreign visit (no existence oracle)")
    void should_throwForbidden_when_cancelAppointmentItemCalledWithMissingOrGuestAppointment() {
        UUID appointmentId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.cancelAppointmentItem(clientId, appointmentId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);

        verify(bookingRepository, never()).findByIdWithFullGraph(any());
    }

    @Test
    @DisplayName("cancelAppointmentItem — bookingId is not a child of appointmentId is a 404, "
            + "reached only AFTER visit ownership is already established, and nothing is mutated "
            + "(perf audit F2: path consistency is now checked in-memory on the SAME "
            + "findByIdWithFullGraph load the mutation would have used, not a separate exists probe)")
    void should_throwNotFound_when_cancelAppointmentItemTargetIsNotAChildOfAppointment() {
        UUID appointmentId = UUID.randomUUID();
        UUID otherAppointmentId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        Booking foreignChild = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        foreignChild.setAppointment(Appointment.builder().id(otherAppointmentId).build());
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(foreignChild));

        assertThatThrownBy(() -> bookingService.cancelAppointmentItem(clientId, appointmentId, bookingId, req))
                .isInstanceOf(NotFoundException.class);

        assertThat(foreignChild.getStatus())
                .as("a bookingId belonging to a DIFFERENT appointment must not be mutated")
                .isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelAppointmentItem — a bookingId that does not exist at all is the SAME 404 as "
            + "one that exists under a different appointment (findByIdWithFullGraph empty)")
    void should_throwNotFound_when_cancelAppointmentItemTargetDoesNotExist() {
        UUID appointmentId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.cancelAppointmentItem(clientId, appointmentId, bookingId, req))
                .isInstanceOf(NotFoundException.class);

        verify(bookingRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelAppointmentItem — happy path reuses cancelBooking's shared mutation body with "
            + "the ALREADY-loaded, path-verified Booking (perf audit F2: findByIdWithFullGraph is "
            + "called exactly ONCE for the whole operation, not once for the exists-style check and "
            + "once more inside cancelBooking) — outcome is byte-for-byte the same as before (phase "
            + "30.6 D1/D6)")
    void should_delegateToCancelBookingUnchanged_when_cancelAppointmentItemAuthorized() {
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = Appointment.builder().id(appointmentId).build();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        booking.setAppointment(appointment);
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, "не потрібно");
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        when(appointmentTransitionService.lockAppointmentHeaderBeforeClientItemCancel(appointmentId))
                .thenReturn(true);
        // F1 freshness re-check (cycle-6 audit 2026-08-03) — this child is still CONFIRMED as of
        // the post-lock scalar probe (no concurrent writer raced this test's single-threaded call).
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(true);

        BookingResponse result = bookingService.cancelAppointmentItem(clientId, appointmentId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(result.id()).isEqualTo(bookingId);
        verify(appointmentTransitionService)
                .collapseAppointmentHeaderAfterClientItemCancel(appointmentId, true, "не потрібно");
        verify(bookingRepository, times(1)).findByIdWithFullGraph(bookingId);
    }
}
