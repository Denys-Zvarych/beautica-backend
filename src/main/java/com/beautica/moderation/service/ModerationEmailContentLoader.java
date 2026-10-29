package com.beautica.moderation.service;

import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.R2StorageService;
import com.beautica.moderation.dto.ContentReportEmailModel;
import com.beautica.moderation.dto.ReportedContent;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.event.ContentReportCreatedEvent;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.entity.SalonImageSlot;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.beautica.moderation.support.DisplayNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Phase 414 D1 — builds what the support report e-mail shows about one report: the target's content (review text +
 * stars, portfolio photo, or profile name + avatar + the owner's contact e-mail), the reporter's comment and the
 * target's OPEN-report count. Image
 * URLs come from {@link R2StorageService#buildPublicUrl}. Repository access is direct, as in
 * {@link ModerationQueueService}: read-only primary-key lookups. The reporter's identity is never loaded, and the
 * reporter's comment comes from the in-memory event (no re-read of the report row).
 *
 * <p>Runs on {@code reportEmailExecutor} ({@code ContentReportEmailDispatcher}). REQUIRES_NEW: on that pool thread
 * there is no outer transaction, so this is simply a new one (no extra connection); it stays correct when the
 * executor is synchronous (tests), where the call happens inside the reporter's already-committed after-commit phase.
 */
@Service
@RequiredArgsConstructor
public class ModerationEmailContentLoader {

    private final ContentReportRepository contentReportRepository;
    private final ReviewRepository reviewRepository;
    private final ClientReviewRepository clientReviewRepository;
    private final MediaRepository mediaRepository;
    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final R2StorageService r2StorageService;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ContentReportEmailModel load(ContentReportCreatedEvent event) {
        long openReports = contentReportRepository.countByTargetTypeAndTargetIdAndStatus(
                event.targetType(), event.targetId(), ReportStatus.OPEN);
        return new ContentReportEmailModel(event.reportId(), event.targetType(), event.targetId(), event.reason(),
                event.reporterUserId(), event.burst(), contentOf(event.targetType(), event.targetId()),
                event.comment(), openReports, List.of());
    }

    private ReportedContent contentOf(ReportTargetType type, UUID targetId) {
        return switch (type) {
            case REVIEW -> reviewRepository.findById(targetId)
                    .map(r -> ReportedContent.review(r.getComment(), r.getRating(), isHidden(r.getModerationStatus())))
                    .orElseGet(ReportedContent::gone);
            case CLIENT_REVIEW -> clientReviewRepository.findById(targetId)
                    .map(r -> ReportedContent.review(r.getComment(), r.getRating(), isHidden(r.getModerationStatus())))
                    .orElseGet(ReportedContent::gone);
            case PORTFOLIO_PHOTO -> mediaRepository.findById(targetId)
                    .filter(m -> m.getMediaType() == MediaType.PORTFOLIO)
                    .map(m -> ReportedContent.photo(r2StorageService.buildPublicUrl(m.getR2Key())))
                    .orElseGet(ReportedContent::gone);
            case MASTER_PROFILE -> masterRepository.findByIdWithUser(targetId)
                    .map(this::masterProfile)
                    .orElseGet(ReportedContent::gone);
            case SALON_PROFILE -> salonRepository.findById(targetId)
                    .map(this::salonProfile)
                    .orElseGet(ReportedContent::gone);
            case CLIENT_PROFILE -> userRepository.findById(targetId)
                    .map(u -> ReportedContent.profile(DisplayNames.fullName(u.getFirstName(), u.getLastName()),
                            avatarOf(u.getAvatarR2Key()), u.getEmail(), u.isEmailVerified()))
                    .orElseGet(ReportedContent::gone);
        };
    }

    private ReportedContent masterProfile(Master master) {
        // Phase 294: a detached master has no user row; its name survives as a snapshot, its avatar does not.
        User user = master.getUser();
        return ReportedContent.profile(DisplayNames.fullName(master.displayFirstName(), master.displayLastName()),
                user == null ? null : avatarOf(user.getAvatarR2Key()), user == null ? null : user.getEmail(),
                user != null && user.isEmailVerified());
    }

    private ReportedContent salonProfile(Salon salon) {
        // Salons have no separate contact e-mail column: the owner's account address is the contact.
        User owner = salon.getOwner();
        return ReportedContent.profile(salon.getName(), avatarOf(salon.imagePointer(SalonImageSlot.LOGO).key()),
                owner == null ? null : owner.getEmail(), owner != null && owner.isEmailVerified());
    }

    private String avatarOf(String r2Key) {
        return r2Key == null || r2Key.isBlank() ? null : r2StorageService.buildPublicUrl(r2Key);
    }

    private static boolean isHidden(ModerationStatus status) {
        return status == ModerationStatus.HIDDEN_PENDING;
    }
}
