package com.beautica.moderation.dto;

import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 414 — everything the support report e-mail renders: the 395 identifiers, the reported content (D1), the
 * reporter's comment (bidi-escaped and URL-defanged, D2), the open-report count of the target, and the one-click action
 * links (D3). {@code reporterUserId} stays in the model for routing / tests but is NOT rendered (audit-fix: the e-mail
 * names no reporter at all).
 * {@link #toString()} omits the UGC and the links so an accidental log line cannot leak content or tokens.
 *
 * @param reporterUserId  the reporter's id (never rendered); null once the account is deleted
 * @param burst           phase 396 burst / cap flag ({@code [BURST]} subject + banner)
 * @param reporterComment the reporter's free text, bidi-escaped and URL-defanged; null when none
 * @param openReportCount OPEN reports on the target at render time
 * @param actions         the buttons, in display order; empty when minting failed or was skipped
 */
public record ContentReportEmailModel(
        UUID reportId,
        ReportTargetType targetType,
        UUID targetId,
        ReportReason reason,
        UUID reporterUserId,
        boolean burst,
        ReportedContent content,
        String reporterComment,
        long openReportCount,
        List<ModerationActionLink> actions
) {

    public ContentReportEmailModel {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(content, "content");
        reporterComment = ReportedContent.userText(reporterComment);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** The same model carrying {@code links} as its buttons. */
    public ContentReportEmailModel withActions(List<ModerationActionLink> links) {
        return new ContentReportEmailModel(reportId, targetType, targetId, reason, reporterUserId, burst, content,
                reporterComment, openReportCount, links);
    }

    @Override
    public String toString() {
        return "ContentReportEmailModel[reportId=" + reportId + ", targetType=" + targetType
                + ", reason=" + reason + ", burst=" + burst + ", openReportCount=" + openReportCount
                + ", actions=" + actions.size() + "]";
    }
}
