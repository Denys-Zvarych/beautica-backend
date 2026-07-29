package com.beautica.review.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.review.dto.ClientReviewResponse;
import com.beautica.review.dto.CreateClientReviewRequest;
import com.beautica.review.entity.ClientReview;
import com.beautica.review.event.ClientReviewCreatedEvent;
import com.beautica.review.repository.ClientReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Provider-authored review of the CLIENT after a completed booking (Phase 27.5 — REVERSES the
 * previously deferred/out-of-scope status of master&rarr;client reviews). Mirrors {@code
 * ReviewService#createReview}'s validation order and after-commit rating-recalculation pattern,
 * swapping the authority direction: here the actor is the PROVIDER (with authority over the
 * booking) and the subject is the CLIENT.
 */
@Service
@RequiredArgsConstructor
public class ClientReviewService {

    private final ClientReviewRepository clientReviewRepository;
    private final BookingRepository bookingRepository;
    private final AuthorizationService authz;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ClientReviewResponse create(UUID actorUserId, CreateClientReviewRequest request) {
        // 1. 404 if the booking does not exist.
        Booking booking = bookingRepository.findByIdWithFullGraph(request.bookingId())
                .orElseThrow(() -> new NotFoundException("Booking not found"));

        // 2. 403 defense-in-depth re-check even though @PreAuthorize's canReviewClient SpEL
        // already gated this — mirrors declineBooking/completeBooking/rescheduleBooking's
        // enforce* re-check after their own load, the established precedent in this feature
        // family (unlike ReviewService.createReview, whose CLIENT-side check is a trivial id
        // equality inlined at the call site — the provider-authority predicate here is the same
        // non-trivial shape decline/complete/reschedule already share via enforceCan*).
        authz.enforceCanReviewClient(actorUserId, booking);

        // 3. 400 if the booking is not COMPLETED.
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "A client can only be reviewed for a completed booking");
        }

        // 4. 400 if the booking has no client (guest/LINK booking, V89 chk_bookings_guest_fields)
        // — there is no account to review. Not a 403: the actor IS an authorized provider over
        // this booking (step 2 already confirmed that); the booking is simply structurally
        // ineligible, the same class of rejection as the status check above.
        if (booking.getClient() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Cannot review a guest booking — no client account exists");
        }

        // 5. 409 on a duplicate review for this booking.
        if (clientReviewRepository.existsByBookingId(booking.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "A client review already exists for this booking");
        }

        ClientReview review = ClientReview.builder()
                .booking(booking)
                .subjectClient(booking.getClient())
                .authorMaster(booking.getMaster())
                .salon(booking.getSalon())
                .rating(request.rating().shortValue())
                .comment(request.comment())
                .build();

        ClientReview saved;
        try {
            saved = clientReviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "A client review already exists for this booking");
        }

        // 6. Rating recalculation happens AFTER commit, in its own transaction — never inline
        // here (Anti-Bug §F.2/§F.8). See ClientReviewEventListener.
        eventPublisher.publishEvent(new ClientReviewCreatedEvent(booking.getClient().getId()));

        // 7. Locked product decision: no notification/outbox row is enqueued for this event — a
        // client is never told a master reviewed them.
        return ClientReviewResponse.from(saved);
    }
}
