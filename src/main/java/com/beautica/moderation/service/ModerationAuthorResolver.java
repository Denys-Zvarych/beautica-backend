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
 * Phase 415 D6 — the accounts told that a review, client review or photo was removed: its AUTHOR, and (follow-up) the
 * SUBJECT of a removed review. {@link ModerationService} calls this BEFORE the action and before any lock (a removed
 * photo's row is deleted), and every read is a scalar query that loads no entity.
 *
 * <p><b>Salon rule (user, locked):</b> for anything salon-related ONLY the salon — its owner + active admins — is
 * notified, never a salon master. For a review / client review the salon is the row's own {@code salon_id} only (NULL
 * = the master's independent period, V41, so the master answers even after joining a salon); a salon master's
 * personal portfolio photo goes to their current salon. A closed salon notifies nobody.
 *
 * <table>
 *   <caption>Author / subject per target</caption>
 *   <tr><th>Target</th><th>Author</th><th>Subject</th></tr>
 *   <tr><td>REVIEW</td><td>the client who wrote it (guest: nobody)</td>
 *       <td>independent master, or the salon</td></tr>
 *   <tr><td>CLIENT_REVIEW</td><td>independent master, or the salon</td><td>the reviewed client</td></tr>
 *   <tr><td>PORTFOLIO_PHOTO</td><td>independent master, or the salon (salon or salon-master photo)</td>
 *       <td>nobody</td></tr>
 *   <tr><td>profiles</td><td>nobody</td><td>nobody</td></tr>
 * </table>
 * Repository access is direct, as in {@link ReportTargetVisibilityResolver}.
 */
@Component
@RequiredArgsConstructor
public class ModerationAuthorResolver {

    private final ReviewRepository reviewRepository;
    private final ClientReviewRepository clientReviewRepository;
    private final MediaRepository mediaRepository;
    private final SalonRepository salonRepository;

    /** The parties of removed content, as mutable id sets (empty when nobody applies). */
    public record Parties(Set<UUID> authorIds, Set<UUID> subjectIds) {

        public static Parties none() {
            return new Parties(new LinkedHashSet<>(), new LinkedHashSet<>());
        }
    }

    public Parties resolve(ReportTargetType type, UUID targetId) {
        return switch (type) {
            case REVIEW -> reviewRepository.findModerationPartiesById(targetId)
                    .map(ref -> new Parties(single(ref.getClientId()),
                            salonOrMaster(ref.getSalonId(), ref.getMasterUserId())))
                    .orElseGet(Parties::none);
            case CLIENT_REVIEW -> clientReviewRepository.findAuthorRefById(targetId)
                    .map(ref -> new Parties(salonOrMaster(ref.getSalonId(), ref.getMasterUserId()),
                            single(ref.getSubjectClientId())))
                    .orElseGet(Parties::none);
            case PORTFOLIO_PHOTO -> mediaRepository.findPortfolioOwnerRefById(targetId)
                    .map(ref -> new Parties(salonOrMaster(ref.getSalonId(), ref.getMasterUserId()),
                            new LinkedHashSet<>()))
                    .orElseGet(Parties::none);
            case MASTER_PROFILE, SALON_PROFILE, CLIENT_PROFILE -> Parties.none();
        };
    }

    /** Salon (owner + active admins) when there is one — never the salon master; otherwise the master's account. */
    private Set<UUID> salonOrMaster(UUID salonId, UUID masterUserId) {
        if (salonId != null) {
            return new LinkedHashSet<>(salonRepository.findOwnerAndActiveAdminIds(salonId));
        }
        return single(masterUserId);
    }

    private static Set<UUID> single(UUID userId) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (userId != null) {
            ids.add(userId);
        }
        return ids;
    }
}
