package com.beautica.review.service;

import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
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
 *
 * <p><b>The STATUS eligibility gate does NOT mirror {@code ReviewService#createReview}</b> — this
 * was the earlier (incorrect) design and has since been narrowed on purpose. Unlike the
 * client&rarr;provider direction, this write path requires {@code status == COMPLETED} strictly,
 * via {@link BookingClosureRule#isProviderReviewEligible}, never the wider {@link
 * BookingClosureRule#isReviewEligible} an elapsed-but-unclosed {@code CONFIRMED} booking would
 * satisfy. Rationale: closing the booking is the PROVIDER's own action; letting them rate the
 * client before performing it lets them submit a rating for a visit they have not yet attested
 * happened. See {@link BookingClosureRule#isProviderReviewEligible}'s javadoc for the full
 * argument — do not re-widen this to reuse {@code isReviewEligible}.
 */
@Service
@RequiredArgsConstructor
public class ClientReviewService {

    private final ClientReviewRepository clientReviewRepository;
    private final BookingRepository bookingRepository;
    private final AuthorizationService authz;
    private final ApplicationEventPublisher eventPublisher;
    private final ClientRatingEligibility eligibility;

    static final String GUEST_DISABLED_MESSAGE = "Cannot review a guest booking — no client account exists";
    static final String NO_IDENTITY_MESSAGE = "Cannot review this booking — it has no client identity";
    static final String DUPLICATE_MESSAGE = "A client review already exists for this booking";

    // No Clock field here — unlike ReviewService#createReview, this write path's eligibility gate
    // (BookingClosureRule#isProviderReviewEligible) is a pure STATUS check with no TIME half, so
    // there is no "now" to resolve. Do not re-add a Clock dependency here unless the gate is
    // deliberately widened again — see the class javadoc for why it should not be.

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

        // 3. 400 if the booking is not COMPLETED. Unlike ReviewService#createReview (client-side),
        // this direction does NOT extend eligibility to an elapsed-but-unclosed CONFIRMED booking
        // — locked product decision: the PROVIDER controls their own closing action (PATCH
        // .../complete), so a rating must follow it, never precede or substitute for it. See
        // BookingClosureRule#isProviderReviewEligible's javadoc for the full rationale.
        // CONFIRMED (elapsed or not) / NOT_COMPLETED / CANCELLED / DECLINED all stay unreviewable.
        if (!BookingClosureRule.isProviderReviewEligible(booking.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "This booking must be marked completed before you can review the client");
        }

        // 4. 400 unless the booking has a rateable client identity (B546, REV-D04 / REV-16 / REV-41). Not a 403: the
        // actor IS an authorized provider (step 2); the booking is structurally ineligible, like the status check.
        boolean guestSubject = booking.getClient() == null;
        if (guestSubject) {
            requireRateableGuest(booking);
        }

        // 5. 409 on a duplicate review for this booking — one salon-side rating per booking, first writer wins
        // (B547, REV-42); the unique constraint below is the race backstop.
        if (clientReviewRepository.existsByBookingId(booking.getId())) {
            throw new ConflictException(DUPLICATE_MESSAGE);
        }

        ClientReview review = guestSubject
                ? ClientReview.forGuest(booking, booking.getGuestPhone(), booking.getMaster(), booking.getSalon(),
                        request.rating().shortValue(), request.comment())
                : ClientReview.builder()
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
            // Only the one-rating-per-booking race (REV-42) is a 409; any other violation is a bug and propagates.
            if (isDuplicateBookingViolation(e)) {
                throw new ConflictException(DUPLICATE_MESSAGE);
            }
            throw e;
        }

        // 6. Rating recalculation happens AFTER commit, in its own transaction — never inline
        // here (Anti-Bug §F.2/§F.8). See ClientReviewEventListener.
        // A guest subject has no users row and no stored aggregate: its readout is computed on read (REV-D10).
        if (!guestSubject) {
            eventPublisher.publishEvent(new ClientReviewCreatedEvent(booking.getClient().getId()));
        }

        // 7. Locked product decision: no notification/outbox row is enqueued for this event — a
        // client is never told a master reviewed them.
        return ClientReviewResponse.from(saved);
    }

    /** The race backstop's constraint: {@code client_reviews.booking_id} is unique (V128). */
    static final String DUPLICATE_BOOKING_CONSTRAINT = "uq_client_reviews_booking";

    /**
     * Matches only Hibernate's structured {@code getConstraintName()} — never a message substring (the PgJDBC detail
     * line can echo row values). Same rationale as {@code CategoryRequestService#isNameUniqueViolation}.
     */
    private static boolean isDuplicateBookingViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hce
                    && DUPLICATE_BOOKING_CONSTRAINT.equals(hce.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * B546 guest arm. One gate, {@link ClientRatingEligibility#guestGate} (the CTA flag's own predicate): flag off is
     * today's 400 (REV-41); no phone (a walk-in without one, an anonymised row) is 400, there is no identity to rate
     * (D2). Then the per-phone attach lock (the one {@code ClientPhoneAttachService} takes), and a re-read that also
     * row-locks the booking: an attach that committed after this booking was loaded has already moved this phone's
     * ratings, so writing a phone-keyed row now would strand it on an attached booking — 409, the client retries and
     * the booking is then account-keyed; a re-read miss caused by the anonymisation sweep is the NO_IDENTITY 400. The row lock keeps the GST-51 anonymisation from interleaving (see
     * {@link BookingRepository#existsUnattachedGuestBooking}).
     */
    private void requireRateableGuest(Booking booking) {
        switch (eligibility.guestGate(booking)) {
            case DISABLED -> throw new BusinessException(HttpStatus.BAD_REQUEST, GUEST_DISABLED_MESSAGE);
            case NO_IDENTITY -> throw new BusinessException(HttpStatus.BAD_REQUEST, NO_IDENTITY_MESSAGE);
            case RATEABLE -> { }
        }
        bookingRepository.acquireWalkInPhoneLock(booking.getGuestPhone());
        if (!bookingRepository.existsUnattachedGuestBooking(booking.getId(), booking.getGuestPhone())) {
            // Lost to the anonymisation sweep: no identity left to rate (same 400 as the gate). Else an attach.
            if (bookingRepository.isGuestPiiAnonymised(booking.getId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, NO_IDENTITY_MESSAGE);
            }
            throw new ConflictException("This booking was just linked to a client account. Try again");
        }
    }
}
