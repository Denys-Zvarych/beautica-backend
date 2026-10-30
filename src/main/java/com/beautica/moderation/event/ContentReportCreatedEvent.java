package com.beautica.moderation.event;

import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;

import java.util.UUID;

/**
 * Published by {@code ContentReportService} when a NEW report row was inserted (never on an idempotent repeat).
 * Phase 414: also carries the reporter's normalized {@code comment} in memory, so the support e-mail does not re-read
 * the row. The event is never persisted (no outbox) and {@link #toString()} omits the comment, so it can never reach a
 * log line through the event.
 * {@code burst} (phase 396) is {@code true} when this report tripped or fell inside a provider burst: the support
 * e-mail is flagged so support looks at the wave as a whole.
 */
public record ContentReportCreatedEvent(
        UUID reportId,
        ReportTargetType targetType,
        UUID targetId,
        ReportReason reason,
        UUID reporterUserId,
        boolean burst,
        String comment) {

    @Override
    public String toString() {
        return "ContentReportCreatedEvent[reportId=" + reportId + ", targetType=" + targetType + ", reason=" + reason
                + ", burst=" + burst + "]";
    }
}
