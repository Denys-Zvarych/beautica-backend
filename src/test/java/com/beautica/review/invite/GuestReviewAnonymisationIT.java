package com.beautica.review.invite;

import com.beautica.booking.retention.GuestPii;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.GuestReviewRetentionService;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.review.service.ReviewModerationService;
import com.beautica.review.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 402 D1/D2 — guest review author labels follow the guest-PII lifecycle against real Postgres: the retention
 * batch and the erase both turn a {@code GUEST_LINK} review's «Ім'я П.» into «Гість» (rating and text intact, cached
 * public views evicted), a review already loaded in the session cannot write the old label back (phase 399 audit),
 * and the erase keeps the phone's SMS opt-out.
 *
 * <p>Same {@code @TestPropertySource} as {@code GuestReviewSubmitIT}, so the Spring context is shared.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review anonymisation — phase 402 IT")
class GuestReviewAnonymisationIT extends AbstractReviewInviteIT {

    private static final String GUEST_LABEL = "Марія Ш.";
    private static final String OTHER_PHONE = "+380675556677";

    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @Autowired private ReviewService reviewService;
    @Autowired private GuestReviewRetentionService guestReviewRetentionService;
    @Autowired private ReviewModerationService moderationService;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID guestReview(UUID bookingId) {
        return fixtures.review(bookingId, null, masterId, "GUEST_LINK", GUEST_LABEL);
    }

    private Map<String, Object> reviewRow(UUID reviewId) {
        return jdbcTemplate.queryForMap(
                "SELECT guest_display_name, rating, comment, moderation_status FROM reviews WHERE id = ?", reviewId);
    }

    /** Due: everything that ended more than a day ago; the fixtures below end 47 h ago. */
    private static Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(1));
    }

    @Test
    @DisplayName("retention: a due single LINK booking's review shows «Гість», rating and text intact")
    void should_anonymiseReviewLabel_when_singleBookingDue() {
        UUID booking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 48);
        UUID review = guestReview(booking);

        anonymisationService.anonymiseDue(cutoff());

        assertThat(reviewRow(review))
                .containsEntry("guest_display_name", GuestPii.ANONYMISED_NAME)
                .containsEntry("rating", 4)
                .containsEntry("comment", "Добре");
    }

    @Test
    @DisplayName("retention: a due visit item's review shows «Гість»; a not-yet-due booking's review keeps its label")
    void should_anonymiseOnlyDueReviews_when_retentionRuns() {
        UUID visit = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID item = fixtures.visitItem(visit, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 48);
        UUID recent = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", OTHER_PHONE, POLICY, 3);
        UUID itemReview = guestReview(item);
        UUID recentReview = guestReview(recent);

        anonymisationService.anonymiseDue(cutoff());

        assertThat(reviewRow(itemReview)).containsEntry("guest_display_name", GuestPii.ANONYMISED_NAME);
        assertThat(reviewRow(recentReview)).containsEntry("guest_display_name", GUEST_LABEL);
    }

    @Test
    @DisplayName("retention: the cached review detail and master review page serve «Гість» right after the run")
    void should_evictCachedPublicViews_when_reviewAnonymised() {
        UUID booking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 48);
        UUID review = guestReview(booking);
        PageRequest page = PageRequest.of(0, 20);
        assertThat(reviewService.getReview(review).clientDisplayName()).isEqualTo(GUEST_LABEL);
        assertThat(reviewService.getReviewsForMaster(masterId, SalonReviewSort.NEWEST, page).getContent())
                .extracting(ReviewResponse::clientDisplayName).containsExactly(GUEST_LABEL);

        anonymisationService.anonymiseDue(cutoff());

        assertThat(reviewService.getReview(review).clientDisplayName()).isEqualTo(GuestPii.ANONYMISED_NAME);
        assertThat(reviewService.getReviewsForMaster(masterId, SalonReviewSort.NEWEST, page).getContent())
                .extracting(ReviewResponse::clientDisplayName).containsExactly(GuestPii.ANONYMISED_NAME);
    }

    @Test
    @DisplayName("erase: the phone's review shows «Гість», another phone's keeps its label, the SMS opt-out is kept")
    void should_anonymiseReviewAndKeepOptOut_when_erasingByPhone() {
        UUID booking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 3);
        UUID other = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", OTHER_PHONE, POLICY, 6);
        UUID review = guestReview(booking);
        UUID otherReview = guestReview(other);
        byte[] phoneHash = phoneHasher.hash(GUEST_PHONE).hash();
        jdbcTemplate.update("INSERT INTO sms_opt_outs (phone_hash, source) VALUES (?, 'REVIEW_INVITE')", phoneHash);

        anonymisationService.eraseByPhone(GUEST_PHONE, null);

        assertThat(reviewRow(review))
                .containsEntry("guest_display_name", GuestPii.ANONYMISED_NAME)
                .containsEntry("comment", "Добре");
        assertThat(reviewRow(otherReview)).containsEntry("guest_display_name", GUEST_LABEL);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sms_opt_outs WHERE phone_hash = ?", Integer.class, phoneHash)).isEqualTo(1);
    }

    @Test
    @DisplayName("399 audit: review loaded in the session → erase → moderate in the same transaction → still «Гість»")
    void should_keepAnonymisedLabel_when_reviewLoadedBeforeAnonymisationIsModerated() {
        UUID booking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 3);
        UUID review = guestReview(booking);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            assertThat(reviewRepository.findById(review)).isPresent();
            anonymisationService.eraseByPhone(GUEST_PHONE, null);
            ReviewModerationService.LockedReview locked = moderationService.lockReview(review).orElseThrow();
            moderationService.moderateLockedReview(locked, ModerationStatus.HIDDEN_PENDING);
        });

        assertThat(reviewRow(review))
                .containsEntry("guest_display_name", GuestPii.ANONYMISED_NAME)
                .containsEntry("moderation_status", "HIDDEN_PENDING");
    }

    @Test
    @DisplayName("399 audit, isolated: review loaded → label update alone → moderate in the same transaction → "
            + "still «Гість» (no other statement clears the session first)")
    void should_keepAnonymisedLabel_when_labelUpdateAloneRunsAfterLoad() {
        UUID booking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 3);
        UUID review = guestReview(booking);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            assertThat(reviewRepository.findById(review)).isPresent();
            guestReviewRetentionService.anonymiseReviewAuthors(List.of(booking));
            ReviewModerationService.LockedReview locked = moderationService.lockReview(review).orElseThrow();
            moderationService.moderateLockedReview(locked, ModerationStatus.HIDDEN_PENDING);
        });

        assertThat(reviewRow(review))
                .containsEntry("guest_display_name", GuestPii.ANONYMISED_NAME)
                .containsEntry("moderation_status", "HIDDEN_PENDING");
    }
}
