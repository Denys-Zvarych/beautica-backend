package com.beautica.moderation.repository;

import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

    /** The reporter's existing report on a target, if any — D4's idempotent-repeat lookup (unique index). */
    Optional<ContentReport> findByReporterUserIdAndTargetTypeAndTargetId(
            UUID reporterUserId, ReportTargetType targetType, UUID targetId);

    /**
     * Inserts an {@code OPEN} report unless the reporter already has one on this target. Concurrency-safe:
     * {@code ON CONFLICT} on {@code ux_content_reports_reporter_target} makes the second of two concurrent
     * submits a no-op instead of a {@code DataIntegrityViolationException} that would abort its transaction.
     * {@code status} comes from the column default ({@code OPEN}); {@code created_at}/{@code updated_at} are the
     * caller's clock instant, so the service can answer with the stored values without re-reading the row.
     *
     * @return 1 when this call created the row, 0 when a report on the same target already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, comment,
                                         created_at, updated_at)
            VALUES (:id, :reporterUserId, :targetType, :targetId, :reason, :comment, :createdAt, :createdAt)
            ON CONFLICT (reporter_user_id, target_type, target_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(
            @Param("id") UUID id,
            @Param("reporterUserId") UUID reporterUserId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("reason") String reason,
            @Param("comment") String comment,
            @Param("createdAt") Instant createdAt);
}
