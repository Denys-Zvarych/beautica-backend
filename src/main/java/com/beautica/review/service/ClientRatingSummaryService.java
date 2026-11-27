package com.beautica.review.service;

import com.beautica.common.RatingBucket;
import com.beautica.common.exception.NotFoundException;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.RatingCountProjection;
import com.beautica.user.UserRatingProjection;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * B526 (D2) — the ONE computation of a client's rating summary, shared by {@code GET /users/me/rating} and the
 * provider client card so both always show the same average, count and buckets. Visible ratings only (REV-D10).
 */
@Service
@RequiredArgsConstructor
public class ClientRatingSummaryService {

    private final UserRepository userRepository;
    private final ClientReviewRepository clientReviewRepository;

    /** @throws NotFoundException when the user does not exist */
    @Transactional(readOnly = true)
    public ClientRatingSummary summaryFor(UUID clientUserId) {
        UserRatingProjection projection = userRepository.findRatingById(clientUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return new ClientRatingSummary(projection.getAvgRating(), projection.getReviewCount(),
                distributionFor(clientUserId));
    }

    /** The grouped bucket query, zero-filled 5 down to 1 (one statement). */
    @Transactional(readOnly = true)
    public List<RatingBucket> distributionFor(UUID clientUserId) {
        return zeroFilled(clientReviewRepository.countBySubjectClientIdGroupByRating(clientUserId).stream()
                .collect(Collectors.toMap(RatingCountProjection::getRating, RatingCountProjection::getCount)));
    }

    /**
     * B546 (REV-D10, CLI-43) — a guest phone's visible 5-to-1 star counts, zero-filled (one statement). The guest's
     * average and count are NOT computed here: they ride the caller's statement through
     * {@link com.beautica.review.repository.GuestRatingSql} (REV-36, one computation for card, list and detail).
     */
    @Transactional(readOnly = true)
    public List<RatingBucket> guestDistributionFor(String phone) {
        return zeroFilled(clientReviewRepository.countByGuestPhoneGroupByRating(phone).stream()
                .collect(Collectors.toMap(RatingCountProjection::getRating, RatingCountProjection::getCount)));
    }

    private static List<RatingBucket> zeroFilled(Map<Integer, Long> countsByRating) {
        return IntStream.rangeClosed(1, 5)
                .boxed()
                .sorted(Comparator.reverseOrder())
                .map(rating -> new RatingBucket(rating, countsByRating.getOrDefault(rating, 0L)))
                .toList();
    }

    /** Five zero buckets, 5 down to 1, built without a query (unrated registered client, guest). */
    public static List<RatingBucket> zeroDistribution() {
        return IntStream.rangeClosed(1, 5).boxed().sorted(Comparator.reverseOrder())
                .map(rating -> new RatingBucket(rating, 0L)).toList();
    }
}
