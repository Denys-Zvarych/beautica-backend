package com.beautica.review.dto;

import com.beautica.master.entity.Master;
import com.beautica.review.entity.Review;
import com.beautica.review.entity.ReviewSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Single review row for a salon's public review list (Phase 13.6, {@code GET
 * /api/v1/salons/{salonId}/reviews}).
 *
 * <p>Unlike the master-scoped {@link ReviewResponse}, this shape also names the reviewed
 * master ({@code masterId}/{@code masterFirstName}/{@code masterLastName}) — a salon has
 * multiple masters, so the client needs to know which one each review is about — and the
 * booked service name.
 *
 * <p>Callers must load {@code review.client}, {@code review.master.user},
 * {@code review.booking.masterService.serviceDefinition} eagerly (see
 * {@code ReviewRepository#findByIdsWithGraphForSalonReviews}) — all four associations are
 * {@code FetchType.LAZY} on the entity.
 *
 * <p><b>This shape is served to ANONYMOUS callers</b> — {@code GET /salons/&#123;salonId&#125;/reviews}
 * is {@code permitAll()} ({@code SecurityConfig}) and the page is cached under
 * {@code reviews-by-salon}. That is why a DETACHED master (V157 / phase 294 — the staff
 * {@code users} row hard-deleted) is rendered under {@link #DETACHED_MASTER_LABEL} here rather
 * than through {@code Master#displayFirstName()}. The 2026-09-04 product decision that a deleted
 * master's NAME survives on a client's own past booking is scoped to THAT receipt; it does not
 * extend to an unauthenticated public listing, where publishing the snapshot would keep the erased
 * person's name readable by anyone on the internet indefinitely. Never emit
 * {@code detachedFirstName}/{@code detachedLastName} on this path.
 */
public record SalonReviewResponse(
        UUID id,
        UUID masterId,
        String masterFirstName,
        String masterLastName,
        String clientDisplayName,
        String serviceName,
        Integer rating,
        String comment,
        OffsetDateTime createdAt,
        @Schema(description = "True when the authenticated caller wrote this review (the app hides «Поскаржитися» "
                + "on it); always false for an anonymous caller and on booking-link (slug) pages.")
        boolean authoredByViewer,
        @Schema(description = "Who wrote the review (phase 399): APP = a client account in the app; GUEST_LINK = a "
                + "booking-link guest through an SMS invite, clientDisplayName is then the guest's «Ім'я П.» snapshot.")
        ReviewSource source
) {

    /**
     * Neutral provider label emitted in place of a DETACHED master's snapshotted name on this
     * PUBLIC, unauthenticated, cached list.
     *
     * <p>Chosen as the bare Ukrainian noun «Майстер» ("the master"/"the stylist"): the codebase
     * carries no existing Ukrainian neutral constant — the only precedent is this record's own
     * {@code "Anonymous"} client fallback, which is an untranslated leftover and not a pattern
     * worth spreading into a second field of the same payload. «Майстер» reads as a generic role
     * label in the app's own language, discloses nothing about the erased person (not even that an
     * erasure happened, unlike «Видалений майстер»), and renders correctly on the mobile client,
     * which joins the two name fields as
     * {@code '${masterFirstName ?? ''} ${masterLastName ?? ''}'.trim()}
     * ({@code salon_mapper.dart}) — hence {@code masterLastName} is deliberately {@code null}
     * rather than an empty string.
     */
    public static final String DETACHED_MASTER_LABEL = "Майстер";

    /**
     * Neutral author label for a self-deleted client (Phase 300 D3) — see {@code
     * ReviewResponse#DETACHED_CLIENT_LABEL}'s javadoc for the full rationale, including why this is
     * its own constant rather than a shared one.
     */
    public static final String DETACHED_CLIENT_LABEL = "Видалений клієнт";

    // Author name: the shared ReviewAuthorLabel rule, same as ReviewResponse.from — "FirstName L." /
    // "FirstName" / "L." / "Anonymous" for an APP account, DETACHED_CLIENT_LABEL for a self-deleted
    // author (Phase 300), the guestDisplayName snapshot for a GUEST_LINK review (phase 399).
    //
    // Viewer-neutral (authoredByViewer = false): this shape is cached under reviews-by-salon, so the
    // per-viewer flag is stamped afterwards by ReviewViewerService (phase 395).
    public static SalonReviewResponse from(Review review) {
        String displayName = ReviewAuthorLabel.of(review);
        // V157 / phase 294 — a detached master (staff account hard-deleted) is rendered under the
        // neutral DETACHED_MASTER_LABEL, NOT through displayFirstName()/displayLastName(). This
        // endpoint is permitAll() and cached; publishing the snapshot here would keep the erased
        // person's name readable by any anonymous caller for as long as the review exists. The
        // isDetached() gate also removes the NPE that a bare getUser().getFirstName() would raise —
        // the same null-guard shape BookingDetailResponse/AppointmentDetailResponse apply to
        // avatarUrl/role, only resolving to a mask instead of null because this field is non-null
        // on the wire for every attached master.
        Master master = review.getMaster();
        String masterFirstName = master.isDetached() ? DETACHED_MASTER_LABEL : master.displayFirstName();
        String masterLastName = master.isDetached() ? null : master.displayLastName();
        return new SalonReviewResponse(
                review.getId(),
                master.getId(),
                masterFirstName,
                masterLastName,
                displayName,
                review.getBooking().getMasterService().getServiceDefinition().getName(),
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC),
                false,
                review.getSource()
        );
    }

    /** A copy with {@code authoredByViewer} set — never mutate a cached instance. */
    public SalonReviewResponse withAuthoredByViewer(boolean authored) {
        return authored == authoredByViewer ? this
                : new SalonReviewResponse(id, masterId, masterFirstName, masterLastName, clientDisplayName,
                        serviceName, rating, comment, createdAt, authored, source);
    }
}
