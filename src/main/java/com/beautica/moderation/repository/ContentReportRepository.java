package com.beautica.moderation.repository;

import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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
     * <p>V208 made that unique index PARTIAL ({@code WHERE reporter_user_id IS NOT NULL}); Postgres infers a partial
     * arbiter only when the {@code ON CONFLICT} clause repeats its predicate, so the two must stay identical.
     *
     * @return 1 when this call created the row, 0 when a report on the same target already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, comment,
                                         created_at, updated_at)
            VALUES (:id, :reporterUserId, :targetType, :targetId, :reason, :comment, :createdAt, :createdAt)
            ON CONFLICT (reporter_user_id, target_type, target_id) WHERE reporter_user_id IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(
            @Param("id") UUID id,
            @Param("reporterUserId") UUID reporterUserId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("reason") String reason,
            @Param("comment") String comment,
            @Param("createdAt") Instant createdAt);

    // ── Auto-hide + burst guard (phase 396 D2) ───────────────────────────────────────────────

    /*
     * ELIGIBLE REPORTER (D9, tightened by the audit-fix D19) — one predicate, used by the threshold, the burst count
     * and nothing else, so the two can never disagree about who counts. For a report cr on review r:
     *   * the reporter row exists (a deleted reporter, null, cannot be verified) and is a CLIENT (staff never count),
     *   * the account was created BEFORE the review (a fresh account cannot be minted to bury a review), and
     *   * the reporter has a COMPLETED booking with r's provider (its master, or its salon) that ENDED no later than the
     *     review was written (only someone who was a client when the review appeared counts).
     * The provider is r's own master_id / salon_id, so no provider id is bound (a null salon needs no typed null).
     * {@code b.starts_at < r.created_at} is implied by {@code ends_at <= r.created_at} (chk_booking_interval:
     * ends_at > starts_at) — it adds no semantics, it only gives the bookings (client_id, status, starts_at) index a
     * range bound instead of a scan of every COMPLETED booking of the client.
     */

    /**
     * Burst guard: OPEN review reports from ELIGIBLE reporters (see above) filed since {@code since} on any review of
     * the same provider as {@code reviewId}. Ineligible reports (staff, strangers) never count, so a provider's own
     * team cannot trip the guard to suspend auto-hide for real clients. Correlated EXISTS per report, driven by the
     * partial {@code idx_content_reports_open_created} range on {@code created_at}.
     */
    @Query(value = """
            SELECT COUNT(*) FROM content_reports cr
             WHERE cr.target_type = 'REVIEW'
               AND cr.status = 'OPEN'
               AND cr.created_at >= :since
               AND EXISTS (SELECT 1 FROM reviews r
                             JOIN reviews p ON p.id = :reviewId
                             JOIN users u ON u.id = cr.reporter_user_id
                            WHERE r.id = cr.target_id
                              AND (r.master_id = p.master_id OR r.salon_id = p.salon_id)
                              AND u.role = 'CLIENT'
                              AND u.created_at < r.created_at
                              AND EXISTS (SELECT 1 FROM bookings b
                                           WHERE b.client_id = u.id
                                             AND b.status = 'COMPLETED'
                                             AND b.ends_at <= r.created_at
                                             AND b.starts_at < r.created_at
                                             AND (b.master_id = r.master_id OR b.salon_id = r.salon_id)))
            """, nativeQuery = true)
    long countRecentEligibleOpenReviewReportsForProvider(
            @Param("reviewId") UUID reviewId, @Param("since") Instant since);

    /** Burst guard: holds every OPEN review report filed since {@code since} on the provider of {@code reviewId}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports cr SET burst_hold = true
             WHERE cr.target_type = 'REVIEW'
               AND cr.status = 'OPEN'
               AND NOT cr.burst_hold
               AND cr.created_at >= :since
               AND EXISTS (SELECT 1 FROM reviews r
                             JOIN reviews p ON p.id = :reviewId
                            WHERE r.id = cr.target_id
                              AND (r.master_id = p.master_id OR r.salon_id = p.salon_id))
            """, nativeQuery = true)
    int holdRecentOpenReviewReportsForProvider(@Param("reviewId") UUID reviewId, @Param("since") Instant since);

    /** Auto-hide cap: holds every OPEN, not yet held report on one review (support decides it). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports SET burst_hold = true
             WHERE target_type = 'REVIEW'
               AND target_id = :reviewId
               AND status = 'OPEN'
               AND NOT burst_hold
            """, nativeQuery = true)
    int holdOpenReportsOnReview(@Param("reviewId") UUID reviewId);

    /**
     * Distinct ELIGIBLE reporters (see above) with an OPEN, non-held report on {@code reviewId} — the auto-hide count.
     */
    @Query(value = """
            SELECT DISTINCT cr.reporter_user_id FROM content_reports cr
              JOIN reviews r ON r.id = cr.target_id
              JOIN users u ON u.id = cr.reporter_user_id
             WHERE cr.target_type = 'REVIEW'
               AND cr.target_id = :reviewId
               AND cr.status = 'OPEN'
               AND NOT cr.burst_hold
               AND u.role = 'CLIENT'
               AND u.created_at < r.created_at
               AND EXISTS (SELECT 1 FROM bookings b
                            WHERE b.client_id = u.id
                              AND b.status = 'COMPLETED'
                              AND b.ends_at <= r.created_at
                              AND b.starts_at < r.created_at
                              AND (b.master_id = r.master_id OR b.salon_id = r.salon_id))
            """, nativeQuery = true)
    List<UUID> findEligibleOpenReporterIds(@Param("reviewId") UUID reviewId);

    /**
     * Transaction-scoped advisory lock on one provider key ({@code ProviderModerationLock}): serialises the burst
     * count + hold and the cap count + hide of every report on the same provider, so two concurrent reports on two
     * different reviews can neither both miss a burst nor both pass the cap, and two overlapping multi-row hold
     * UPDATEs never interleave. Two-key form: its key space never overlaps the single-bigint
     * {@code pg_advisory_xact_lock(hashtext(..))} locks used elsewhere. The scalar wrapper exists because
     * {@code pg_advisory_xact_lock} returns {@code void}.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:namespace, hashtext(:key))) AS l", nativeQuery = true)
    int lockProviderKey(@Param("namespace") int namespace, @Param("key") String key);

    // ── Resolve (phase 396 D4) ───────────────────────────────────────────────────────────────

    /** Closes every OPEN report on one target with the same resolution. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ContentReport cr
               SET cr.status = com.beautica.moderation.entity.ReportStatus.RESOLVED,
                   cr.resolution = :resolution,
                   cr.resolvedAt = :now,
                   cr.resolutionNote = :note,
                   cr.updatedAt = :now
             WHERE cr.targetType = :targetType
               AND cr.targetId = :targetId
               AND cr.status = com.beautica.moderation.entity.ReportStatus.OPEN
            """)
    int resolveOpenOnTarget(
            @Param("targetType") ReportTargetType targetType,
            @Param("targetId") UUID targetId,
            @Param("resolution") ReportResolution resolution,
            @Param("note") String note,
            @Param("now") Instant now);

    /**
     * D7: stamps {@code target_gone_at} on every (already resolved) report of a target that was just removed, so the
     * one-year purge clock starts for all of them, not only for the reports resolved by this action.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ContentReport cr SET cr.targetGoneAt = :now, cr.updatedAt = :now
             WHERE cr.targetType = :targetType
               AND cr.targetId = :targetId
               AND cr.targetGoneAt IS NULL
               AND cr.status = com.beautica.moderation.entity.ReportStatus.RESOLVED
            """)
    int markTargetGone(
            @Param("targetType") ReportTargetType targetType,
            @Param("targetId") UUID targetId,
            @Param("now") Instant now);

    // ── Queue (phase 396 D4) ─────────────────────────────────────────────────────────────────

    /**
     * One page of reports in one status; the caller supplies the fixed sort. OPEN (created_at ASC) is served by
     * idx_content_reports_open_created, RESOLVED (resolved_at DESC, id DESC) by idx_content_reports_resolved_at (V209).
     */
    Page<ContentReport> findByStatus(ReportStatus status, Pageable pageable);

    // ── Retention (phase 396 D7) ─────────────────────────────────────────────────────────────

    /*
     * D7 gone-target sweep, ONE statement per target type, each an anti-join (NOT EXISTS) bounded by LIMIT :batchSize
     * (FOR UPDATE SKIP LOCKED, so two instances never fight over a batch). ContentReportRetentionService loops each until
     * a batch comes back short, every batch in its own transaction. A matching report gets target_gone_at = now; an
     * OPEN one is closed as TARGET_GONE (an already-resolved report keeps its resolution). "Exists" mirrors what
     * ReportTargetVisibilityResolver accepts at intake.
     */

    /** Gone-target batch for {@code REVIEW}: exists = a review row that is not {@code REMOVED} (a removed review is gone for retention: a report that raced the takedown is closed here instead of staying OPEN forever). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports cr
               SET target_gone_at = :now,
                   status = 'RESOLVED',
                   resolution = CASE WHEN cr.status = 'OPEN' THEN 'TARGET_GONE' ELSE cr.resolution END,
                   resolved_at = COALESCE(cr.resolved_at, :now),
                   updated_at = :now
             WHERE cr.id IN (SELECT c.id FROM content_reports c
                              WHERE c.target_gone_at IS NULL
                                AND c.target_type = 'REVIEW'
                                AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.id = c.target_id AND r.moderation_status <> 'REMOVED')
                              LIMIT :batchSize
                                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int markGoneReviewTargets(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** Gone-target batch for {@code CLIENT_REVIEW}: exists = a client review row that is not {@code REMOVED}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports cr
               SET target_gone_at = :now,
                   status = 'RESOLVED',
                   resolution = CASE WHEN cr.status = 'OPEN' THEN 'TARGET_GONE' ELSE cr.resolution END,
                   resolved_at = COALESCE(cr.resolved_at, :now),
                   updated_at = :now
             WHERE cr.id IN (SELECT c.id FROM content_reports c
                              WHERE c.target_gone_at IS NULL
                                AND c.target_type = 'CLIENT_REVIEW'
                                AND NOT EXISTS (SELECT 1 FROM client_reviews x WHERE x.id = c.target_id AND x.moderation_status <> 'REMOVED')
                              LIMIT :batchSize
                                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int markGoneClientReviewTargets(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** Gone-target batch for {@code PORTFOLIO_PHOTO}: exists = a {@code PORTFOLIO} media row. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports cr
               SET target_gone_at = :now,
                   status = 'RESOLVED',
                   resolution = CASE WHEN cr.status = 'OPEN' THEN 'TARGET_GONE' ELSE cr.resolution END,
                   resolved_at = COALESCE(cr.resolved_at, :now),
                   updated_at = :now
             WHERE cr.id IN (SELECT c.id FROM content_reports c
                              WHERE c.target_gone_at IS NULL
                                AND c.target_type = 'PORTFOLIO_PHOTO'
                                AND NOT EXISTS (SELECT 1 FROM media_files m WHERE m.id = c.target_id AND m.media_type = 'PORTFOLIO')
                              LIMIT :batchSize
                                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int markGonePortfolioPhotoTargets(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** Gone-target batch for {@code MASTER_PROFILE}: exists = a master that is not detached (account deleted). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports cr
               SET target_gone_at = :now,
                   status = 'RESOLVED',
                   resolution = CASE WHEN cr.status = 'OPEN' THEN 'TARGET_GONE' ELSE cr.resolution END,
                   resolved_at = COALESCE(cr.resolved_at, :now),
                   updated_at = :now
             WHERE cr.id IN (SELECT c.id FROM content_reports c
                              WHERE c.target_gone_at IS NULL
                                AND c.target_type = 'MASTER_PROFILE'
                                AND NOT EXISTS (SELECT 1 FROM masters ms WHERE ms.id = c.target_id AND ms.user_id IS NOT NULL)
                              LIMIT :batchSize
                                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int markGoneMasterProfileTargets(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** Gone-target batch for {@code SALON_PROFILE}: exists = an active salon. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports cr
               SET target_gone_at = :now,
                   status = 'RESOLVED',
                   resolution = CASE WHEN cr.status = 'OPEN' THEN 'TARGET_GONE' ELSE cr.resolution END,
                   resolved_at = COALESCE(cr.resolved_at, :now),
                   updated_at = :now
             WHERE cr.id IN (SELECT c.id FROM content_reports c
                              WHERE c.target_gone_at IS NULL
                                AND c.target_type = 'SALON_PROFILE'
                                AND NOT EXISTS (SELECT 1 FROM salons sa WHERE sa.id = c.target_id AND sa.is_active)
                              LIMIT :batchSize
                                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int markGoneSalonProfileTargets(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** Gone-target batch for {@code CLIENT_PROFILE}: exists = a user row. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE content_reports cr
               SET target_gone_at = :now,
                   status = 'RESOLVED',
                   resolution = CASE WHEN cr.status = 'OPEN' THEN 'TARGET_GONE' ELSE cr.resolution END,
                   resolved_at = COALESCE(cr.resolved_at, :now),
                   updated_at = :now
             WHERE cr.id IN (SELECT c.id FROM content_reports c
                              WHERE c.target_gone_at IS NULL
                                AND c.target_type = 'CLIENT_PROFILE'
                                AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = c.target_id)
                              LIMIT :batchSize
                                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int markGoneClientProfileTargets(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** D7 purge, one batch: reports whose target has been gone for longer than the retention window. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM content_reports
             WHERE id IN (SELECT id FROM content_reports
                           WHERE target_gone_at < :cutoff
                           LIMIT :batchSize
                             FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteGoneBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
