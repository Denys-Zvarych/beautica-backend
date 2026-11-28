package com.beautica.review.service;

import com.beautica.booking.entity.Booking;
import com.beautica.review.config.ClientRatingProperties;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * B546 (REV-D04, REV-41) — the ONE "does this booking have a rateable client identity" predicate, shared by the
 * {@code POST /client-reviews} write ({@link ClientReviewService}) and every {@code providerCanReviewClient} flag path
 * ({@code BookingService} detail, provider listing, salon board), so the CTA and the write cannot drift.
 *
 * <p>A registered client is always rateable. A guest / walk-in with no account is rateable by its E.164 phone only
 * while the guest feature flag is on and its PII has not been anonymised (GST-51). Status and provider authority are
 * separate conjuncts owned by the callers.
 */
@Component
public class ClientRatingEligibility {

    private final boolean guestRatingEnabled;

    public ClientRatingEligibility(ClientRatingProperties properties) {
        this.guestRatingEnabled = properties.guestEnabled();
    }

    /** {@code true} when the booking's client (account or guest phone) may receive a provider rating. */
    public boolean isRateable(Booking booking) {
        if (!booking.isRateableByProvider()) {
            return false;
        }
        return booking.getClient() != null || isGuestRateable(booking);
    }

    /**
     * The SQL twin of {@link #isRateable}, for a {@code COUNT(*)} that cannot load entities (the «Архів» pending
     * badge, {@code PendingBookingActionsService}). Kept adjacent to {@link #isRateable} so the two forms change
     * together: {@code client IS NOT NULL}, OR (guest flag on only) {@code guest_phone IS NOT NULL}.
     * {@code Booking#isRateableByProvider} ({@code client || guestPhone}) is implied by either arm.
     *
     * <p>The in-memory guest gate's {@code guestPiiAnonymisedAt == null} term is deliberately NOT repeated here:
     * {@code chk_bookings_guest_fields} (V260; relied on the same way by V263) forces {@code guest_phone IS NULL} on
     * every anonymised row, so the term is redundant in SQL — and dropping it makes the flag-on predicate EQUAL to
     * V271's partial-index predicate ({@code client_id IS NOT NULL OR guest_phone IS NOT NULL}), which lets Postgres
     * strip the qual and run the count as an index-only scan. V191 serves the flag-off form.
     * {@code PendingCountReviewCtaParityIT} (anonymised shapes included) proves agreement with the CTA per row, both
     * flag values; changing this shape means re-checking {@code V271BookingsCompletedRateableIndexesMigrationTest}.
     */
    public Specification<Booking> rateable() {
        Specification<Booking> registered = (root, query, cb) -> cb.isNotNull(root.get("client"));
        if (!guestRatingEnabled) {
            return registered;
        }
        Specification<Booking> guest = (root, query, cb) -> cb.isNotNull(root.get("guestPhone"));
        return registered.or(guest);
    }

    /**
     * The guest arm's single gate, shared by the CTA flag ({@link #isGuestRateable}) and the write
     * ({@code ClientReviewService}), which maps a non-{@link GuestGate#RATEABLE} verdict to its 400 message. Assumes
     * {@code client == null}. The flag is checked here and nowhere else.
     */
    GuestGate guestGate(Booking booking) {
        if (!guestRatingEnabled) {
            return GuestGate.DISABLED;
        }
        if (booking.getGuestPhone() == null || booking.getGuestPiiAnonymisedAt() != null) {
            return GuestGate.NO_IDENTITY;
        }
        return GuestGate.RATEABLE;
    }

    /** Guest arm only: flag on and the guest PII still present. Assumes {@code client == null}. */
    boolean isGuestRateable(Booking booking) {
        return guestGate(booking) == GuestGate.RATEABLE;
    }

    /** Why a guest / walk-in booking is or is not rateable. */
    enum GuestGate {
        RATEABLE,
        /** The guest rating flag is off (REV-41): today's message. */
        DISABLED,
        /** No phone (a walk-in without one) or the guest PII is anonymised (GST-51): no identity to rate. */
        NO_IDENTITY
    }
}
