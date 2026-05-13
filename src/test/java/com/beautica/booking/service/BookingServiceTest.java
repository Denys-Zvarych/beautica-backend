package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import org.springframework.data.domain.PageImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private AuthorizationService authz;
    @Mock
    private NotificationOutboxService outboxService;
    @Mock
    private SlotCalculationService slotCalculationService;
    @Mock
    private CacheManager cacheManager;

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
                authz,
                outboxService,
                slotCalculationService,
                clock,
                cacheManager
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
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

        BookingResponse result = bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(result).isNotNull();
        verify(slotCalculationService).evictAvailableSlots(
                eq(masterId),
                any(LocalDate.class),
                eq(masterServiceId)
        );
    }

    @Test
    @DisplayName("existing booking is returned without saving when idempotency key already exists")
    void should_returnExistingBooking_when_idempotencyKeyMatches() {
        String key = "unique-key-123";
        Booking existing = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
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
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
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
        Booking saved = buildBooking(bookingId, client, master, msaWithOverrides, BookingStatus.PENDING);
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
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
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
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.saveAndFlush(any())).thenReturn(saved);

        bookingService.createBooking(clientId, null, validRequest());

        verify(outboxService).enqueueNewBooking(bookingId);
    }

    // ── confirmBooking ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking moves to CONFIRMED and notification is enqueued when authorized actor confirms")
    void should_confirmBooking_when_authorizedActorConfirms() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.confirmBooking(actorId, bookingId);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(outboxService).enqueueStatusChanged(bookingId);
    }

    @Test
    @DisplayName("ForbiddenException is thrown when an unauthorized actor attempts to confirm a booking")
    void should_throwForbidden_when_unauthorizedActorConfirms() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageBooking(actorId, booking);

        assertThatThrownBy(() -> bookingService.confirmBooking(actorId, bookingId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("400 is thrown when confirm is called on a booking that is not in PENDING status")
    void should_throw400_when_confirmingNonPendingBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.confirmBooking(actorId, bookingId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── declineBooking ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking moves to DECLINED and slot cache is evicted when authorized actor declines")
    void should_declineBooking_when_authorizedActorDeclines() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
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
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageBooking(actorId, booking);

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
    }

    @Test
    @DisplayName("ForbiddenException is thrown when an unauthorized actor attempts to complete a booking")
    void should_throwForbidden_when_unauthorizedActorCompletesBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageBooking(actorId, booking);

        assertThatThrownBy(() -> bookingService.completeBooking(actorId, bookingId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("400 is thrown when complete is called on a booking that is still PENDING")
    void should_throw400_when_completingPendingBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.completeBooking(actorId, bookingId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
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
                .when(authz).enforceCanManageBooking(actorId, booking);

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

    // ── cancelBooking ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("PENDING booking moves to CANCELLED and slot cache is evicted when client cancels")
    void should_cancelBooking_when_clientCancelsPendingBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
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
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
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
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
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

    // ── listBookings ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("filtered bookings page is returned when salon owner queries with a specific status")
    void should_returnFilteredBookings_when_salonOwnerListsWithStatus() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User salonOwner = new User(
                "owner@example.com", "hash", Role.SALON_OWNER, "Owner", "User", "+380501111113", salonId);
        setField(salonOwner, "id", actorId);
        Pageable pageable = Pageable.unpaged();

        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));
        when(bookingRepository.findIdsBySalonIdAndOwnerIdAndStatus(salonId, actorId, BookingStatus.PENDING, pageable))
                .thenReturn(Page.empty());

        Page<BookingResponse> result =
                bookingService.listBookings(actorId, buildAuth(Role.SALON_OWNER), BookingStatus.PENDING, pageable);

        assertThat(result.getTotalElements()).isZero();
        verify(bookingRepository).findIdsBySalonIdAndOwnerIdAndStatus(salonId, actorId, BookingStatus.PENDING, pageable);
        verify(bookingRepository, never()).findIdsBySalonIdAndOwnerId(any(), any(), any());
    }

    @Test
    @DisplayName("all bookings page is returned when salon owner queries without a status filter")
    void should_returnAllBookings_when_salonOwnerListsWithoutStatus() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User salonOwner = new User(
                "owner2@example.com", "hash", Role.SALON_OWNER, "Owner", "Two", "+380501111114", salonId);
        setField(salonOwner, "id", actorId);
        Pageable pageable = Pageable.unpaged();

        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));
        when(bookingRepository.findIdsBySalonIdAndOwnerId(salonId, actorId, pageable))
                .thenReturn(Page.empty());

        Page<BookingResponse> result =
                bookingService.listBookings(actorId, buildAuth(Role.SALON_OWNER), null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsBySalonIdAndOwnerId(salonId, actorId, pageable);
        verify(bookingRepository, never()).findIdsBySalonIdAndOwnerIdAndStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mapped BookingResponse page is returned when salon owner lists with a non-empty page")
    void should_returnMappedBookings_when_salonOwnerListsWithNonEmptyPage() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User salonOwner = new User(
                "owner3@example.com", "hash", Role.SALON_OWNER, "Owner", "Three", "+380501111115", salonId);
        setField(salonOwner, "id", actorId);
        Pageable pageable = Pageable.unpaged();
        Booking existingBooking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);

        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));
        when(bookingRepository.findIdsBySalonIdAndOwnerId(salonId, actorId, pageable))
                .thenReturn(new PageImpl<>(List.of(bookingId)));
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId)))
                .thenReturn(List.of(existingBooking));

        Page<BookingResponse> result =
                bookingService.listBookings(actorId, buildAuth(Role.SALON_OWNER), null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("BusinessException is thrown when a salon owner's account has no salon ID linked")
    void should_throwBusinessException_when_salonOwnerHasNoSalonId() {
        UUID actorId = UUID.randomUUID();
        User salonOwner = buildUser(actorId, Role.SALON_OWNER);
        // salonId is null — buildUser does not set it
        Pageable pageable = Pageable.unpaged();

        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.listBookings(actorId, buildAuth(Role.SALON_OWNER), null, pageable))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("CLIENT with no status filter receives bookings from the client-scoped repository method")
    void should_returnClientBookings_when_clientListsWithoutStatus() {
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findIdsByClientId(clientId, pageable)).thenReturn(Page.empty());

        Page<BookingResponse> result = bookingService.listBookings(clientId, buildAuth(Role.CLIENT), null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByClientId(clientId, pageable);
        verify(bookingRepository, never()).findIdsByClientIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("CLIENT with PENDING status filter receives bookings from the status-scoped repository method")
    void should_returnClientBookings_when_clientListsWithStatus() {
        Pageable pageable = Pageable.unpaged();

        when(bookingRepository.findIdsByClientIdAndStatus(clientId, BookingStatus.PENDING, pageable))
                .thenReturn(Page.empty());

        Page<BookingResponse> result = bookingService.listBookings(clientId, buildAuth(Role.CLIENT), BookingStatus.PENDING, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByClientIdAndStatus(clientId, BookingStatus.PENDING, pageable);
        verify(bookingRepository, never()).findIdsByClientId(any(), any());
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER receives bookings from the master-scoped repository method")
    void should_returnMasterBookings_when_independentMasterLists() {
        Pageable pageable = Pageable.unpaged();
        User masterUser = buildUser(UUID.randomUUID(), Role.INDEPENDENT_MASTER);
        UUID masterUserId = masterUser.getId();

        when(masterRepository.findByUserId(masterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterId(masterId, pageable)).thenReturn(Page.empty());

        Page<BookingResponse> result = bookingService.listBookings(masterUserId, buildAuth(Role.INDEPENDENT_MASTER), null, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterId(masterId, pageable);
    }

    @Test
    @DisplayName("SALON_MASTER receives only status-matched bookings from the master-scoped repository method")
    void should_returnMasterBookings_when_salonMasterListsWithStatus() {
        Pageable pageable = Pageable.unpaged();
        User salonMasterUser = buildUser(UUID.randomUUID(), Role.SALON_MASTER);
        UUID salonMasterUserId = salonMasterUser.getId();

        when(masterRepository.findByUserId(salonMasterUserId)).thenReturn(Optional.of(master));
        when(bookingRepository.findIdsByMasterIdAndStatus(masterId, BookingStatus.CONFIRMED, pageable))
                .thenReturn(Page.empty());

        Page<BookingResponse> result = bookingService.listBookings(
                salonMasterUserId, buildAuth(Role.SALON_MASTER), BookingStatus.CONFIRMED, pageable);

        assertThat(result).isNotNull();
        verify(bookingRepository).findIdsByMasterIdAndStatus(masterId, BookingStatus.CONFIRMED, pageable);
        verify(bookingRepository, never()).findIdsByMasterId(any(), any());
    }

    @Test
    @DisplayName("ForbiddenException is thrown when SALON_ADMIN calls listBookings")
    void should_throwForbidden_when_salonAdminListsBookings() {
        UUID salonAdminId = UUID.randomUUID();
        User salonAdmin = buildUser(salonAdminId, Role.SALON_ADMIN);
        Pageable pageable = Pageable.unpaged();

        assertThatThrownBy(() -> bookingService.listBookings(salonAdminId, buildAuth(Role.SALON_ADMIN), null, pageable))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── getBooking ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BookingDetailResponse is returned when an authorized actor requests their own booking")
    void should_returnBooking_when_getBookingCalledByOwner() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        BookingDetailResponse result = bookingService.getBooking(clientId, bookingId);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(bookingId);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
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
}
