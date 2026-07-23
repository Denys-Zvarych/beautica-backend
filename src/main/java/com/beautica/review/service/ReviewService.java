package com.beautica.review.service;

import org.springframework.data.domain.Sort;
import java.util.Set;
import com.beautica.common.web.SortWhitelist;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.review.dto.CreateAppointmentReviewRequest;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.MasterReviewSummaryResponse;
import com.beautica.review.dto.MyReviewResponse;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.dto.SalonReviewSummaryResponse;
import com.beautica.review.dto.SalonReviewSummaryResponse.RatingBucket;
import com.beautica.review.entity.Review;
import com.beautica.review.event.ReviewCreatedEvent;
import com.beautica.review.repository.RatingCountProjection;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ReviewResponse createReview(UUID clientId, CreateReviewRequest request) {
        // clientId must come from Authentication in the controller — never from request body
        Booking booking = bookingRepository.findByIdWithFullGraph(request.bookingId())
                .orElseThrow(() -> new NotFoundException("Booking not found"));

        // A guest (LINK) booking has no registered account (V89 chk_bookings_guest_fields —
        // client_id is null), so no authenticated CLIENT can ever be its owner. Guarding
        // against a null client here (instead of dereferencing unconditionally) turns what
        // would be a 500 into the same uniform 403 an unowned booking gets — mirrors
        // BookingService.cancelBooking / rescheduleBooking, and stays consistent with
        // BookingService.canReview, which already treats a guest COMPLETED booking as
        // non-reviewable (no account exists to leave a review with).
        if (booking.getClient() == null || !booking.getClient().getId().equals(clientId)) {
            throw new ForbiddenException("Not authorized to review this booking");
        }

        // BE-6 per-item review guard (checked AFTER ownership so a foreign booking still 403s — no
        // existence oracle). A booking that is one item of a multi-service visit carries a non-null
        // appointment FK; a review must be left ONCE for the WHOLE visit, never per child, or the
        // visit would collect N reviews. Mirrors BookingService#assertNotAppointmentChild (409, same
        // envelope). The FK id is on the booking row itself, so getAppointment() needs no query.
        if (booking.getAppointment() != null) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "This booking is part of a multi-service visit; leave one review for the whole "
                            + "visit via POST /appointments/{id}/review");
        }

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Review can only be submitted for completed bookings");
        }

        if (reviewRepository.existsByBookingId(booking.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Review already exists for this booking");
        }

        Review review = Review.builder()
                .booking(booking)
                .client(booking.getClient())
                .master(booking.getMaster())
                .salon(booking.getSalon())
                .rating(request.rating().shortValue())
                .comment(request.comment())
                .build();

        Review saved;
        try {
            saved = reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Review already exists for this booking");
        }
        // booking.getSalon() is already populated on Review.salon by the builder above
        // (verified: createReview sets .salon(booking.getSalon()), not a lazy re-derivation
        // from master.getSalon() — booking.salon is the source of truth for "which salon
        // owned this booking at completion time"). null for an INDEPENDENT_MASTER booking.
        UUID salonId = booking.getSalon() != null ? booking.getSalon().getId() : null;
        eventPublisher.publishEvent(new ReviewCreatedEvent(booking.getMaster().getId(), salonId));
        return ReviewResponse.from(saved);
    }

    /**
     * Creates the ONE review a client may leave for a COMPLETED multi-service visit (BE-6). The
     * appointment is the review target — {@code appointment_id} is set and is the authoritative
     * one-review-per-visit dedup key (partial unique index {@code ux_reviews_appointment}, V127) —
     * while {@code booking_id} points at the visit's FIRST child booking so the whole existing
     * review read/render surface (masters/salon reviews lists, {@code GET /reviews/{id}},
     * {@code /reviews/me}) keeps working unchanged. {@code master_id}/{@code salon_id} come from the
     * visit (single master; the header salon is the same value stamped on every item at create time).
     *
     * <p>Authorization + status + duplicate handling mirror {@link #createReview} exactly: a missing
     * visit is a 404, a foreign/guest visit collapses to the same 403 (no existence oracle), a
     * non-COMPLETED visit is a 400 with the same message, and a second review is a 409 — the exact
     * same envelopes the single-booking path returns (no new error shapes). {@code clientId} comes
     * only from the authenticated principal, never the body.
     */
    @Transactional
    public ReviewResponse createAppointmentReview(
            UUID clientId, UUID appointmentId, CreateAppointmentReviewRequest request) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));

        // A guest (LINK) visit has a null client (no registered account), so no authenticated CLIENT
        // can own it — guarding against null here turns a would-be 500 into the same uniform 403 a
        // foreign visit gets (mirrors createReview above and AppointmentTransitionService).
        if (appointment.getClient() == null || !appointment.getClient().getId().equals(clientId)) {
            throw new ForbiddenException("Not authorized to review this appointment");
        }

        if (appointment.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Review can only be submitted for completed bookings");
        }

        if (reviewRepository.existsByAppointmentId(appointmentId)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Review already exists for this appointment");
        }

        // Ordered, graph-hydrated items (master.user, master.salon, masterService.serviceDefinition
        // all fetched) — needed for the master, the first-child booking_id, and ReviewResponse.from's
        // serviceName. Never empty for a real visit (≥1 chained row); guard defensively.
        List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointmentId);
        if (items.isEmpty()) {
            throw new NotFoundException("Appointment not found");
        }
        Booking first = items.get(0);

        Review review = Review.builder()
                .booking(first)
                .appointment(appointment)
                .client(appointment.getClient())
                .master(first.getMaster())
                .salon(appointment.getSalon())
                .rating(request.rating().shortValue())
                .comment(request.comment())
                .build();

        Review saved;
        try {
            saved = reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // A same-visit race that slips past existsByAppointmentId above trips ux_reviews_appointment
            // — same 409 the single-booking duplicate path returns.
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Review already exists for this appointment");
        }

        UUID salonId = appointment.getSalon() != null ? appointment.getSalon().getId() : null;
        eventPublisher.publishEvent(new ReviewCreatedEvent(first.getMaster().getId(), salonId));
        return ReviewResponse.from(saved);
    }

    private static final int MAX_PAGE_NUMBER = 10_000;

    /**
     * Sortable, paginated received-reviews list for a master's public profile ({@code GET
     * /masters/{masterId}/reviews}). Two-query ID-then-hydrate pattern (avoids the HHH90003004
     * in-memory pagination warning + N+1), with the caller-supplied {@link Pageable} sort
     * stripped before it reaches the repository. The sort dimension is a closed
     * {@link SalonReviewSort} enum (Phase 8.11) dispatched to one of four fixed repository
     * methods — reused verbatim from the salon list rather than duplicated, since the four wire
     * values are identical (§DRY).
     *
     * <p>Cached under {@code reviews-by-master} with {@code sync = true} to collapse the
     * thundering herd when a popular master's page expires. The key includes {@code sort}
     * (Phase 8.11 decision 3) so each sort dimension gets its own entry — omitting it would
     * make all four sorts collide on one entry and silently serve the wrong order. Evicted by
     * {@link com.beautica.review.event.ReviewEventListener#onReviewCreated} via a prefix scan on
     * {@code "master:<masterId>:"}, which spans every sort key.
     */
    @Cacheable(
            value = "reviews-by-master",
            key = "'master:' + #masterId + ':sort:' + #sort + ':page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize",
            sync = true)
    @Transactional(readOnly = true)
    public Page<ReviewResponse> getReviewsForMaster(UUID masterId, SalonReviewSort sort, Pageable pageable) {
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Page number must not exceed " + MAX_PAGE_NUMBER);
        }
        SalonReviewSort effectiveSort = sort != null ? sort : SalonReviewSort.NEWEST;

        // Strip caller-supplied sort: each JPQL query below hardcodes its own ORDER BY.
        // A caller-supplied sort field can trigger PropertyReferenceException, leaking entity property names.
        Pageable unsortedPage = SortWhitelist.stripSort(pageable);
        Page<UUID> idPage = switch (effectiveSort) {
            case NEWEST -> reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(masterId, unsortedPage);
            case OLDEST -> reviewRepository.findIdsByMasterIdOrderByCreatedAtAsc(masterId, unsortedPage);
            case HIGHEST -> reviewRepository.findIdsByMasterIdOrderByRatingDescCreatedAtDesc(masterId, unsortedPage);
            case LOWEST -> reviewRepository.findIdsByMasterIdOrderByRatingAscCreatedAtDesc(masterId, unsortedPage);
        };
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        List<UUID> ids = idPage.getContent();
        List<Review> reviews = reviewRepository.findByIdsWithGraph(ids);
        Map<UUID, Review> byId = reviews.stream()
                .collect(Collectors.toMap(Review::getId, r -> r));
        List<ReviewResponse> content = ids.stream()
                .map(id -> ReviewResponse.from(byId.get(id)))
                .toList();
        return new PageImpl<>(content, pageable, idPage.getTotalElements());
    }

    /**
     * Principal-scoped list of reviews authored by the signed-in client ({@code GET /reviews/me}).
     * Not cached: a client's own short history is low-traffic and per-principal, so a cache would
     * add eviction surface (new reviews) for little gain.
     */
    @Transactional(readOnly = true)
    public PageResponse<MyReviewResponse> getMyReviews(UUID clientUserId, Pageable pageable) {
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Page number must not exceed " + MAX_PAGE_NUMBER);
        }
        // Strip caller-supplied sort: the JPQL has ORDER BY r.createdAt DESC hardcoded.
        // An arbitrary sort field would trigger PropertyReferenceException, leaking property names.
        Pageable unsortedPage = SortWhitelist.stripSort(pageable);
        Page<MyReviewResponse> page = reviewRepository.findMyReviews(clientUserId, unsortedPage);
        return PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    // Reviews are immutable after creation — no @CacheEvict path needed.
    @Cacheable(value = "review-detail", key = "#reviewId")
    @Transactional(readOnly = true)
    public ReviewResponse getReview(UUID reviewId) {
        Review review = reviewRepository.findByIdWithAssociations(reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        return ReviewResponse.from(review);
    }

    // ── Master reviews summary (Phase 8.10) ─────────────────────────────────────

    /**
     * Rating summary for a master's public profile ({@code GET
     * /masters/{masterId}/reviews/summary}). Structural copy of {@link #getSalonReviewSummary},
     * swapping {@code Salon}→{@code Master}: {@code avgRating}/{@code reviewCount} are read
     * straight off the persisted {@code Master} row (no live aggregation), consistent with the
     * "recalculate on write, read persisted on read" contract {@link
     * com.beautica.review.event.ReviewEventListener} maintains for the master rating.
     *
     * <p>Not cached — parity with {@link #getSalonReviewSummary} (a single primary-key lookup
     * plus one indexed GROUP BY, cheap enough that a dedicated cache would only add
     * eviction-wiring surface). {@code Review.master} is non-null for every review, so master
     * aggregation always applies.
     */
    @Transactional(readOnly = true)
    public MasterReviewSummaryResponse getMasterReviewSummary(UUID masterId) {
        Master master = masterRepository.findById(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found: " + masterId));

        List<RatingCountProjection> rows = reviewRepository.countByMasterIdGroupByRating(masterId);
        Map<Integer, Long> countsByRating = rows.stream()
                .collect(Collectors.toMap(RatingCountProjection::getRating, RatingCountProjection::getCount));

        // Zero-fill every bucket from 5 down to 1 — a rating with zero reviews must still
        // appear in the response, never be silently omitted.
        List<RatingBucket> distribution = IntStream.rangeClosed(1, 5)
                .boxed()
                .sorted((a, b) -> b - a)
                .map(rating -> new RatingBucket(rating, countsByRating.getOrDefault(rating, 0L)))
                .toList();

        BigDecimal avgRating = master.getReviewCount() == 0 ? null : master.getAvgRating();

        return new MasterReviewSummaryResponse(avgRating, master.getReviewCount(), distribution);
    }

    // ── Salon reviews (Phase 13.6 — Public Salon Profile) ───────────────────────

    /**
     * Rating summary for a salon's public profile ({@code GET /salons/{salonId}/reviews/summary}).
     *
     * <p>{@code avgRating}/{@code reviewCount} are read straight off the persisted
     * {@code Salon} row (no live aggregation) — consistent with the "recalculate on write,
     * read persisted on read" contract {@link com.beautica.review.event.ReviewEventListener}
     * maintains. Not cached: this is a single primary-key lookup plus one indexed GROUP BY,
     * cheap enough that a dedicated cache would only add eviction-wiring surface.
     */
    @Transactional(readOnly = true)
    public SalonReviewSummaryResponse getSalonReviewSummary(UUID salonId) {
        Salon salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        List<RatingCountProjection> rows = reviewRepository.countBySalonIdGroupByRating(salonId);
        Map<Integer, Long> countsByRating = rows.stream()
                .collect(Collectors.toMap(RatingCountProjection::getRating, RatingCountProjection::getCount));

        // Zero-fill every bucket from 5 down to 1 — a rating with zero reviews must still
        // appear in the response, never be silently omitted.
        List<RatingBucket> distribution = IntStream.rangeClosed(1, 5)
                .boxed()
                .sorted((a, b) -> b - a)
                .map(rating -> new RatingBucket(rating, countsByRating.getOrDefault(rating, 0L)))
                .toList();

        BigDecimal avgRating = salon.getReviewCount() == 0 ? null : salon.getAvgRating();

        return new SalonReviewSummaryResponse(avgRating, salon.getReviewCount(), distribution);
    }

    /**
     * Sortable, paginated review list for a salon's public profile ({@code GET
     * /salons/{salonId}/reviews}). Mirrors {@link #getReviewsForMaster} exactly (two-query
     * ID-then-hydrate pattern, caller-supplied sort stripped from the {@link Pageable}
     * before it reaches the repository) — the only difference is the sort dimension is a
     * closed {@link SalonReviewSort} enum dispatched to one of four fixed repository
     * methods, instead of a single hardcoded {@code ORDER BY}.
     *
     * <p>Cached under {@code reviews-by-salon} — same rationale as {@link #getReviewsForMaster}:
     * a public, high-traffic endpoint backed by an identical two-query pattern. The key
     * includes {@code sort} (unlike the master path, which has only one fixed order) so each
     * sort dimension gets its own cache entry. {@code sync = true} collapses the thundering
     * herd when a popular salon's page expires. Evicted by {@link
     * com.beautica.review.event.ReviewEventListener#onReviewCreated} via a prefix scan on
     * {@code "salon:<salonId>:"}.
     */
    @Cacheable(
            value = "reviews-by-salon",
            key = "'salon:' + #salonId + ':sort:' + #sort + ':page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize",
            sync = true)
    @Transactional(readOnly = true)
    public Page<SalonReviewResponse> getSalonReviews(UUID salonId, SalonReviewSort sort, Pageable pageable) {
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Page number must not exceed " + MAX_PAGE_NUMBER);
        }
        SalonReviewSort effectiveSort = sort != null ? sort : SalonReviewSort.NEWEST;

        // Strip caller-supplied sort: each JPQL query below hardcodes its own ORDER BY.
        // A caller-supplied sort field can trigger PropertyReferenceException, leaking
        // entity property names.
        Pageable unsortedPage = SortWhitelist.stripSort(pageable);
        Page<UUID> idPage = switch (effectiveSort) {
            case NEWEST -> reviewRepository.findIdsBySalonIdOrderByCreatedAtDesc(salonId, unsortedPage);
            case OLDEST -> reviewRepository.findIdsBySalonIdOrderByCreatedAtAsc(salonId, unsortedPage);
            case HIGHEST -> reviewRepository.findIdsBySalonIdOrderByRatingDescCreatedAtDesc(salonId, unsortedPage);
            case LOWEST -> reviewRepository.findIdsBySalonIdOrderByRatingAscCreatedAtDesc(salonId, unsortedPage);
        };
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        List<UUID> ids = idPage.getContent();
        List<Review> reviews = reviewRepository.findByIdsWithGraphForSalonReviews(ids);
        Map<UUID, Review> byId = reviews.stream()
                .collect(Collectors.toMap(Review::getId, r -> r));
        // Re-sort hydrated results back into the ID-list order — findByIdsWithGraphForSalonReviews
        // returns rows in undefined order (same contract as findByIdsWithGraph).
        List<SalonReviewResponse> content = ids.stream()
                .map(id -> SalonReviewResponse.from(byId.get(id)))
                .toList();
        return new PageImpl<>(content, pageable, idPage.getTotalElements());
    }
}
