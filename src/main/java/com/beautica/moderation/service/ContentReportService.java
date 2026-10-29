package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ContentReportRateLimitedException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.moderation.dto.CreateReportRequest;
import com.beautica.moderation.dto.ReportResponse;
import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.event.ContentReportCreatedEvent;
import com.beautica.moderation.repository.ContentReportRepository;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 395 — UGC report intake ({@code POST /api/v1/reports}).
 *
 * <p>Order of checks, each chosen so a cheaper or more informative answer wins:
 * <ol>
 *   <li>request shape: {@code OTHER} needs a comment (400);</li>
 *   <li>D4 idempotent repeat: an existing report on the same target is returned as-is (200), spending no rate
 *       token and sending no e-mail;</li>
 *   <li>D6 rate limit: 20 new reports per 24 h per user (429), charged before the visibility lookups so the
 *       limit also bounds id probing;</li>
 *   <li>D2 visibility: a target the reporter cannot read is 404 with the generic not-found body (no
 *       existence oracle);</li>
 *   <li>D5 self-report: 400;</li>
 *   <li>insert via {@code ON CONFLICT DO NOTHING}; only a row this call created publishes
 *       {@link ContentReportCreatedEvent} (support e-mail after commit, D7). A concurrent twin that lost the
 *       race reads the winner's row and answers 200. The 201 body is built from the values just written
 *       (id, {@code OPEN}, the clock instant truncated to Postgres' microsecond precision) — no re-read.</li>
 *   <li>phase 396: a NEW {@code REVIEW} report runs {@link ReviewAutoHideService#onReviewReported} in the same
 *       transaction (burst guard + auto-hide threshold); a burst flags the support e-mail.</li>
 * </ol>
 * The reporter's comment is stored trimmed (blank becomes {@code null}) and is never logged.
 */
@Service
@RequiredArgsConstructor
public class ContentReportService {

    private static final Logger log = LoggerFactory.getLogger(ContentReportService.class);

    /** {@code Retry-After} for D6 — the 24-hour window of {@code RateLimitConfig#contentReportBuckets}. */
    static final long RATE_LIMIT_RETRY_AFTER_SECONDS = Duration.ofHours(24).toSeconds();

    private final ContentReportRepository contentReportRepository;
    private final ReportTargetVisibilityResolver visibilityResolver;
    private final ReviewAutoHideService reviewAutoHideService;
    private final ApplicationEventPublisher eventPublisher;

    /** Per-user D6 budget; bound by bean name ({@code RateLimitConfig#contentReportBuckets}). */
    private final LoadingCache<String, Bucket> contentReportBuckets;

    private final Clock clock;

    /** Outcome of {@link #createReport}: the report and whether this call created it (201) or found it (200). */
    public record CreateReportResult(ReportResponse report, boolean created) {}

    @Transactional
    public CreateReportResult createReport(UUID reporterId, Role reporterRole, CreateReportRequest request) {
        String comment = normalizeComment(request.comment());
        requireCommentForOther(request.reason(), comment);

        Optional<ContentReport> existing = findExisting(reporterId, request);
        if (existing.isPresent()) {
            return new CreateReportResult(ReportResponse.from(existing.get()), false);
        }

        consumeRateLimitToken(reporterId);
        requireReportableTarget(reporterId, reporterRole, request);
        return insert(reporterId, request, comment);
    }

    private Optional<ContentReport> findExisting(UUID reporterId, CreateReportRequest request) {
        return contentReportRepository.findByReporterUserIdAndTargetTypeAndTargetId(
                reporterId, request.targetType(), request.targetId());
    }

    private void consumeRateLimitToken(UUID reporterId) {
        if (!contentReportBuckets.get(reporterId.toString()).tryConsume(1)) {
            throw new ContentReportRateLimitedException(RATE_LIMIT_RETRY_AFTER_SECONDS);
        }
    }

    private void requireReportableTarget(UUID reporterId, Role reporterRole, CreateReportRequest request) {
        ReportTargetVisibilityResolver.ReportTarget target = visibilityResolver
                .resolve(request.targetType(), request.targetId(), reporterId, reporterRole)
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        if (target.ownedByReporter()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Own content cannot be reported");
        }
    }

    private CreateReportResult insert(UUID reporterId, CreateReportRequest request, String comment) {
        UUID id = UUID.randomUUID();
        // timestamptz stores microseconds: truncate so the 201 body equals the stored row exactly.
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int inserted = contentReportRepository.insertIgnoringDuplicate(id, reporterId,
                request.targetType().name(), request.targetId(), request.reason().name(), comment, createdAt);
        if (inserted == 0) {
            // A concurrent submit of the same (reporter, target) committed first — answer as a repeat.
            ContentReport winner = findExisting(reporterId, request)
                    .orElseThrow(() -> new IllegalStateException("Conflicting content report not visible"));
            return new CreateReportResult(ReportResponse.from(winner), false);
        }
        boolean burst = request.targetType() == ReportTargetType.REVIEW
                && reviewAutoHideService.onReviewReported(request.targetId());
        eventPublisher.publishEvent(new ContentReportCreatedEvent(
                id, request.targetType(), request.targetId(), request.reason(), reporterId, burst, comment));
        log.info("Content report {} created on {} by user {}", id, request.targetType(), reporterId);
        return new CreateReportResult(new ReportResponse(id, ReportStatus.OPEN, createdAt), true);
    }

    private static String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireCommentForOther(ReportReason reason, String comment) {
        if (reason == ReportReason.OTHER
                && (comment == null || comment.length() < CreateReportRequest.MIN_OTHER_COMMENT_LENGTH)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "A comment is required for reason OTHER");
        }
    }
}
