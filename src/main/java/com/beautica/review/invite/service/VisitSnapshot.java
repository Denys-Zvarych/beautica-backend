package com.beautica.review.invite.service;

import com.beautica.booking.enums.BookingSource;
import com.beautica.review.invite.repository.ReviewInviteVisitView;

import java.util.UUID;

/**
 * Phase 400 — what the invite gates (D2) need to know about one visit, read fresh at create and again at send.
 * Holds the raw guest phone: never logged, never put in an exception message ({@link #toString} redacts it).
 *
 * @param appointmentId          the visit header id; {@code null} for a booking-keyed snapshot (D7)
 * @param bookingId              phase 400 D7 — the single booking id when the subject has no visit header, else
 *                               {@code null}
 * @param source                 {@code appointments.booking_source}
 * @param hasClient              the visit belongs to a registered account ({@code client_id IS NOT NULL})
 * @param guestPhone             the guest phone as stored (E.164), {@code null} after anonymisation
 * @param anonymised             {@code guest_pii_anonymised_at IS NOT NULL}
 * @param policyVersion          the privacy-policy version the guest accepted, {@code null} for pre-381 visits
 * @param hasUnreviewedCompleted at least one COMPLETED booking of the visit has no review yet
 */
public record VisitSnapshot(
        UUID appointmentId,
        UUID bookingId,
        BookingSource source,
        boolean hasClient,
        String guestPhone,
        boolean anonymised,
        String policyVersion,
        boolean hasUnreviewedCompleted
) {

    /** A visit-keyed snapshot ({@code bookingId = null}) — the pre-D7 shape. */
    public VisitSnapshot(UUID appointmentId, BookingSource source, boolean hasClient, String guestPhone,
                         boolean anonymised, String policyVersion, boolean hasUnreviewedCompleted) {
        this(appointmentId, null, source, hasClient, guestPhone, anonymised, policyVersion, hasUnreviewedCompleted);
    }

    static VisitSnapshot of(ReviewInviteVisitView view) {
        return new VisitSnapshot(view.getAppointmentId(), view.getBookingId(),
                BookingSource.valueOf(view.getBookingSource()), view.getHasClient(), view.getGuestPhone(), view.getAnonymised(), view.getPolicyVersion(),
                view.getHasUnreviewedCompleted());
    }

    /** A LINK visit with no account — the only source the min-policy-version gate (D2) applies to. */
    public boolean isGuestLinkVisit() {
        return source == BookingSource.LINK && !hasClient;
    }

    /**
     * Phase 434 — a STAFF (walk-in) visit with no account. Gets the same invite as a LINK guest but is exempt from the
     * min-policy-version gate: walk-ins never record a {@code guest_privacy_policy_version} (user decision 2026-11-04).
     */
    public boolean isGuestStaffVisit() {
        return source == BookingSource.STAFF && !hasClient;
    }

    /** Phase 434 — the visits that get a review invite: a LINK guest or a STAFF walk-in, never APP, never an account. */
    public boolean isReviewInviteVisit() {
        return isGuestLinkVisit() || isGuestStaffVisit();
    }

    @Override
    public String toString() {
        return "VisitSnapshot[appointmentId=" + appointmentId + ", bookingId=" + bookingId + ", source=" + source + ", guestPhone=<redacted>]";
    }
}
