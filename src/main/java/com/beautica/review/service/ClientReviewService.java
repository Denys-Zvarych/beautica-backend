package com.beautica.review.service;

import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
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

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Provider-authored review of the CLIENT after a completed booking (Phase 27.5 — REVERSES the
 * previously deferred/out-of-scope status of master&rarr;client reviews). Mirrors {@code
 * ReviewService#createReview}'s validation order and after-commit rating-recalculation pattern,
 * swapping the authority direction: here the actor is the PROVIDER (with authority over the
 * booking) and the subject is the CLIENT.
 *
 * <p>The STATUS+TIME eligibility gate mirrors {@code ReviewService#createReview} exactly — the
 * client-review path had the identical "Past-tab-by-elapsed-time but never COMPLETED" bug (see
 * {@link BookingClosureRule#isReviewEligible}'s javadoc), fixed here by reusing the same
 * canonical predicate rather than re-deriving {@code status == COMPLETED}.
 */
@Service
@RequiredArgsConstructor
public class ClientReviewService {

    private final ClientReviewRepository clientReviewRepository;
    private final BookingRepository bookingRepository;
    private final AuthorizationService authz;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Absolute-instant "now" for {@link BookingClosureRule#isReviewEligible} — {@link
     * Clock#instant()} as a fixed-offset {@link OffsetDateTime}, the SAME idiom {@code
     * BookingService#resolveNow} / {@code ReviewService#resolveNow} / {@code
     * AppointmentService#resolveNow} use for the identical purpose (Anti-Bug §G: never {@code
     * Instant.now()} / {@code OffsetDateTime.now()} directly, and never a second, divergent clock
     * idiom for the same rule).
     */
    private OffsetDateTime resolveNow() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

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

        // 3. 400 if the booking is neither COMPLETED nor an elapsed-but-unclosed CONFIRMED
        // booking — locked product decision, same as ReviewService#createReview: a booking that
        // aged into "Past" by elapsed time must be reviewable even when the provider never
        // closed it out (see BookingClosureRule#isReviewEligible's javadoc). NOT_COMPLETED /
        // CANCELLED / DECLINED stay unreviewable regardless of endsAt.
        if (!BookingClosureRule.isReviewEligible(booking.getStatus(), booking.getEndsAt(), resolveNow())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "This booking is not yet eligible for a review");
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
