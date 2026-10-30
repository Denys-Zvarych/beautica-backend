package com.beautica.review.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.review.entity.ClientReview;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.entity.Review;
import com.beautica.review.event.ClientReviewModerationChangedEvent;
import com.beautica.review.event.ReviewModerationChangedEvent;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 396 — the review side of moderation: locks a review row, moves its {@code moderation_status}, and publishes
 * the after-commit event that recalculates the VISIBLE-only rating aggregates and evicts the cached public views
 * (D3: "recalculate ratings on every status change"). The moderation package decides WHEN; this service owns HOW,
 * so no other package writes a review row.
 *
 * <p>{@code REMOVED} is final: a RESTORE / DISMISS of a removed review is 409, never a silent no-op recorded as
 * RESTORED / DISMISSED (the retention sweep treats a removed review as a gone target).
 *
 * <p>Every method joins the caller's transaction ({@link Propagation#MANDATORY}): the lock, the status write and the
 * report resolution the caller performs next must commit or roll back together.
 */
@Service
@RequiredArgsConstructor
public class ReviewModerationService {

    private final ReviewRepository reviewRepository;
    private final ClientReviewRepository clientReviewRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** What the auto-hide rule needs to know about a locked review. Ids only. */
    public record LockedReview(UUID reviewId, UUID masterId, UUID salonId, ModerationStatus status) {}

    /**
     * Locks the review {@code FOR UPDATE} for the rest of the caller's transaction. Concurrent reports on the same
     * review serialise here, so each one's count sees the previous one's committed report.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedReview> lockReview(UUID reviewId) {
        return reviewRepository.findByIdForUpdate(reviewId).map(review -> new LockedReview(
                review.getId(),
                review.getMaster().getId(),
                review.getSalon() != null ? review.getSalon().getId() : null,
                review.getModerationStatus()));
    }

    /**
     * Moves the review the caller locked with {@link #lockReview} (same transaction) to {@code target}; publishes
     * {@link ReviewModerationChangedEvent} only when the status actually changed. Loads the row through the
     * persistence context ({@code findById}), never a second {@code FOR UPDATE}: the row lock is already held.
     *
     * @throws BusinessException 409 when the review is {@code REMOVED} and {@code target} is not (REMOVED is final)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moderateLockedReview(LockedReview locked, ModerationStatus target) {
        Review review = loadLocked(locked);
        requireNotRemoved(review.getModerationStatus(), target);
        if (review.moderate(target)) {
            publishChanged(review);
        }
    }

    /**
     * The auto-hide transition of a review the caller locked with {@link #lockReview}: VISIBLE &rarr;
     * {@code HIDDEN_PENDING}, stamping {@code auto_hidden_at = now} (counted by the per-provider cap).
     *
     * @return {@code true} when the status changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean autoHideLockedReview(LockedReview locked, Instant now) {
        Review review = loadLocked(locked);
        if (!review.autoHide(now)) {
            return false;
        }
        publishChanged(review);
        return true;
    }

    /** Auto-hides of the locked review's provider (its master, or its salon) at or after {@code since}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long countProviderAutoHidesSince(LockedReview locked, Instant since) {
        return reviewRepository.countAutoHiddenForProviderSince(locked.reviewId(), since);
    }

    private Review loadLocked(LockedReview locked) {
        return reviewRepository.findById(locked.reviewId())
                .orElseThrow(() -> new IllegalStateException("Locked review vanished inside its transaction"));
    }

    private void publishChanged(Review review) {
        User masterUser = review.getMaster().getUser();
        eventPublisher.publishEvent(new ReviewModerationChangedEvent(
                review.getId(),
                review.getMaster().getId(),
                masterUser != null ? masterUser.getId() : null,
                review.getSalon() != null ? review.getSalon().getId() : null));
    }

    private static void requireNotRemoved(ModerationStatus current, ModerationStatus target) {
        if (current == ModerationStatus.REMOVED && target != ModerationStatus.REMOVED) {
            throw new BusinessException(HttpStatus.CONFLICT, "Removed content cannot be restored");
        }
    }

    /**
     * Moves a provider&rarr;client review to {@code target}; publishes {@link ClientReviewModerationChangedEvent}
     * only when the status actually changed.
     *
     * @return {@code false} when the review no longer exists
     * @throws BusinessException 409 when the review is {@code REMOVED} and {@code target} is not (REMOVED is final)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean moderateClientReview(UUID clientReviewId, ModerationStatus target) {
        Optional<ClientReview> found = clientReviewRepository.findByIdForUpdate(clientReviewId);
        if (found.isEmpty()) {
            return false;
        }
        ClientReview review = found.get();
        requireNotRemoved(review.getModerationStatus(), target);
        if (review.moderate(target)) {
            eventPublisher.publishEvent(new ClientReviewModerationChangedEvent(review.getSubjectClient().getId()));
        }
        return true;
    }
}
