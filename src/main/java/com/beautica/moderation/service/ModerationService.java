package com.beautica.moderation.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.media.service.MediaService;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.dto.ResolveReportRequest;
import com.beautica.moderation.dto.ResolveReportResponse;
import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.moderation.repository.ModerationActionTokenRepository;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.service.ReviewModerationService;
import com.beautica.review.service.ReviewModerationService.LockedReview;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 396 D4 — support's resolve action ({@code POST /api/v1/internal/moderation/reports/{id}/resolve}). One call
 * acts on the report's TARGET and closes <b>every</b> OPEN report on it with the same resolution:
 * <ul>
 *   <li>{@code REMOVE} — review / client review → {@code REMOVED}; portfolio photo → deleted through
 *       {@link MediaService#deletePortfolioPhotoForModeration} (row, then R2 after commit); profiles →
 *       {@code ACTIONED} only (support contacts the owner manually; suspension is out of scope). A removed review or
 *       photo starts the D7 one-year clock ({@code target_gone_at}) on every report of the target.</li>
 *   <li>{@code RESTORE} — review / client review → {@code VISIBLE} (400 for other targets).</li>
 *   <li>{@code DISMISS} — reports closed; an auto-hidden review becomes {@code VISIBLE} again.</li>
 * </ul>
 * A review or photo found gone at resolve time closes its reports as {@code TARGET_GONE}. Every review status change
 * recalculates ratings after commit ({@link ReviewModerationService}). A non-OPEN report is 409, and so is a resolve
 * that finds no OPEN report left to close (a concurrent resolve won). RESTORE / DISMISS of a {@code REMOVED} review or
 * client review is 409 (D21: REMOVED is final); support closes such a report with REMOVE, and the retention sweep
 * treats a removed review as a gone target. The note is stored, never logged.
 *
 * <p>Phase 414 D11: this is the ONE resolve path (curl and the e-mail buttons alike), and every successful resolve
 * revokes all live e-mail action links of the target in the same transaction, so no older e-mail can act again.
 *
 * <p>Phase 415: the same transaction writes the in-app outcome rows for the reporters and, on a content REMOVE, the
 * author ({@link InAppNotificationService#notifyModerationOutcome}); a rolled-back resolve (409) writes none.
 */
@Service
@RequiredArgsConstructor
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);
    private static final String REPORT_ALREADY_RESOLVED = "Report already resolved";
    /** Same 8-char target id prefix as the phase-414 link log (security INFO: no full ids in logs). */
    private static final int TARGET_ID_LOG_PREFIX = 8;

    private final ContentReportRepository contentReportRepository;
    private final ModerationActionTokenRepository actionTokenRepository;
    private final ReviewModerationService reviewModerationService;
    private final ProviderModerationLock providerLock;
    private final MediaService mediaService;
    private final ModerationAuthorResolver authorResolver;
    private final InAppNotificationService inAppNotificationService;
    private final Clock clock;

    @Transactional
    public ResolveReportResponse resolve(UUID reportId, ResolveReportRequest request) {
        ContentReport report = contentReportRepository.findById(reportId)
                .orElseThrow(() -> new NotFoundException("Report not found"));
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new BusinessException(HttpStatus.CONFLICT, REPORT_ALREADY_RESOLVED);
        }
        ReportTargetType type = report.getTargetType();
        UUID targetId = report.getTargetId();
        ModerationAction action = request.action();
        requireApplicable(type, action);
        // Lock-free reads first (audit-fix cycle 1, PERF 4): the author ids are scalars, read before applyToTarget
        // takes the review row lock / provider lock.
        Set<UUID> authorIds = authorIdsOf(type, targetId, action);

        boolean targetExists = applyToTarget(type, targetId, action);
        ReportResolution resolution = targetExists ? resolutionOf(type, action) : ReportResolution.TARGET_GONE;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int resolved = contentReportRepository.resolveOpenOnTarget(
                type, targetId, resolution, normalizeNote(request.note()), now);
        if (resolved == 0) {
            // A concurrent resolve of the same target committed between our read and our UPDATE: answer like a
            // resolved report (409), rolling back the target action this call just applied.
            throw new BusinessException(HttpStatus.CONFLICT, REPORT_ALREADY_RESOLVED);
        }
        if (resolution == ReportResolution.REMOVED || resolution == ReportResolution.TARGET_GONE) {
            contentReportRepository.markTargetGone(type, targetId, now);
        }
        actionTokenRepository.revokeAllForTarget(type, targetId, now);
        notifyOutcome(type, targetId, resolution, reportId, authorIds, now);
        log.info("Moderation: {} reports on {} {} resolved as {} (action {})",
                resolved, type, targetId.toString().substring(0, TARGET_ID_LOG_PREFIX), resolution, action);
        return new ResolveReportResponse(type, targetId, resolution, resolved);
    }

    /** Phase 414 D9: the oldest OPEN report on a target, the one an e-mailed action resolves. */
    @Transactional(readOnly = true)
    public Optional<UUID> findOldestOpenReportId(ReportTargetType type, UUID targetId) {
        return contentReportRepository
                .findFirstByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAscIdAsc(type, targetId, ReportStatus.OPEN)
                .map(ContentReport::getId);
    }

    /** Phase 414 D1 / D7: OPEN reports on a target (shown in the e-mail and on the confirm page). */
    @Transactional(readOnly = true)
    public long countOpenReports(ReportTargetType type, UUID targetId) {
        return contentReportRepository.countByTargetTypeAndTargetIdAndStatus(type, targetId, ReportStatus.OPEN);
    }

    /** Phase 415: the author is looked up only when there will be removed content to tell them about. */
    private Set<UUID> authorIdsOf(ReportTargetType type, UUID targetId, ModerationAction action) {
        return action == ModerationAction.REMOVE ? authorResolver.resolve(type, targetId) : Set.of();
    }

    /** Phase 415 D8: feed rows for the reporters (and the author) in this transaction — curl and e-mail alike. */
    private void notifyOutcome(ReportTargetType type, UUID targetId, ReportResolution resolution, UUID reportId,
                               Set<UUID> authorIds, Instant resolvedAt) {
        Set<UUID> reporterIds = new LinkedHashSet<>(
                contentReportRepository.findReporterIdsResolvedAt(type, targetId, resolvedAt));
        inAppNotificationService.notifyModerationOutcome(
                new ModerationOutcome(type, resolution, reportId, reporterIds, authorIds));
    }

    private static void requireApplicable(ReportTargetType type, ModerationAction action) {
        if (action == ModerationAction.RESTORE && !isReview(type)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RESTORE applies to reviews only");
        }
    }

    /** @return whether the target still exists (only knowable for reviews and a photo REMOVE; otherwise true) */
    private boolean applyToTarget(ReportTargetType type, UUID targetId, ModerationAction action) {
        ModerationStatus status = action == ModerationAction.REMOVE ? ModerationStatus.REMOVED : ModerationStatus.VISIBLE;
        return switch (type) {
            case REVIEW -> moderateReview(targetId, status);
            case CLIENT_REVIEW -> reviewModerationService.moderateClientReview(targetId, status);
            case PORTFOLIO_PHOTO -> action != ModerationAction.REMOVE
                    || mediaService.deletePortfolioPhotoForModeration(targetId);
            case MASTER_PROFILE, SALON_PROFILE, CLIENT_PROFILE -> true;
        };
    }

    /**
     * Review row lock, then the provider lock (same order as intake, D20), then the transition on the locked row.
     *
     * @return {@code false} when the review no longer exists
     */
    private boolean moderateReview(UUID reviewId, ModerationStatus status) {
        Optional<LockedReview> locked = reviewModerationService.lockReview(reviewId);
        if (locked.isEmpty()) {
            return false;
        }
        providerLock.lock(locked.get());
        reviewModerationService.moderateLockedReview(locked.get(), status);
        return true;
    }

    private static ReportResolution resolutionOf(ReportTargetType type, ModerationAction action) {
        return switch (action) {
            case REMOVE -> isProfile(type) ? ReportResolution.ACTIONED : ReportResolution.REMOVED;
            case RESTORE -> ReportResolution.RESTORED;
            case DISMISS -> ReportResolution.DISMISSED;
        };
    }

    private static boolean isReview(ReportTargetType type) {
        return type == ReportTargetType.REVIEW || type == ReportTargetType.CLIENT_REVIEW;
    }

    private static boolean isProfile(ReportTargetType type) {
        return type == ReportTargetType.MASTER_PROFILE
                || type == ReportTargetType.SALON_PROFILE
                || type == ReportTargetType.CLIENT_PROFILE;
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
