package com.beautica.review.invite.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.review.entity.Review;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.dto.GuestReviewItemRequest;
import com.beautica.review.invite.dto.ReviewInviteItemResponse;
import com.beautica.review.invite.dto.ReviewInviteResponse;
import com.beautica.review.invite.entity.ReviewInvite;
import com.beautica.review.invite.entity.ReviewInviteStatus;
import com.beautica.review.invite.entity.SmsOptOutSource;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import com.beautica.review.service.GuestLinkReviewDraft;
import com.beautica.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 401 — the guest review page ({@code beautica.app/r/{token}}): read the invite, submit the guest's review(s)
 * once, opt out of review SMS.
 *
 * <p><b>The token is the credential</b> (D1): it is hashed (SHA-256, {@link ReviewInviteTokenHasher}) on entry and
 * never logged, stored or echoed. Every lookup matches hash + status (+ expiry) in ONE statement (D2). Unknown, malformed,
 * expired, voided, flag-off and — on the submit — consumed tokens all end in the same {@link #notFound()} (no
 * oracle). The read alone tells a consumed token apart ({@code ALREADY_REVIEWED}, website 019 input): only the token
 * holder can ask, so it reveals nothing to anyone else.
 *
 * <p>{@link #resolveInviteBookings} is the single place an invite becomes its bookings (D8); read, submit and the
 * D5a eligibility all go through it.
 */
@Service
@RequiredArgsConstructor
public class GuestReviewService {

    /** The website's token alphabet and bounds (base64url; the send job issues 22 chars). Anything else is a 404. */
    static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    /** D6 — how long after the SMS was sent its token can still opt the phone out. */
    static final Duration OPT_OUT_WINDOW = Duration.ofDays(30);
    static final String NOT_FOUND_MESSAGE = "Review invite not found";
    static final String NOT_IN_INVITE_MESSAGE = "Booking is not part of this review invite";
    static final String DUPLICATE_ITEM_MESSAGE = "Each booking can be rated once";

    private final ReviewInviteRepository inviteRepository;
    private final SmsOptOutRepository optOutRepository;
    private final BookingRepository bookingRepository;
    private final ReviewService reviewService;
    private final ReviewInviteProperties properties;
    private final Clock clock;

    /** {@code GET} — the open form, {@code ALREADY_REVIEWED} for a consumed token, else 404. One invite lookup. */
    @Transactional(readOnly = true)
    public ReviewInviteResponse getInvite(String rawToken) {
        byte[] tokenHash = tokenHashOrNotFound(rawToken);
        ReviewInvite invite = inviteRepository.findRedeemableOrConsumed(tokenHash, clock.instant())
                .orElseThrow(GuestReviewService::notFound);
        if (invite.getStatus() == ReviewInviteStatus.CONSUMED) {
            return ReviewInviteResponse.alreadyReviewed();
        }
        List<Booking> bookings = resolveInviteBookings(invite);
        if (bookings.isEmpty()) {
            throw notFound();
        }
        return toResponse(bookings);
    }

    /**
     * {@code POST …/reviews} — D4: atomically redeem the invite ({@code SENT → CONSUMED}, one {@code UPDATE … RETURNING}
     * that takes the row lock), check every item belongs to it (409 otherwise), then write the reviews through the
     * app's shared create path (D3). One transaction: a 400/404/409 after the redeem — an item outside the invite, a
     * duplicate item, an already-reviewed booking — rolls the consume back too, so the token stays usable.
     *
     * @return the number of reviews written
     */
    @Transactional
    public int submit(String rawToken, List<GuestReviewItemRequest> items) {
        byte[] tokenHash = tokenHashOrNotFound(rawToken);
        ReviewInvite invite = inviteRepository.consumeRedeemable(tokenHash, clock.instant())
                .orElseThrow(GuestReviewService::notFound);
        Map<UUID, Booking> bookingsById = resolveInviteBookings(invite).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        if (bookingsById.isEmpty()) {
            throw notFound();
        }
        requireItemsInInvite(items, bookingsById.keySet());
        reviewService.createGuestLinkReviews(items.stream()
                .map(item -> new GuestLinkReviewDraft(bookingsById.get(item.bookingId()), item.rating(), item.comment()))
                .toList());
        return items.size();
    }

    /**
     * {@code POST …/opt-out} — D6: any {@code SENT}/{@code CONSUMED}/{@code EXPIRED} token sent within the last 30 days
     * opts its phone out of review SMS (idempotent). Else 404.
     */
    @Transactional
    public void optOut(String rawToken) {
        byte[] tokenHash = tokenHashOrNotFound(rawToken);
        Instant now = clock.instant();
        ReviewInvite invite = inviteRepository.findOptOutable(tokenHash, now.minus(OPT_OUT_WINDOW))
                .orElseThrow(GuestReviewService::notFound);
        optOutRepository.insertIfAbsent(invite.getPhoneHash(), now, SmsOptOutSource.REVIEW_INVITE.name());
    }

    /**
     * D8 — the ONE place an invite turns into its reviewable bookings: COMPLETED LINK guest bookings
     * ({@code client_id IS NULL}) of the invite's visit, by start time; or, for a booking-keyed invite (V215), its one
     * booking while it still has no visit header and passes the same filter. Anything else (an APP or STAFF booking,
     * a registered client, not COMPLETED, since attached to a visit) is simply absent (D5a). One fetch-joined
     * statement per subject kind. Callers must hold a transaction.
     */
    public List<Booking> resolveInviteBookings(ReviewInvite invite) {
        if (invite.isBookingKeyed()) {
            return bookingRepository.findGuestReviewableSingleBookingWithGraph(invite.getBookingId())
                    .map(List::of)
                    .orElse(List.of());
        }
        return bookingRepository.findGuestReviewableByAppointmentIdWithGraph(invite.getAppointmentId());
    }

    private byte[] tokenHashOrNotFound(String rawToken) {
        if (!properties.enabled() || rawToken == null || !TOKEN_SHAPE.matcher(rawToken).matches()) {
            throw notFound();
        }
        return ReviewInviteTokenHasher.hash(rawToken);
    }

    private static void requireItemsInInvite(List<GuestReviewItemRequest> items, Set<UUID> inviteBookingIds) {
        Set<UUID> seen = new HashSet<>();
        for (GuestReviewItemRequest item : items) {
            if (!seen.add(item.bookingId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, DUPLICATE_ITEM_MESSAGE);
            }
            if (!inviteBookingIds.contains(item.bookingId())) {
                throw new BusinessException(HttpStatus.CONFLICT, NOT_IN_INVITE_MESSAGE);
            }
        }
    }

    private static ReviewInviteResponse toResponse(List<Booking> bookings) {
        Booking first = bookings.get(0);
        boolean salon = first.getSalon() != null;
        List<ReviewInviteItemResponse> items = bookings.stream()
                .map(b -> new ReviewInviteItemResponse(b.getId(),
                        b.getMasterService().getServiceDefinition().getName(), masterName(b.getMaster())))
                .toList();
        // The provider card (website MasterStrip) — sourced like the booking-link info endpoints, from the graph
        // already loaded (master.user, booking.salon): no extra statement.
        ReviewInviteProviderCard card = salon
                ? ReviewInviteProviderCard.ofSalon(first.getSalon())
                : ReviewInviteProviderCard.ofMaster(first.getMaster());
        return ReviewInviteResponse.open(
                salon ? first.getSalon().getName() : masterName(first.getMaster()),
                salon ? ReviewInviteResponse.PROVIDER_SALON : ReviewInviteResponse.PROVIDER_MASTER,
                first.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toLocalDate(),
                items,
                Review.guestDisplayNameFor(first.getGuestName(), first.getGuestSurname()),
                card.avatarUrl(), card.avgRating(), card.reviewCount(), card.professionalTitle());
    }

    // Same rule as GuestBookingService / GuestVisitCancellationService#masterName (V157 / phase 294 D3:
    // displayFirstName()/displayLastName(), null-safe on either half, so a detached master still has a name).
    private static String masterName(Master master) {
        String firstName = master.displayFirstName();
        String lastName = master.displayLastName();
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }

    private static NotFoundException notFound() {
        return new NotFoundException(NOT_FOUND_MESSAGE);
    }
}
