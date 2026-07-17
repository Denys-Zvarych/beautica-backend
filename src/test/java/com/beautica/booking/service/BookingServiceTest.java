package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.BookingRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private SlotCalculationService slotCalculationService;
    @Mock
    private com.beautica.review.repository.ReviewRepository reviewRepository;
    @Mock
    private com.beautica.location.DiscoveryLocationResolver discoveryLocationResolver;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Mock
    private ScheduleDateMath dateMath;

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
                slotCalculationService,
                reviewRepository,
                discoveryLocationResolver,
                clock,
                cacheManager,
                salonCatalogCacheEvictor,
                dateMath
        );

        clientId = UUID.randomUUID();
        masterId = UUID.randomUUID();
        masterServiceId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        client = buildUser(clientId, Role.CLIENT);
        master = buildMaster(masterId, MasterType.INDEPENDENT_MASTER);
        serviceDef = buildServiceDef(new BigDecimal("200.00"), 60, 0);
        msa = buildMsa(masterServiceId, master, serviceDef, null, null);
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
        Booking b = Booking.builder()
                .client(c)
                .master(m)
                .masterService(a)
                .status(status)
                .startsAt(ZonedDateTime.now(clock).plusHours(2).toOffsetDateTime())
                .endsAt(ZonedDateTime.now(clock).plusHours(3).toOffsetDateTime())
                .priceAtBooking(new BigDecimal("200.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        setField(b, "id", id);
        ReflectionTestUtils.setField(b, "createdAt", Instant.now());
        return b;
    }

    private CreateBookingRequest validRequest() {
        return new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusHours(2),
                null,
                null
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

    // ── createBooking ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking is created and saved when the slot is free and no overlap exists")
    void should_createBooking_when_slotAvailableAndNoConflict() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

        BookingResponse result = bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result).isNotNull();
        verify(slotCalculationService).evictAvailableSlots(
                eq(masterId),
                any(LocalDate.class),
                eq(masterServiceId)
        );
    }

    @Test
    @DisplayName("SALON_OWNER master — booking creation proceeds identically to INDEPENDENT_MASTER")
    void should_bookOwnerMaster_when_clientBooksAvailableSlot() {
        // Arrange — build an SALON_OWNER-type master (same as INDEPENDENT_MASTER from BookingService's perspective)
        Master ownerMaster = buildMaster(masterId, MasterType.SALON_OWNER);
        MasterServiceAssignment ownerMsa = buildMsa(masterServiceId, ownerMaster, serviceDef, null, null);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(ownerMaster));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(ownerMsa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));

        Booking saved = buildBooking(bookingId, client, ownerMaster, ownerMsa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

        // Act
        BookingResponse result = bookingService.createBooking(clientId, null, validRequest());

        // Assert — booking created; master_id is the owner-master's ID
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMaster().getMasterType()).isEqualTo(MasterType.SALON_OWNER);
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result).isNotNull();
        verify(slotCalculationService).evictAvailableSlots(eq(masterId), any(LocalDate.class), eq(masterServiceId));
    }

    @Test
    @DisplayName("existing booking is returned without saving when idempotency key already exists")
    void should_returnExistingBooking_when_idempotencyKeyMatches() {
        String key = "unique-key-123";
        Booking existing = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findActiveByClientIdAndIdempotencyKey(clientId, key))
                .thenReturn(Optional.of(existing));

        BookingResponse result = bookingService.createBooking(clientId, key, validRequest());

        verify(bookingRepository, never()).saveAndFlush(any());
        assertThat(result.id()).isEqualTo(bookingId);
    }

    @Test
    @DisplayName("409 Conflict is thrown when the requested slot overlaps an existing booking")
    void should_throw409_when_slotOverlapsExistingBooking() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("409 CLIENT_BOOKING_CONFLICT is thrown when the client already holds an overlapping "
            + "booking with a DIFFERENT master — the master-busy check never runs")
    void should_throwClientBookingConflict_when_clientAlreadyHasOverlappingBookingWithDifferentMaster() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
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
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

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
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("404 NotFoundException is thrown when the master service assignment does not exist")
    void should_throw404_when_masterServiceNotFound() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
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
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(inactiveMaster));

        // Act + Assert — the filter(Master::isActive) turns the Optional empty
        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("404 NotFoundException is thrown when the requested service does not belong to the master")
    void should_throwNotFoundException_when_serviceDoesNotBelongToMaster() {
        // Arrange — master is found and active; but the service lookup returns empty
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("400 is thrown when the requested start time is in the past")
    void should_throw400_when_startsAtInThePast() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest pastRequest = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).minusMinutes(10),
                null,
                null
        );

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, pastRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("400 is thrown when the requested start time is exactly 14 minutes from now (below minimum lead time)")
    void should_throw400_when_startsAtIsExactly14MinutesFromNow() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest request = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusMinutes(14),
                null,
                null
        );

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("booking proceeds past time check when start time is exactly 15 minutes from now (minimum lead time boundary)")
    void should_proceedPastTimeCheck_when_startsAtIsExactly15MinutesFromNow() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

        CreateBookingRequest request = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusMinutes(15),
                null,
                null
        );

        BookingResponse result = bookingService.createBooking(clientId, null, request);

        assertThat(result).isNotNull();
        verify(bookingRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("400 is thrown when the requested start time is more than 180 days in the future")
    void should_throw400_when_startsAtMoreThan180DaysAhead() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest farFutureRequest = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusDays(181),
                null,
                null
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
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msaWithOverrides));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msaWithOverrides, BookingStatus.CONFIRMED);
        setField(saved, "priceAtBooking", new BigDecimal("250.00"));
        setField(saved, "durationMinutesAtBooking", 45);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

        bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPriceAtBooking()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(captor.getValue().getDurationMinutesAtBooking()).isEqualTo(45);
    }

    @Test
    @DisplayName("price and duration are snapshotted from base values when no overrides are set")
    void should_fallBackToBaseValues_when_noOverrides() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

        bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPriceAtBooking()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(captor.getValue().getDurationMinutesAtBooking()).isEqualTo(60);
    }

    @Test
    @DisplayName("new-booking notification is enqueued when the booking is successfully created")
    void should_enqueueNewBookingNotification_when_bookingCreated() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

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
        verify(slotCalculationService).evictAvailableSlots(
                eq(masterId),
                any(LocalDate.class),
                eq(masterServiceId)
        );
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

    // ── completeBooking ────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking moves to COMPLETED and notification is enqueued when a CONFIRMED booking is completed")
    void should_completeBooking_when_confirmedBookingCompleted() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.completeBooking(actorId, bookingId);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        verify(outboxService).enqueueStatusChanged(bookingId);
        // Phase 18.3: the review prompt is enqueued in the same completion transaction.
        verify(outboxService).enqueueReviewRequested(bookingId);
    }

    @Test
    @DisplayName("guest (LINK / null-client) completion enqueues STATUS_CHANGED but never REVIEW_REQUESTED")
    void should_notEnqueueReviewRequested_when_completingGuestBooking() {
        UUID actorId = UUID.randomUUID();
        // Guest booking: CONFIRMED with a null client (V89 chk_bookings_guest_fields). A guest has
        // no account to review with, so completion must not enqueue the review prompt (which would
        // NPE on booking.getClient() at drain time and dead-letter the outbox row).
        Booking booking = buildBooking(bookingId, null, master, msa, BookingStatus.CONFIRMED);
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
    @DisplayName("revenue-dashboard cache is evicted for the actor when a booking is completed")
    void should_evictRevenueDashboardCache_when_bookingCompleted() {
        // Arrange
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
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
    @DisplayName("booking moves to NOT_COMPLETED with CLIENT_NO_SHOW reason when master records a no-show")
    void should_markNotCompleted_when_masterRecordsNoShow() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, "No show");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.notCompleteBooking(actorId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.NOT_COMPLETED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.CLIENT_NO_SHOW);
        verify(outboxService).enqueueStatusChanged(bookingId);
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
    @DisplayName("revenue-dashboard cache is evicted for the actor when a booking is marked not-completed")
    void should_evictRevenueDashboardCache_when_bookingMarkedNotCompleted() {
        // Arrange
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
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
        verify(slotCalculationService).evictAvailableSlots(
                eq(masterId),
                any(LocalDate.class),
                eq(masterServiceId)
        );
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
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime());
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BookingElapsedException.class);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        // Guard short-circuits ahead of the whole reschedule critical section (Q6 verify-not-called).
        verify(slotCalculationService, never()).getAvailableSlots(any(), any(), any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any());
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
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime());
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
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BookingDetailResponse result = bookingService.rescheduleBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getStartsAt()).isEqualTo(newStartsAt);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(outboxService).enqueueBookingRescheduled(bookingId);
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
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(LocalDate.class), eq(masterServiceId)))
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
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        stubRescheduleSlotAvailable(newStartsAt);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(bookingId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BookingDetailResponse result = bookingService.rescheduleBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getStartsAt()).isEqualTo(newStartsAt);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(outboxService).enqueueBookingRescheduled(bookingId);
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
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime());
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(otherClientId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);

        // Guard fires before any slot lookup / lock / persistence
        verify(slotCalculationService, never()).getAvailableSlots(any(), any(), any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any());
    }

    @Test
    @DisplayName("403 ForbiddenException is thrown when the booking has no client (guest booking) so no actor can own it")
    void should_throwForbidden_when_reschedulingGuestBooking() {
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        setField(guestBooking, "client", null);
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime());
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
                ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime());
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rescheduleBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(booking.getStatus()).isEqualTo(terminal);
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any());
    }

    @Test
    @DisplayName("400 is thrown when the new start time is below the 15-minute lead-time floor")
    void should_throw400_when_rescheduleNewTimeBelowLeadTime() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        RescheduleBookingRequest req = new RescheduleBookingRequest(
                ZonedDateTime.now(clock).plusMinutes(14).toOffsetDateTime());
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
                ZonedDateTime.now(clock).plusDays(181).toOffsetDateTime());
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
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        // No slot matches newStartsAt → off-schedule
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(LocalDate.class), eq(masterServiceId)))
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
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt);
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
        verify(outboxService, never()).enqueueBookingRescheduled(any());
    }

    @Test
    @DisplayName("409 CLIENT_BOOKING_CONFLICT is thrown when the new time overlaps another booking the "
            + "client holds with a DIFFERENT master, and the master-busy check never runs")
    void should_throwClientBookingConflict_when_rescheduleOverlapsClientsOtherBookingWithDifferentMaster() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        OffsetDateTime newStartsAt = ZonedDateTime.now(clock).plusHours(4).toOffsetDateTime();
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt);
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
        verify(outboxService, never()).enqueueBookingRescheduled(any());
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
        RescheduleBookingRequest req = new RescheduleBookingRequest(newStartsAt);
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
        bookingService.rescheduleBooking(clientId, bookingId, new RescheduleBookingRequest(newStartsAt));
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
                        List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                actorId, buildAuth(Role.SALON_OWNER), List.of(BookingStatus.CONFIRMED), null, null, pageable);

        assertThat(result.totalElements()).isZero();
        verify(bookingRepository)
                .findIdsBySalonIdsFiltered(List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("all bookings page is returned when salon owner queries without a status filter")
    void should_returnAllBookings_when_salonOwnerListsWithoutStatus() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("empty status list behaves exactly as absent — normalised to null, not an empty IN list "
            + "(Phase 26.1: ?status= must not match nothing)")
    void should_normalizeEmptyStatusList_to_null_when_salonOwnerListsWithEmptyStatusList() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), List.of(), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, normalizedUnpaged());
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
                        List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER),
                List.of(BookingStatus.CONFIRMED, BookingStatus.CONFIRMED), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository)
                .findIdsBySalonIdsFiltered(List.of(salonId), Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged());
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
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), expected, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER),
                List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdsFiltered(List.of(salonId), expected, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("mapped detail page is returned when salon owner lists with a non-empty page")
    void should_returnMappedBookings_when_salonOwnerListsWithNonEmptyPage() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Booking existingBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(existingBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, pageable);

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
        when(bookingRepository.findIdsBySalonIdsFiltered(List.of(salonId), null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(guestBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, pageable);

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
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, pageable);

        assertThat(result.data()).isEmpty();
        verify(bookingRepository, never()).findIdsBySalonIdsFiltered(any(), any(), any(), any(), any());
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
        when(bookingRepository.findIdsBySalonIdsFiltered(salonIds, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(existingBooking));
        when(reviewRepository.findReviewedBookingIds(List.of(bookingId))).thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        verify(salonRepository).findIdsByOwnerIdAndIsActiveTrue(actorId);
        verify(bookingRepository).findIdsBySalonIdsFiltered(salonIds, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("empty page is returned immediately when owner has no active salons")
    void should_returnEmpty_when_ownerHasNoSalons() {
        UUID actorId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of());

        var result =
                bookingService.getMyBookings(actorId, buildAuth(Role.SALON_OWNER), null, null, null, pageable);

        assertThat(result.data()).isEmpty();
        verify(bookingRepository, never()).findIdsBySalonIdsFiltered(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CLIENT with no status filter queries the enriched client projection")
    void should_returnClientBookings_when_clientListsWithoutStatus() {
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findClientBookingDetails(clientId, null, null, null, normalizedUnpaged())).thenReturn(Page.empty());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findClientBookingDetails(clientId, null, null, null, normalizedUnpaged());
        verify(bookingRepository, never()).findIdsByClientId(any(), any());
    }

    @Test
    @DisplayName("CLIENT with a status filter passes the status to the enriched client projection")
    void should_returnClientBookings_when_clientListsWithStatus() {
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findClientBookingDetails(clientId, Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                clientId, buildAuth(Role.CLIENT), List.of(BookingStatus.CONFIRMED), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findClientBookingDetails(clientId, Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("CLIENT with multiple statuses passes the union to the enriched client projection "
            + "(Phase 26.1: ?status=CANCELLED&status=DECLINED for CLIENT too)")
    void should_returnClientBookings_when_clientListsWithMultipleStatuses() {
        Pageable pageable = Pageable.unpaged();
        Set<BookingStatus> expected = EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED);

        when(bookingRepository.findClientBookingDetails(clientId, expected, null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                clientId, buildAuth(Role.CLIENT),
                List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findClientBookingDetails(clientId, expected, null, null, normalizedUnpaged());
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
                "MANICURE", false);
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) surfaces masterProfessionalTitle and locationNote from the projection when set")
    void should_surfaceTitleAndLocationNote_when_clientProjectionRowHasBoth() {
        Pageable pageable = Pageable.unpaged();
        var row = clientProjectionRow("Перукар-стиліст", "3-й поверх, код 1234");
        when(bookingRepository.findClientBookingDetails(clientId, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).masterProfessionalTitle()).isEqualTo("Перукар-стиліст");
        assertThat(result.data().get(0).locationNote()).isEqualTo("3-й поверх, код 1234");
    }

    @Test
    @DisplayName("getMyBookings (CLIENT) surfaces null masterProfessionalTitle/locationNote (not NPE) when the projection row has neither")
    void should_returnNullTitleAndLocationNote_when_clientProjectionRowHasNeither() {
        Pageable pageable = Pageable.unpaged();
        var row = clientProjectionRow(null, null);
        when(bookingRepository.findClientBookingDetails(clientId, null, null, null, normalizedUnpaged()))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).masterProfessionalTitle()).isNull();
        assertThat(result.data().get(0).locationNote()).isNull();
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER receives bookings from the master-scoped repository method")
    void should_returnMasterBookings_when_independentMasterLists() {
        Pageable pageable = Pageable.unpaged();
        User masterUser = buildUser(UUID.randomUUID(), Role.INDEPENDENT_MASTER);
        UUID masterUserId = masterUser.getId();

        when(masterRepository.findByUserId(masterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, null, null, null, normalizedUnpaged())).thenReturn(Page.empty());

        var result = bookingService.getMyBookings(masterUserId, buildAuth(Role.INDEPENDENT_MASTER), null, null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, null, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("SALON_MASTER receives only status-matched bookings from the master-scoped repository method")
    void should_returnMasterBookings_when_salonMasterListsWithStatus() {
        Pageable pageable = Pageable.unpaged();
        User salonMasterUser = buildUser(UUID.randomUUID(), Role.SALON_MASTER);
        UUID salonMasterUserId = salonMasterUser.getId();

        when(masterRepository.findByUserId(salonMasterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(
                salonMasterUserId, buildAuth(Role.SALON_MASTER), List.of(BookingStatus.CONFIRMED), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged());
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
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, expected, null, null, normalizedUnpaged())).thenReturn(Page.empty());

        var result = bookingService.getMyBookings(masterUserId, buildAuth(Role.INDEPENDENT_MASTER),
                List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, expected, null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("repeated/duplicate statuses de-duplicate to a single-element EnumSet before hitting the "
            + "master-scoped repository method (Phase 26.1: ?status=CONFIRMED&status=CONFIRMED)")
    void should_deduplicateStatuses_when_independentMasterRepeatsTheSameStatus() {
        Pageable pageable = Pageable.unpaged();
        User masterUser = buildUser(UUID.randomUUID(), Role.INDEPENDENT_MASTER);
        UUID masterUserId = masterUser.getId();

        when(masterRepository.findByUserId(masterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged()))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(masterUserId, buildAuth(Role.INDEPENDENT_MASTER),
                List.of(BookingStatus.CONFIRMED, BookingStatus.CONFIRMED), null, null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdFiltered(masterId, Set.of(BookingStatus.CONFIRMED), null, null, normalizedUnpaged());
    }

    @Test
    @DisplayName("ForbiddenException is thrown when SALON_ADMIN calls getMyBookings")
    void should_throwForbidden_when_salonAdminListsBookings() {
        UUID salonAdminId = UUID.randomUUID();
        User salonAdmin = buildUser(salonAdminId, Role.SALON_ADMIN);
        Pageable pageable = Pageable.unpaged();

        assertThatThrownBy(() -> bookingService.getMyBookings(salonAdminId, buildAuth(Role.SALON_ADMIN), null, null, null, pageable))
                .isInstanceOf(ForbiddenException.class);
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
                        actorId, buildAuth(Role.CLIENT), null, null, null, maliciousPageable))
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
                        actorId, buildAuth(Role.CLIENT), null, null, null, badPageable))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("foo")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
        verify(salonRepository, never()).findIdsByOwnerIdAndIsActiveTrue(any());
        verify(masterRepository, never()).findByUserId(any());
    }

    // ── getMyBookings — sort cardinality bound (Phase 26.3 audit, backend-perf F4) ──────────────
    //
    // MAX_SORT_ORDERS caps normalizeBookingSort's effective Sort at 3 entries — each distinct
    // (property, direction) sequence compiles to a textually distinct ORDER BY, so an unbounded
    // sort list inflates plan-cache entries. The two tests below isolate that count bound from the
    // whitelist check above: every property used here (startsAt, priceAtBooking, createdAt) is
    // individually whitelisted, so a rejection can ONLY be attributed to cardinality, never to an
    // unrecognised property name.

    @Test
    @DisplayName("BusinessException(400) is thrown, and NEITHER repository is ever touched, when sort "
            + "carries 4 orders — all four individually whitelisted properties (startsAt, "
            + "priceAtBooking, createdAt, startsAt again) so the rejection is attributable ONLY to "
            + "exceeding MAX_SORT_ORDERS=3, never to the property whitelist")
    void should_rejectSort_when_fourSortOrdersExceedTheCountBound() {
        UUID actorId = UUID.randomUUID();
        Sort fourOrders = Sort.by("startsAt")
                .and(Sort.by("priceAtBooking"))
                .and(Sort.by("createdAt"))
                .and(Sort.by(Sort.Direction.DESC, "startsAt"));
        Pageable tooManyOrdersPageable = PageRequest.of(0, 20, fourOrders);

        assertThatThrownBy(() -> bookingService.getMyBookings(
                        actorId, buildAuth(Role.CLIENT), null, null, null, tooManyOrdersPageable))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many sort properties")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(bookingRepository);
        verify(salonRepository, never()).findIdsByOwnerIdAndIsActiveTrue(any());
        verify(masterRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("exactly 3 sort orders are accepted — the CLIENT projection query is reached with the "
            + "normalized (3 whitelisted orders + mandatory id tiebreaker) sort, proving the bound is "
            + "\">3 rejects\", not \">=3 rejects\" (no off-by-one against MAX_SORT_ORDERS=3)")
    void should_acceptSort_when_exactlyThreeSortOrdersAtTheCountBound() {
        Sort threeOrders = Sort.by("startsAt")
                .and(Sort.by("priceAtBooking"))
                .and(Sort.by("createdAt"));
        Pageable atBoundPageable = Pageable.unpaged(threeOrders);
        Sort expectedNormalizedSort = threeOrders.and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable expectedNormalizedPageable = Pageable.unpaged(expectedNormalizedSort);

        when(bookingRepository.findClientBookingDetails(clientId, null, null, null, expectedNormalizedPageable))
                .thenReturn(Page.empty());

        var result = bookingService.getMyBookings(clientId, buildAuth(Role.CLIENT), null, null, null, atBoundPageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findClientBookingDetails(clientId, null, null, null, expectedNormalizedPageable);
    }

    // ── getBooking ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BookingDetailResponse is returned when an authorized actor requests their own booking")
    void should_returnBooking_when_getBookingCalledByOwner() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(bookingId);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.canReview()).isFalse();
        verify(authz).enforceCanViewBooking(clientId, booking);
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
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(providerActorId, bookingId);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(bookingId);
        assertThat(result.clientId()).isNull();
        assertThat(result.clientFirstName()).isEqualTo("Оксана");
        assertThat(result.clientLastName()).isEqualTo("Мельник");
        verify(authz).enforceCanViewBooking(providerActorId, guestBooking);
    }

    @Test
    @DisplayName("canReview is false for a COMPLETED guest (LINK) booking with no existing review — no account exists to leave one")
    void should_returnCanReviewFalse_when_completedGuestBookingHasNoClient() {
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        setField(guestBooking, "client", null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(UUID.randomUUID(), bookingId);

        assertThat(result.canReview()).isFalse();
    }

    // ── getBooking — canReview truth table (Phase 19.3) ──────────────────────────
    //
    // canReview = (status == COMPLETED) && no existing Review for the booking.
    // Each row of the truth table stubs reviewRepository.existsByBookingId and the
    // status, then asserts the single observable predicate on the response.

    private BookingDetailResponse getBookingWith(BookingStatus status, boolean reviewExists) {
        Booking booking = buildBooking(bookingId, client, master, msa, status);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(reviewExists);
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());
        return bookingService.getBooking(clientId, bookingId);
    }

    @Test
    @DisplayName("canReview is false for a DECLINED booking (not COMPLETED)")
    void should_returnCanReviewFalse_when_bookingDeclined() {
        assertThat(getBookingWith(BookingStatus.DECLINED, false).canReview()).isFalse();
        // A DECLINED booking is never review-eligible, so the existence check is irrelevant
        // to the outcome — but the predicate must still short-circuit to false on status.
    }

    @Test
    @DisplayName("canReview is false for a CONFIRMED booking (not COMPLETED)")
    void should_returnCanReviewFalse_when_bookingConfirmed() {
        assertThat(getBookingWith(BookingStatus.CONFIRMED, false).canReview()).isFalse();
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
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
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
    @DisplayName("getBooking surfaces the salon name and salon-primary address/labels for a salon-employed master")
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
        setField(enriched, "salon", salon);
        MasterServiceAssignment enrichedMsa = buildMsa(masterServiceId, enriched, serviceDef, null, null);
        Booking booking = buildBooking(bookingId, client, enriched, enrichedMsa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
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
    }

    @Test
    @DisplayName("locationNote and masterProfessionalTitle are null (not NPE) on a guest (LINK) booking whose master has set neither")
    void should_returnNullLocationNoteAndTitle_when_guestBookingAndMasterHasNeither() {
        Booking guestBooking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        setField(guestBooking, "client", null);
        setField(guestBooking, "guestName", "Оксана");
        setField(guestBooking, "guestSurname", "Мельник");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(guestBooking));
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
        when(discoveryLocationResolver.resolveLabels(any(), any())).thenReturn(emptyLabels());

        BookingDetailResponse result = bookingService.getBooking(UUID.randomUUID(), bookingId);

        assertThat(result.clientId()).isNull();
        assertThat(result.masterProfessionalTitle()).isNull();
        assertThat(result.locationNote()).isNull();
    }
}
