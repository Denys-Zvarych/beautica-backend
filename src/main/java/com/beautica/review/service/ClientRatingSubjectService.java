package com.beautica.review.service;

import com.beautica.review.event.ClientReviewCreatedEvent;
import com.beautica.review.repository.ClientReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.UUID;

/**
 * B546 — the lifecycle of a provider rating's SUBJECT (REV-38): a guest / walk-in rating is keyed to the booking's
 * phone, moves to the account when the booking attaches (REV-39, REV-D11) and dies with the guest PII (REV-40). The
 * other features (phone attach, guest-PII anonymisation, account self-delete) call this, never the review repository.
 * Every method joins the caller's transaction ({@code MANDATORY}).
 */
@Service
@RequiredArgsConstructor
public class ClientRatingSubjectService {

    private final ClientReviewRepository clientReviewRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Moves the phone-keyed ratings of the bookings now attached to {@code clientId} onto the account and, when any
     * moved, recalculates the account aggregate after commit ({@link ClientReviewCreatedEvent}). Call in the attach
     * transaction, after the bookings UPDATE.
     *
     * @return rows moved
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int moveGuestRatingsToAccount(UUID clientId) {
        int moved = clientReviewRepository.moveGuestRatingsToAccount(clientId);
        if (moved > 0) {
            eventPublisher.publishEvent(new ClientReviewCreatedEvent(clientId));
        }
        return moved;
    }

    /** Deletes the phone-keyed ratings of just-anonymised guest bookings (GST-51, REV-40). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteGuestRatingsOfBookings(Collection<UUID> bookingIds) {
        return bookingIds.isEmpty() ? 0 : clientReviewRepository.deleteGuestRatingsOfBookings(bookingIds);
    }

    /** Deletes any phone-keyed rating left on the account's bookings before a self-delete detaches them (CLI-66). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteGuestRatingsOfAccountBookings(UUID clientId) {
        return clientReviewRepository.deleteGuestRatingsOfAccountBookings(clientId);
    }
}
