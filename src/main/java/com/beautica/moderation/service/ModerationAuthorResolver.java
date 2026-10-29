package com.beautica.moderation.service;

import com.beautica.media.repository.MediaRepository;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.repository.SalonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 415 D6 — the accounts told that a review, client review or photo was removed. {@link ModerationService} calls
 * this BEFORE the action and before any lock (a removed photo's row is deleted), and every read is a scalar query
 * that loads no entity: the review row lock and the provider lock taken next see no stale managed instance, and no
 * entity graph is hydrated while they are held (audit-fix cycle 1, PERF 2 / 4).
 *
 * <ul>
 *   <li>{@code REVIEW} — the client who wrote it; a guest review has no account, so nobody.</li>
 *   <li>{@code CLIENT_REVIEW} — an independent master's review: that master. A salon review is the salon's (only the
 *       owner and admins write it, stored under the performing master's name), so the owner + active admins.</li>
 *   <li>{@code PORTFOLIO_PHOTO} — a salon photo belongs to the salon, never to the uploader: owner + active admins; a
 *       master photo: that master.</li>
 *   <li>Profiles — nobody (no content is removed).</li>
 * </ul>
 * Repository access is direct, as in {@link ReportTargetVisibilityResolver}.
 */
@Component
@RequiredArgsConstructor
public class ModerationAuthorResolver {

    private final ReviewRepository reviewRepository;
    private final ClientReviewRepository clientReviewRepository;
    private final MediaRepository mediaRepository;
    private final SalonRepository salonRepository;

    /** @return a mutable set of author account ids; empty when nobody answers for the target */
    public Set<UUID> resolve(ReportTargetType type, UUID targetId) {
        return switch (type) {
            case REVIEW -> reviewRepository.findClientIdById(targetId)
                    .map(this::single)
                    .orElseGet(LinkedHashSet::new);
            case CLIENT_REVIEW -> clientReviewRepository.findAuthorRefById(targetId)
                    .map(ref -> salonOrMaster(ref.getSalonId(), ref.getMasterUserId()))
                    .orElseGet(LinkedHashSet::new);
            case PORTFOLIO_PHOTO -> mediaRepository.findPortfolioOwnerRefById(targetId)
                    .map(ref -> salonOrMaster(ref.getSalonId(), ref.getMasterUserId()))
                    .orElseGet(LinkedHashSet::new);
            case MASTER_PROFILE, SALON_PROFILE, CLIENT_PROFILE -> new LinkedHashSet<>();
        };
    }

    private Set<UUID> salonOrMaster(UUID salonId, UUID masterUserId) {
        if (salonId != null) {
            return new LinkedHashSet<>(salonRepository.findOwnerAndActiveAdminIds(salonId));
        }
        return masterUserId == null ? new LinkedHashSet<>() : single(masterUserId);
    }

    private Set<UUID> single(UUID userId) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(userId);
        return ids;
    }
}
