package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
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
import com.beautica.notification.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                clock
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
    @DisplayName("should_createBooking_when_slotAvailableAndNoConflict")
    void should_createBooking_when_slotAvailableAndNoConflict() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.save(any())).thenReturn(saved);

        BookingResponse result = bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should_returnExistingBooking_when_idempotencyKeyMatches")
    void should_returnExistingBooking_when_idempotencyKeyMatches() {
        String key = "unique-key-123";
        Booking existing = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.findActiveByClientIdAndIdempotencyKey(clientId, key))
                .thenReturn(Optional.of(existing));

        BookingResponse result = bookingService.createBooking(clientId, key, validRequest());

        verify(bookingRepository, never()).save(any());
        assertThat(result.id()).isEqualTo(bookingId);
    }

    @Test
    @DisplayName("should_throw409_when_slotOverlapsExistingBooking")
    void should_throw409_when_slotOverlapsExistingBooking() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.of(msa));
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("should_throw404_when_masterNotFound")
    void should_throw404_when_masterNotFound() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_throw404_when_masterServiceNotFound")
    void should_throw404_when_masterServiceNotFound() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_throw400_when_startsAtInThePast")
    void should_throw400_when_startsAtInThePast() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest pastRequest = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).minusMinutes(10),
                null
        );

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, pastRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("should_throw400_when_startsAtMoreThan180DaysAhead")
    void should_throw400_when_startsAtMoreThan180DaysAhead() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.of(msa));

        CreateBookingRequest farFutureRequest = new CreateBookingRequest(
                masterId,
                masterServiceId,
                ZonedDateTime.now(clock).plusDays(181),
                null
        );

        assertThatThrownBy(() -> bookingService.createBooking(clientId, null, farFutureRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("should_snapshotPriceAndDuration_when_masterServiceHasOverrides")
    void should_snapshotPriceAndDuration_when_masterServiceHasOverrides() {
        MasterServiceAssignment msaWithOverrides = buildMsa(
                masterServiceId, master, serviceDef, new BigDecimal("250.00"), 45);
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.of(msaWithOverrides));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msaWithOverrides, BookingStatus.PENDING);
        setField(saved, "priceAtBooking", new BigDecimal("250.00"));
        setField(saved, "durationMinutesAtBooking", 45);
        when(bookingRepository.save(any())).thenReturn(saved);

        bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getPriceAtBooking()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(captor.getValue().getDurationMinutesAtBooking()).isEqualTo(45);
    }

    @Test
    @DisplayName("should_fallBackToBaseValues_when_noOverrides")
    void should_fallBackToBaseValues_when_noOverrides() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.save(any())).thenReturn(saved);

        bookingService.createBooking(clientId, null, validRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getPriceAtBooking()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(captor.getValue().getDurationMinutesAtBooking()).isEqualTo(60);
    }

    @Test
    @DisplayName("should_enqueueNewBookingNotification_when_bookingCreated")
    void should_enqueueNewBookingNotification_when_bookingCreated() {
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.findById(masterServiceId)).thenReturn(Optional.of(msa));
        when(bookingRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        Booking saved = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.save(any())).thenReturn(saved);

        bookingService.createBooking(clientId, null, validRequest());

        verify(outboxService).enqueueNewBooking(bookingId);
    }

    // ── confirmBooking ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_confirmBooking_when_authorizedActorConfirms")
    void should_confirmBooking_when_authorizedActorConfirms() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.confirmBooking(actorId, bookingId);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(outboxService).enqueueBookingStatusChange(bookingId);
    }

    @Test
    @DisplayName("should_throwForbidden_when_unauthorizedActorConfirms")
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
    @DisplayName("should_throw400_when_confirmingNonPendingBooking")
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
    @DisplayName("should_declineBooking_when_authorizedActorDeclines")
    void should_declineBooking_when_authorizedActorDeclines() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, "Unavailable");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.declineBooking(actorId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.DECLINED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.PROVIDER_UNAVAILABLE);
        verify(outboxService).enqueueBookingStatusChange(bookingId);
    }

    @Test
    @DisplayName("should_throw400_when_declineCalledWithoutCancellationReason")
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
    @DisplayName("should_completeBooking_when_confirmedBookingCompleted")
    void should_completeBooking_when_confirmedBookingCompleted() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.completeBooking(actorId, bookingId);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        verify(outboxService).enqueueBookingStatusChange(bookingId);
    }

    @Test
    @DisplayName("should_throw400_when_completingPendingBooking")
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
    @DisplayName("should_markNotCompleted_when_masterRecordsNoShow")
    void should_markNotCompleted_when_masterRecordsNoShow() {
        UUID actorId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, "No show");
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.notCompleteBooking(actorId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.NOT_COMPLETED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.CLIENT_NO_SHOW);
        verify(outboxService).enqueueBookingStatusChange(bookingId);
    }

    @Test
    @DisplayName("should_throw400_when_notCompleteCalledWithoutCancellationReason")
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
    @DisplayName("should_cancelBooking_when_clientCancelsPendingBooking")
    void should_cancelBooking_when_clientCancelsPendingBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.PENDING);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outboxService).enqueueBookingStatusChange(bookingId);
    }

    @Test
    @DisplayName("should_cancelBooking_when_clientCancelsConfirmedBooking")
    void should_cancelBooking_when_clientCancelsConfirmedBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        bookingService.cancelBooking(clientId, bookingId, req);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outboxService).enqueueBookingStatusChange(bookingId);
    }

    @Test
    @DisplayName("should_throwForbidden_when_nonClientRoleCallsCancelBooking")
    void should_throwForbidden_when_nonClientRoleCallsCancelBooking() {
        // Fix SEC H1: a user whose UUID coincidentally matches the booking's client UUID
        // but who holds SALON_OWNER role must be rejected by the role guard, not the
        // ownership check — the role check fires first before any booking is loaded.
        UUID salonOwnerUserId = UUID.randomUUID();
        User salonOwner = buildUser(salonOwnerUserId, Role.SALON_OWNER);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(userRepository.findById(salonOwnerUserId)).thenReturn(Optional.of(salonOwner));

        assertThatThrownBy(() -> bookingService.cancelBooking(salonOwnerUserId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);

        verify(bookingRepository, never()).findByIdWithFullGraph(any());
    }

    @Test
    @DisplayName("should_throwForbidden_when_nonClientCancelsBooking")
    void should_throwForbidden_when_nonClientCancelsBooking() {
        UUID otherUserId = UUID.randomUUID();
        User otherUser = buildUser(otherUserId, Role.SALON_MASTER);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(userRepository.findById(otherUserId)).thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() -> bookingService.cancelBooking(otherUserId, bookingId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should_throw400_when_clientCancelsCompletedBooking")
    void should_throw400_when_clientCancelsCompletedBooking() {
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.COMPLETED);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.CLIENT_CANCELLED, null);
        when(userRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(clientId, bookingId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── getBooking ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throw403_when_clientBReadsClientABooking")
    void should_throw403_when_clientBReadsClientABooking() {
        UUID clientBId = UUID.randomUUID();
        Booking booking = buildBooking(bookingId, client, master, msa, BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanViewBooking(clientBId, booking);

        assertThatThrownBy(() -> bookingService.getBooking(clientBId, bookingId))
                .isInstanceOf(ForbiddenException.class);
    }
}
