package com.beautica.review.invite.service;

import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;

import java.math.BigDecimal;

/**
 * Phase 401 follow-up — the provider card (website MasterStrip) on the guest review page: avatar, rating, review
 * count and, for a master, the professional title. Sourced exactly as the public booking-link info endpoints do:
 * <ul>
 *   <li>a master — {@code BookingSlugService#findBySlug}: {@code user.avatarUrl}, {@code user.professionalTitle},
 *       {@code master.avgRating}/{@code reviewCount};</li>
 *   <li>a salon-performed visit — {@code SalonBookingSlugService#findBySlug}: {@code salon.avatarUrl},
 *       {@code salon.avgRating}/{@code reviewCount}, no title (the salon page has none).</li>
 * </ul>
 * The zero-review rule is the shared {@link BookingDetailResponse#masterAvgRatingOrNull}: no reviews → {@code null},
 * never the stored {@code 0.00}.
 *
 * <p><b>Visibility</b> mirrors the booking-link finders: a master shows its card only while it would resolve through
 * {@code MasterRepository#findByBookingSlugWithUser} (active, attached to a user, its salon — if any — active); a
 * salon only while {@code SalonRepository#findBookingPageRowBySlug} would resolve it (active). Otherwise the card is
 * {@link #HIDDEN} — the page still names the provider, but no avatar/title/rating of a deactivated, detached or
 * closed provider is published.
 *
 * <p>Reads only the graph {@code BookingRepository#findGuestReviewable*WithGraph} already fetched
 * ({@code master.user}, {@code master.salon}, {@code booking.salon}); no extra statement per item — it is built once, from the first booking.
 */
record ReviewInviteProviderCard(String avatarUrl, BigDecimal avgRating, int reviewCount, String professionalTitle) {

    static final ReviewInviteProviderCard HIDDEN = new ReviewInviteProviderCard(null, null, 0, null);

    static ReviewInviteProviderCard ofMaster(Master master) {
        User user = master.getUser();
        if (!master.isActive() || user == null || !salonActiveOrNone(master.getSalon())) {
            return HIDDEN;
        }
        return new ReviewInviteProviderCard(user.getAvatarUrl(),
                BookingDetailResponse.masterAvgRatingOrNull(master.getReviewCount(), master.getAvgRating()),
                master.getReviewCount(), user.getProfessionalTitle());
    }

    static ReviewInviteProviderCard ofSalon(Salon salon) {
        if (!salon.isActive()) {
            return HIDDEN;
        }
        return new ReviewInviteProviderCard(salon.getAvatarUrl(),
                BookingDetailResponse.masterAvgRatingOrNull(salon.getReviewCount(), salon.getAvgRating()),
                salon.getReviewCount(), null);
    }

    private static boolean salonActiveOrNone(Salon salon) {
        return salon == null || salon.isActive();
    }
}
