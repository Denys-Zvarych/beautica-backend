package com.beautica.moderation.service;

import com.beautica.moderation.config.ModerationProperties;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.service.ReviewModerationService;
import com.beautica.review.service.ReviewModerationService.LockedReview;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 396 D2 — the auto-hide hook, run by {@code ContentReportService} inside the transaction that inserted a NEW
 * {@code REVIEW} report.
 *
 * <ol>
 *   <li>Lock the review row ({@code FOR UPDATE}), then the review's provider ({@link ProviderModerationLock}, D20):
 *       concurrent reports on one review, and on any review of one provider, serialise, so each one's counts see the
 *       previous one's committed report (no missed burst, no two auto-hides both passing the cap).</li>
 *   <li><b>Burst guard (D11).</b> When {@code burst-threshold} (default 5) OPEN review reports from ELIGIBLE reporters
 *       hit the same provider's reviews within {@code burst-window} (default 10 min), every OPEN report of the
 *       provider in the window is put on {@code burst_hold}: held reports never count toward auto-hide, so a
 *       coordinated wave cannot hide anything on its own; support resolves them. Ineligible reports (staff,
 *       strangers) never count toward the burst (D19), so a provider's team cannot suspend auto-hide for real
 *       clients by flooding.</li>
 *   <li><b>Threshold.</b> Otherwise, a VISIBLE review is auto-hidden once {@code auto-hide-threshold} (default 3)
 *       distinct eligible reporters have an OPEN, non-held report on it
 *       ({@link ContentReportRepository#findEligibleOpenReporterIds}).</li>
 *   <li><b>Cap (D16).</b> At most {@code auto-hide-cap} (default 1) auto-hides per provider per
 *       {@code auto-hide-cap-window} (24 h). Past the cap the review stays VISIBLE, its OPEN reports are held for
 *       support and the e-mail is flagged {@code [BURST]}.</li>
 * </ol>
 * <b>Recount rule.</b> Only OPEN reports count. RESTORE / DISMISS resolve every OPEN report on the review, so the
 * old reports can never re-hide it, and reports filed after a restore count from zero.
 */
@Service
@RequiredArgsConstructor
public class ReviewAutoHideService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAutoHideService.class);

    private final ContentReportRepository contentReportRepository;
    private final ReviewModerationService reviewModerationService;
    private final ProviderModerationLock providerLock;
    private final ModerationProperties properties;
    private final Clock clock;

    /**
     * @return {@code true} when this report tripped (or fell inside) a burst, or hit the auto-hide cap — the caller
     *         flags the support e-mail
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onReviewReported(UUID reviewId) {
        Optional<LockedReview> locked = reviewModerationService.lockReview(reviewId);
        if (locked.isEmpty()) {
            return false;
        }
        LockedReview review = locked.get();
        providerLock.lock(review);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (holdBurst(review, now)) {
            return true;
        }
        return review.status() == ModerationStatus.VISIBLE && hideWhenThresholdReached(review, now);
    }

    private boolean holdBurst(LockedReview review, Instant now) {
        Instant since = now.minus(properties.burstWindow());
        long recent = contentReportRepository.countRecentEligibleOpenReviewReportsForProvider(review.reviewId(), since);
        if (recent < properties.burstThreshold()) {
            return false;
        }
        int held = contentReportRepository.holdRecentOpenReviewReportsForProvider(review.reviewId(), since);
        log.warn("Report burst on master {} / salon {}: {} eligible OPEN review reports in {}; {} newly held, "
                        + "auto-hide suspended for them until support resolves",
                review.masterId(), review.salonId(), recent, properties.burstWindow(), held);
        return true;
    }

    /** @return {@code true} when the threshold was reached but the provider's cap was not (reports held instead) */
    private boolean hideWhenThresholdReached(LockedReview review, Instant now) {
        List<UUID> reporters = contentReportRepository.findEligibleOpenReporterIds(review.reviewId());
        if (reporters.size() < properties.autoHideThreshold()) {
            return false;
        }
        long recentHides = reviewModerationService.countProviderAutoHidesSince(
                review, now.minus(properties.autoHideCapWindow()));
        if (recentHides >= properties.autoHideCap()) {
            int held = contentReportRepository.holdOpenReportsOnReview(review.reviewId());
            log.warn("Review {} reached the auto-hide threshold but its provider (master {} / salon {}) already had {} "
                            + "auto-hide(s) in {}; {} reports held for support",
                    review.reviewId(), review.masterId(), review.salonId(), recentHides,
                    properties.autoHideCapWindow(), held);
            return true;
        }
        reviewModerationService.autoHideLockedReview(review, now);
        log.info("Review {} auto-hidden (HIDDEN_PENDING) by {} eligible reporters {}",
                review.reviewId(), reporters.size(), reporters);
        return false;
    }
}
