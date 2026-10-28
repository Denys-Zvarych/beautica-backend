package com.beautica.moderation.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One user's report of one piece of UGC (phase 395). The row is the source of truth for moderation; the
 * support e-mail is a lossy notification only.
 *
 * <p><b>Write path.</b> Rows are inserted only through
 * {@link com.beautica.moderation.repository.ContentReportRepository#insertIgnoringDuplicate}, a native
 * {@code INSERT ... ON CONFLICT DO NOTHING} on {@code ux_content_reports_reporter_target}, so a concurrent
 * double submit collapses onto one row without an aborted transaction. The entity is therefore read-only
 * here: no setters, no public constructor.
 *
 * <p><b>Not mapped yet.</b> {@code resolution} and {@code resolved_at} exist in V207 for phase 396, which
 * maps them together with the resolve actions.
 *
 * <p>{@code comment} is the reporter's free text: it never goes into the support e-mail or a log line.
 */
@Entity
@Table(
        name = "content_reports",
        indexes = {
                @Index(name = "ux_content_reports_reporter_target",
                        columnList = "reporter_user_id, target_type, target_id", unique = true),
                @Index(name = "idx_content_reports_status_created", columnList = "status, created_at"),
                @Index(name = "idx_content_reports_target", columnList = "target_type, target_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentReport extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32, updatable = false)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32, updatable = false)
    private ReportReason reason;

    @Column(name = "comment", length = 500, updatable = false)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReportStatus status;
}
