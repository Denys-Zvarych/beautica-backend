package com.beautica.moderation.service;

import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportTargetType;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 415 D8 — what one {@link ModerationService#resolve} call decided, handed to the in-app feed in the same
 * transaction. Ids only: no report comment, resolution note, content or names.
 *
 * @param targetType    the resolved target's type (stored as the feed row's {@code moderated_target_type})
 * @param resolution    the resolution every closed report got
 * @param batchReportId the report id passed to {@code resolve}; the dedup key suffix (D7)
 * @param reporterIds   distinct, non-null reporter accounts of the reports this call closed
 * @param authorIds     the accounts that answer for the content (see {@link ModerationAuthorResolver}); empty unless
 *                      the action was a REMOVE on a review / client review / photo
 * @param subjectIds    the accounts the removed review is ABOUT (follow-up): the reviewed provider (independent master,
 *                      or the salon's owner + active admins) for a review, the reviewed client for a client review;
 *                      empty otherwise
 */
public record ModerationOutcome(
        ReportTargetType targetType,
        ReportResolution resolution,
        UUID batchReportId,
        Set<UUID> reporterIds,
        Set<UUID> authorIds,
        Set<UUID> subjectIds) {

    public ModerationOutcome {
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(batchReportId, "batchReportId");
        reporterIds = Set.copyOf(reporterIds);
        authorIds = authorIds == null ? Set.of() : Set.copyOf(authorIds);
        subjectIds = subjectIds == null ? Set.of() : Set.copyOf(subjectIds);
    }
}
