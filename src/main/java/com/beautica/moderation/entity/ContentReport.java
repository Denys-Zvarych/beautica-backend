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

import java.time.Instant;
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
 * <p><b>Resolution (phase 396).</b> {@code status}, {@code resolution}, {@code resolved_at}, {@code target_gone_at},
 * {@code burst_hold} and {@code resolution_note} are written only by bulk statements in
 * {@code ContentReportRepository} (resolve all OPEN reports on a target, the burst hold, the D7 retention sweep), so
 * the entity stays read-only.
 *
 * <p><b>Reporter (V208).</b> {@code reporter_user_id} is {@code ON DELETE SET NULL}: a deleted reporter's reports
 * survive (D7 retention) with a {@code null} reporter, which never counts toward auto-hide. V209's trigger clears
 * {@code comment} in the same row write, so an orphaned report keeps no reporter text.
 *
 * <p>{@code comment} is the reporter's free text: it never goes into the support e-mail or a log line.
 */
@Entity
@Table(
        name = "content_reports",
        indexes = {
                // ux_content_reports_reporter_target (WHERE reporter_user_id IS NOT NULL), idx_content_reports_open_created
                // (WHERE status = 'OPEN') and idx_content_reports_target_gone (WHERE target_gone_at IS NOT NULL) are
                // partial indexes (V208), as is idx_content_reports_resolved_at (resolved_at DESC, id DESC WHERE
                // status = 'RESOLVED', V209) and idx_content_reports_reporter_created ((reporter_user_id, created_at DESC,
                // id DESC) INCLUDE (target_type, target_id) WHERE reporter_user_id IS NOT NULL, V220) — not expressible
                // in JPA @Index.
                @Index(name = "idx_content_reports_target", columnList = "target_type, target_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentReport extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** {@code null} once the reporter's account was deleted (V208 {@code ON DELETE SET NULL}). */
    @Column(name = "reporter_user_id", updatable = false)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 16)
    private ReportResolution resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** D7: when the target was found gone (or removed); the row is purged one year later. */
    @Column(name = "target_gone_at")
    private Instant targetGoneAt;

    /** Burst guard: held reports never count toward auto-hide; support resolves them. */
    @Column(name = "burst_hold", nullable = false)
    private boolean burstHold;

    /** Support's free-text note on resolve. Never logged. */
    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;
}
