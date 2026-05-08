package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final int MIN_MINUTES_AHEAD = 15;
    private static final int MAX_DAYS_AHEAD = 180;

    private final BookingRepository bookingRepository;
    private final MasterRepository masterRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authz;
    private final NotificationOutboxService outboxService;
    private final Clock clock;

    @Transactional
    public BookingResponse createBooking(UUID clientId, String idempotencyKey, CreateBookingRequest request) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Fix M5: use the partial-index-aligned query to avoid full table scan
            return bookingRepository.findActiveByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                    .map(BookingResponse::from)
                    .orElseGet(() -> doCreateBooking(clientId, idempotencyKey, request));
        }
        return doCreateBooking(clientId, idempotencyKey, request);
    }

    @Transactional(readOnly = true)
    public BookingDetailResponse getBooking(UUID actorUserId, UUID bookingId) {
        // Use full-graph fetch to avoid lazy-load SELECTs when building BookingDetailResponse
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        authz.enforceCanViewBooking(actorUserId, booking);
        return BookingDetailResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> listBookings(UUID actorUserId, BookingStatus status, Pageable pageable) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        // Fix H1: use full-graph queries to prevent N+1 lazy loads on BookingResponse mapping
        Page<Booking> page = switch (actor.getRole()) {
            case CLIENT -> status == null
                    ? bookingRepository.findByClientIdWithGraph(actorUserId, pageable)
                    : bookingRepository.findByClientIdAndStatusWithGraph(actorUserId, status, pageable);
            case SALON_MASTER, INDEPENDENT_MASTER -> {
                Master master = masterRepository.findByUserId(actorUserId)
                        .orElseThrow(() -> new NotFoundException("Master profile not found"));
                yield status == null
                        ? bookingRepository.findByMasterIdWithGraph(master.getId(), pageable)
                        : bookingRepository.findByMasterIdAndStatusWithGraph(master.getId(), status, pageable);
            }
            case SALON_OWNER -> {
                UUID salonId = actor.getSalonId();
                if (salonId == null) {
                    throw new BusinessException("Salon owner has no associated salon");
                }
                yield bookingRepository.findBySalonIdAndOwnerId(salonId, actorUserId, pageable);
            }
            default -> throw new ForbiddenException("Access denied");
        };

        return page.map(BookingResponse::from);
    }

    @Transactional
    public BookingResponse confirmBooking(UUID actorUserId, UUID bookingId) {
        Booking booking = loadBookingOrThrow(bookingId);
        authz.enforceCanManageBooking(actorUserId, booking);
        assertTransition(booking, BookingStatus.PENDING, BookingStatus.CONFIRMED);
        booking.setStatus(BookingStatus.CONFIRMED);
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueBookingStatusChange(saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse declineBooking(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        // Fix M4: require a reason, consistent with notCompleteBooking
        if (req.cancellationReason() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Cancellation reason required for declining a booking");
        }
        Booking booking = loadBookingOrThrow(bookingId);
        authz.enforceCanManageBooking(actorUserId, booking);
        assertTransition(booking, BookingStatus.PENDING, BookingStatus.DECLINED);
        booking.setStatus(BookingStatus.DECLINED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(req.comment());
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueBookingStatusChange(saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse completeBooking(UUID actorUserId, UUID bookingId) {
        Booking booking = loadBookingOrThrow(bookingId);
        authz.enforceCanManageBooking(actorUserId, booking);
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
        booking.setStatus(BookingStatus.COMPLETED);
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueBookingStatusChange(saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse notCompleteBooking(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        Booking booking = loadBookingOrThrow(bookingId);
        authz.enforceCanManageBooking(actorUserId, booking);
        if (req.cancellationReason() == null) {
            throw new BusinessException("Cancellation reason required");
        }
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.NOT_COMPLETED);
        booking.setStatus(BookingStatus.NOT_COMPLETED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(req.comment());
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueBookingStatusChange(saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse cancelBooking(UUID clientUserId, UUID bookingId, StatusUpdateRequest req) {
        // Fix H1 (SEC): role guard before booking load prevents UUID-collision bypass
        User actor = userRepository.findById(clientUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (actor.getRole() != Role.CLIENT) {
            throw new ForbiddenException("Only clients can cancel bookings");
        }
        Booking booking = loadBookingOrThrow(bookingId);
        if (!booking.getClient().getId().equals(clientUserId)) {
            throw new ForbiddenException("Access denied");
        }
        BookingStatus current = booking.getStatus();
        if (current != BookingStatus.PENDING && current != BookingStatus.CONFIRMED) {
            throw new BusinessException("Cannot cancel a booking in status %s".formatted(current));
        }
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancellationReason(req.cancellationReason());
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueBookingStatusChange(saved.getId());
        return BookingResponse.from(saved);
    }

    private BookingResponse doCreateBooking(UUID clientId, String idempotencyKey, CreateBookingRequest request) {
        Master master = masterRepository.findByIdWithSalonAndOwner(request.masterId())
                .filter(Master::isActive)
                .orElseThrow(() -> new NotFoundException("Master not found or inactive"));

        MasterServiceAssignment msa = masterServiceRepository.findById(request.masterServiceId())
                .orElseThrow(() -> new NotFoundException("Master service not found"));

        if (!msa.getMaster().getId().equals(request.masterId())) {
            throw new BusinessException("Service does not belong to the requested master");
        }

        OffsetDateTime startsAt = request.startsAt().toOffsetDateTime();
        validateStartsAt(startsAt);

        BigDecimal effectivePrice = msa.getPriceOverride() != null
                ? msa.getPriceOverride()
                : msa.getServiceDefinition().getBasePrice();
        int effectiveDuration = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes()
                : msa.getServiceDefinition().getBaseDurationMinutes();
        int bufferMinutes = msa.getServiceDefinition().getBufferMinutesAfter();

        OffsetDateTime endsAt = startsAt.plusMinutes((long) effectiveDuration + bufferMinutes);

        // Fix H3: load and validate the client BEFORE acquiring the advisory lock to
        // minimise the lock hold window — no DB round-trip inside the critical section.
        User client = userRepository.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Client not found"));

        bookingRepository.acquireAdvisoryLock(master.getId());

        if (bookingRepository.existsOverlap(master.getId(), startsAt, endsAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        Booking booking = Booking.builder()
                .client(client)
                .master(master)
                .masterService(msa)
                .salon(master.getSalon())
                .status(BookingStatus.PENDING)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(effectivePrice)
                .durationMinutesAtBooking(effectiveDuration)
                .bufferMinutesAtBooking(bufferMinutes)
                .idempotencyKey(idempotencyKey)
                .build();

        Booking saved;
        try {
            saved = bookingRepository.save(booking);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        outboxService.enqueueNewBooking(saved.getId());
        return BookingResponse.from(saved);
    }

    private void validateStartsAt(OffsetDateTime startsAt) {
        // Fix M4 (PERF): compute Duration once — two separate calls created redundant objects
        Duration gap = Duration.between(clock.instant(), startsAt.toInstant());
        if (gap.toMinutes() < MIN_MINUTES_AHEAD) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Booking must start at least 15 minutes from now");
        }
        if (gap.toDays() > MAX_DAYS_AHEAD) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Booking cannot be more than 180 days in the future");
        }
    }

    private Booking loadBookingOrThrow(UUID bookingId) {
        // Fix M6: use full-graph fetch so mutation responses do not trigger
        // additional SELECTs for masterService and serviceDefinition
        return bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
    }

    private void assertTransition(Booking booking, BookingStatus expected, BookingStatus target) {
        if (booking.getStatus() != expected) {
            throw new BusinessException(
                    "Cannot transition from %s to %s".formatted(booking.getStatus(), target));
        }
    }
}
