package com.beautica.review.service;

import com.beautica.user.User;
import org.springframework.data.domain.Sort;
import java.util.Set;
import com.beautica.common.web.SortWhitelist;
import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.RatingBucket;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.MasterReviewSummaryResponse;
import com.beautica.review.dto.MyReviewResponse;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.dto.SalonReviewSummaryResponse;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    // Phase 333 — see InAppNotificationService's class javadoc for why this is a separate seam,
    // never derived from the eventPublisher/outbox side-effects above.
    private final InAppNotificationService inAppNotificationService;

    /**
     * Absolute-instant "now" for {@link BookingClosureRule#isReviewEligible} — {@link
     * Clock#instant()} as a fixed-offset {@link OffsetDateTime}, the SAME idiom {@code
     * BookingService#resolveNow} uses for the identical purpose (Anti-Bug §G: never {@code
     * Instant.now()} / {@code OffsetDateTime.now()} directly, and never a second, divergent clock
     * idiom for the same rule).
     */
    private OffsetDateTime resolveNow() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

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

        requireReviewable(booking);

        Review review = Review.builder()
                .booking(booking)
                .client(booking.getClient())
                .master(booking.getMaster())
                .salon(booking.getSalon())
                .rating(request.rating().shortValue())
                .comment(request.comment())
                .build();

        Review saved = saveAndAnnounce(review, booking, clientId);
        // The caller just wrote it (phase 395 authoredByViewer).
        return ReviewResponse.from(saved).withAuthoredByViewer(true);
    }

    /**
     * Phase 401 D3/D5 — the guest-link twin of {@link #createReview}: same eligibility gate, same one-review-per-booking
     * rule (409), same persistence, rating-recalculation event and provider notification — only the author differs (no
     * account; the «Ім'я П.» snapshot built by {@link Review#guestLinkReview}). The caller ({@code GuestReviewService})
     * has already proved every booking is a COMPLETED LINK guest booking of the invite (D5a) and validated rating/comment
     * with the same Bean Validation rules as {@code CreateReviewRequest}. Joins the caller's transaction (the invite
     * consume, D4), so a 400/409 on any item rolls the whole submit back.
     *
     * <p>Phase 401 audit-fix (perf LOW): the duplicate check is ONE {@code findReviewedBookingIds} over the whole
     * submit, not one {@code existsByBookingId} per item. Items are still checked in submission order with the same
     * per-item precedence as {@link #requireReviewable} (eligibility 400 before duplicate 409), and the UNIQUE
     * constraint on {@code reviews.booking_id} still turns a concurrent insert into the same 409.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createGuestLinkReviews(List<GuestLinkReviewDraft> drafts) {
        Set<UUID> alreadyReviewed = new HashSet<>(reviewRepository.findReviewedBookingIds(
                drafts.stream().map(d -> d.booking().getId()).toList()));
        OffsetDateTime now = resolveNow();
        for (GuestLinkReviewDraft draft : drafts) {
            Booking booking = draft.booking();
            requireEligible(booking, now);
            if (alreadyReviewed.contains(booking.getId())) {
                throw reviewAlreadyExists();
            }
        }
        // Phase 401 audit-fix (perf MEDIUM): ONE ReviewCreatedEvent per distinct provider of the submit, published
        // after every row is written, so the AFTER_COMMIT ReviewEventListener runs one master + one salon
        // recalculation (each already counting every review of this commit) and one round of cache evictions —
        // never N identical ones. The notification stays per booking (architect D10).
        //
        // Coalesced HERE, not in the listener: Spring dispatches AFTER_COMMIT listeners from afterCompletion, after
        // the transaction's synchronizations are cleared, so a listener-side "already handled in this transaction"
        // set has no transaction to live in (GuestReviewRatingCoalescingIT caught exactly that).
        List<Review> saved = persistAll(drafts.stream()
                .map(d -> Review.guestLinkReview(d.booking(), (short) d.rating(), d.comment(),
                        d.booking().getGuestName(), d.booking().getGuestSurname()))
                .toList());
        Set<ProviderKey> announcedProviders = new HashSet<>();
        Set<UUID> announcedSalons = new HashSet<>();
        List<ReviewCreatedEvent> ratingEvents = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Booking booking = drafts.get(i).booking();
            ReviewCreatedEvent event = ratingEventFor(booking, null);
            if (announcedProviders.add(new ProviderKey(event.masterId(), event.salonId()))) {
                ratingEvents.add(withSalonOnce(event, announcedSalons));
            }
            inAppNotificationService.notifyReviewReceived(saved.get(i).getId(), booking, null);
        }
        ratingEvents.forEach(eventPublisher::publishEvent);
    }

    /** One (master, salon-at-booking-time) pair of a guest submit — the rating-event dedup key. */
    private record ProviderKey(UUID masterId, UUID salonId) {}

    /**
     * Phase 401 audit-fix cycle 2 (perf LOW): a salon visit served by K masters yields K master events but must
     * recalculate / evict its salon ONCE — the first event for a salon carries its id, every later one carries
     * {@code null} (the listener skips the salon branch on null; the passport evictor reads only {@code clientId}).
     */
    private static ReviewCreatedEvent withSalonOnce(ReviewCreatedEvent event, Set<UUID> announcedSalons) {
        if (event.salonId() == null || announcedSalons.add(event.salonId())) {
            return event;
        }
        return new ReviewCreatedEvent(event.masterId(), event.masterUserId(), null, event.clientId());
    }

    /**
     * The eligibility half shared by both create paths. Locked product decision: a booking that entered the client's
     * "Past" tab BY ELAPSED TIME is reviewable even when the provider never marked it COMPLETED — mirrors
     * BookingService#canReview exactly (same BookingClosureRule#isReviewEligible call), so a client offered the
     * canReview CTA can never land here and get rejected. NOT_COMPLETED / CANCELLED / DECLINED stay unreviewable
     * regardless of endsAt — see that method's javadoc.
     */
    private void requireReviewable(Booking booking) {
        requireEligible(booking, resolveNow());

        if (reviewRepository.existsByBookingId(booking.getId())) {
            throw reviewAlreadyExists();
        }
    }

    private static void requireEligible(Booking booking, OffsetDateTime now) {
        if (!BookingClosureRule.isReviewEligible(booking.getStatus(), booking.getEndsAt(), now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "This booking is not yet eligible for a review");
        }
    }

    private static BusinessException reviewAlreadyExists() {
        return new BusinessException(HttpStatus.CONFLICT, "Review already exists for this booking");
    }

    /**
     * The persistence + side-effect half shared by both create paths.
     *
     * @param authorClientId the authoring CLIENT ({@code users.id}), or {@code null} for a guest-link review
     */
    private Review saveAndAnnounce(Review review, Booking booking, UUID authorClientId) {
        Review saved = persist(review);
        eventPublisher.publishEvent(ratingEventFor(booking, authorClientId));
        // Phase 333, matrix row 8 — the rated master + the business owner (never admins). The
        // reviewing CLIENT is the actor and is excluded (a guest author has no account: null, a
        // no-op). `booking` is already the caller's own loaded, managed entity (item 8 of the
        // audit-fix cycle 1 backlog) — never reloaded (audit-fix cycle 1, finding 1).
        inAppNotificationService.notifyReviewReceived(saved.getId(), booking, authorClientId);
        return saved;
    }

    /** Insert + flush; a concurrent insert for the same booking hits the UNIQUE booking_id → the same 409. */
    private Review persist(Review review) {
        try {
            return reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw reviewAlreadyExists();
        }
    }

    /**
     * Guest-path batch insert: one {@code saveAll} + ONE flush (phase 401 audit-fix cycle 2, perf INFO) instead of a
     * flush per item. A concurrent insert for any booking of the batch hits the UNIQUE booking_id → the same 409,
     * and the caller's transaction (with the invite consume) rolls back as a whole.
     */
    private List<Review> persistAll(List<Review> reviews) {
        try {
            List<Review> saved = reviewRepository.saveAll(reviews);
            reviewRepository.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw reviewAlreadyExists();
        }
    }

    /** The rating-recalculation / cache-eviction event for a review of {@code booking}. */
    private static ReviewCreatedEvent ratingEventFor(Booking booking, UUID authorClientId) {
        // booking.getSalon() is already populated on Review.salon by the builder above
        // (verified: createReview sets .salon(booking.getSalon()), not a lazy re-derivation
        // from master.getSalon() — booking.salon is the source of truth for "which salon
        // owned this booking at completion time"). null for an INDEPENDENT_MASTER booking.
        UUID salonId = booking.getSalon() != null ? booking.getSalon().getId() : null;
        // masterUserId (Phase 240 re-audit, Finding 1) addresses the userId-keyed
        // "master-detail-by-user" cache behind GET /masters/me, which masterId cannot reach.
        // Costs nothing: findByIdWithFullGraph above JOIN FETCHes `m.user`, so getMaster() and
        // getUser() are both fully hydrated entities in this persistence context — not proxies —
        // and getId() reads an in-memory field. No lazy initialisation, no extra statement on the
        // review-create path.
        // authorClientId is the AUTHOR, carried so ClientPassportCacheEvictor can drop that
        // client's cached BEAUTY PASSPORT — its reviewsWritten line just changed. null for a
        // guest-link review (no account, no passport; the evictor is null-safe).
        // V157 / phase 294 D1: masters.user_id is nullable. A review can only be written against a
        // COMPLETED booking whose provider is still live, so this is null in no reachable flow
        // today — but a null here would NPE on a write path rather than fail a guard, so the read
        // is guarded and the userId-keyed cache eviction is simply skipped for a detached master
        // (there is no GET /masters/me for a deleted account to serve stale).
        User masterUser = booking.getMaster().getUser();
        return new ReviewCreatedEvent(
                booking.getMaster().getId(),
                masterUser != null ? masterUser.getId() : null,
                salonId,
                authorClientId);
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

    // Phase 396: a review's content is immutable, but its moderation_status is not. Only VISIBLE reviews load here
    // (a hidden/removed one is the same 404 as an unknown id), and every moderation transition evicts this key
    // after commit (ReviewEventListener#onReviewModerationChanged). A 404 is never cached (exception).
    //
    // sync = true closes the load/evict race (audit-fix D22): without it a reader that loaded the row just BEFORE a
    // hide committed could put its VISIBLE copy AFTER the listener's evict, pinning a hidden review for the full TTL.
    // With sync the load runs inside Caffeine's per-key compute, and Caffeine's invalidate of a key whose compute is
    // in flight blocks until that compute finishes and then removes the result — so the evict always lands after
    // any in-flight load. It also coalesces concurrent misses on a hot public key (§F-7).
    @Cacheable(value = "review-detail", key = "#reviewId", sync = true)
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
        return salonReviewSummary(salonId, salon.getAvgRating(), salon.getReviewCount());
    }

    /**
     * Phase 384 audit - {@link #getSalonReviewSummary} for a caller that ALREADY holds the salon's persisted
     * {@code avgRating}/{@code reviewCount} (the public salon page's projection row): the same summary built from
     * those values plus the one GROUP BY, without the primary-key {@code findById} the id-only form spends. Identical
     * output to the id form for the same row (the zero-review normalisation is shared, not copied).
     */
    @Transactional(readOnly = true)
    public SalonReviewSummaryResponse getSalonReviewSummary(UUID salonId, BigDecimal avgRating, int reviewCount) {
        return salonReviewSummary(salonId, avgRating, reviewCount);
    }

    private SalonReviewSummaryResponse salonReviewSummary(UUID salonId, BigDecimal storedAvgRating, int reviewCount) {
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

        BigDecimal avgRating = reviewCount == 0 ? null : storedAvgRating;

        return new SalonReviewSummaryResponse(avgRating, reviewCount, distribution);
    }

    /**
     * Phase 383/384 audit - the newest {@code limit} reviews of a master for the public booking page, uncached, in ONE
     * statement and with no page count. Same order (newest first) and same {@link ReviewResponse} as
     * {@code getReviewsForMaster(NEWEST, 0, limit)}; it bypasses the paged {@code reviews-by-master} cache because the
     * page itself is cached under {@code booking-slug-info} (and evicted with it on a new review).
     */
    @Transactional(readOnly = true)
    public List<ReviewResponse> getLatestReviewsForMaster(UUID masterId, int limit) {
        return reviewRepository.findLatestWithGraphByMasterId(masterId, PageRequest.of(0, limit)).stream()
                .map(ReviewResponse::from)
                .toList();
    }

    /** Salon twin of {@link #getLatestReviewsForMaster}: {@link SalonReviewResponse} rows, newest first, one statement. */
    @Transactional(readOnly = true)
    public List<SalonReviewResponse> getLatestReviewsForSalon(UUID salonId, int limit) {
        return reviewRepository.findLatestWithGraphBySalonId(salonId, PageRequest.of(0, limit)).stream()
                .map(SalonReviewResponse::from)
                .toList();
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
