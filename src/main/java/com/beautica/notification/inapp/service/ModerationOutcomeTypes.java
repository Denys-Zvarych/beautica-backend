package com.beautica.notification.inapp.service;

import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.notification.inapp.entity.InAppNotificationType;

/**
 * Phase 415 D1 / D4 / D5 — which feed type a moderation resolution produces for the reporters, and whether the
 * content author is told. Pure mapping, no I/O.
 *
 * <table>
 *   <caption>Reporter type</caption>
 *   <tr><th>Target</th><th>REMOVED</th><th>RESTORED / DISMISSED</th><th>ACTIONED / TARGET_GONE</th></tr>
 *   <tr><td>review, client review, photo</td><td>REPORT_CONTENT_REMOVED</td><td>REPORT_NO_VIOLATION</td>
 *       <td>REPORT_REVIEWED</td></tr>
 *   <tr><td>profile</td><td>(n/a)</td><td>REPORT_NO_VIOLATION (DISMISSED)</td><td>REPORT_REVIEWED</td></tr>
 * </table>
 * The subject of a removed review / client review gets {@code REVIEW_ABOUT_YOU_REMOVED}.
 * The author gets {@code CONTENT_REMOVED} only for {@code REMOVED} content; never on DISMISS / RESTORE (D4), a gone
 * target (D5) or a profile.
 */
final class ModerationOutcomeTypes {

    private ModerationOutcomeTypes() {
    }

    static InAppNotificationType reporterType(ReportTargetType targetType, ReportResolution resolution) {
        return switch (resolution) {
            case REMOVED -> isContent(targetType)
                    ? InAppNotificationType.REPORT_CONTENT_REMOVED
                    : InAppNotificationType.REPORT_REVIEWED;
            case RESTORED, DISMISSED -> InAppNotificationType.REPORT_NO_VIOLATION;
            case ACTIONED, TARGET_GONE -> InAppNotificationType.REPORT_REVIEWED;
        };
    }

    static boolean notifiesAuthor(ReportTargetType targetType, ReportResolution resolution) {
        return resolution == ReportResolution.REMOVED && isContent(targetType);
    }

    /**
     * Phase 415 follow-up — the subject of a removed review / client review (the reviewed provider or client) gets
     * {@code REVIEW_ABOUT_YOU_REMOVED}; never for a photo, a profile, or any non-REMOVED resolution.
     */
    static boolean notifiesSubject(ReportTargetType targetType, ReportResolution resolution) {
        return resolution == ReportResolution.REMOVED
                && (targetType == ReportTargetType.REVIEW || targetType == ReportTargetType.CLIENT_REVIEW);
    }

    static boolean isContent(ReportTargetType targetType) {
        return switch (targetType) {
            case REVIEW, CLIENT_REVIEW, PORTFOLIO_PHOTO -> true;
            case MASTER_PROFILE, SALON_PROFILE, CLIENT_PROFILE -> false;
        };
    }
}
