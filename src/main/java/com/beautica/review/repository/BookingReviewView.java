package com.beautica.review.repository;

/**
 * Scalar projection of ONE client&rarr;provider {@link com.beautica.review.entity.Review}, keyed by
 * the booking it hangs off (Phase 317). Backs {@code BookingDetailResponse#reviewByClient}.
 *
 * <p>Deliberately NOT {@code ReviewResponse}: that record is the shape of the {@code permitAll}
 * {@code GET /masters/&#123;id&#125;/reviews} listing and carries the reviewer's display name and
 * the service name, both of which the enclosing {@code BookingDetailResponse} already renders from
 * its own graph. Selecting them again here would add joins to a query whose whole purpose is to
 * cost one statement, and would give the booking response two independently-drifting copies of the
 * same client identity.
 *
 * <p>{@code rating} is the entity's native {@code Short} — widened to {@code Integer} at the DTO
 * boundary by {@code ClientAuthoredReviewResponse}, never here, so the JPQL constructor expression
 * matches the column type exactly and Hibernate needs no coercion.
 *
 * <p><b>No {@code bookingId} component.</b> The caller already holds the id it queried by —
 * {@code BookingService#getBooking} passes it in and its sole consumer,
 * {@code BookingService#toClientAuthoredReview}, reads only {@code rating} and {@code comment}. A
 * selected-but-never-read column is how a projection quietly grows back into the {@code
 * ReviewResponse} shape this one exists to stay smaller than. {@code reviews.booking_id} is a
 * UNIQUE FK, so the {@link java.util.Optional} the query returns already carries "at most one row
 * per booking" without restating the key in the payload.
 *
 * @param rating  the client's 1-5 star rating
 * @param comment the client's free text, or {@code null}
 */
public record BookingReviewView(
        Short rating,
        String comment
) {}
