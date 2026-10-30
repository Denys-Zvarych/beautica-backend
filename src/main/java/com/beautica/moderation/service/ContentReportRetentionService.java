package com.beautica.moderation.service;

import com.beautica.moderation.config.ModerationProperties;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.moderation.repository.ModerationActionTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntUnaryOperator;

/**
 * Phase 396 D7 (locked 2026-10-28) — report retention: a report is kept while its target exists, plus one year.
 * <ol>
 *   <li>Every report whose target no longer exists gets {@code target_gone_at = now}; the OPEN ones are closed as
 *       {@code TARGET_GONE}. One anti-join statement per target type ({@code ContentReportRepository#markGone*}), a
 *       {@code REMOVED} review / client review counting as gone (D21).</li>
 *   <li>Reports with {@code target_gone_at} more than one calendar year (UTC) before {@code now} are deleted.</li>
 * </ol>
 * Every statement is batched ({@code app.moderation.retention.batch-size} rows, at most {@code max-batches-per-run}
 * batches per statement kind), each batch in its own {@code REQUIRES_NEW} transaction — the guest-PII retention
 * pattern: a failure keeps the earlier batches, and no single statement holds row locks over the whole table.
 * Reports on a live target are never purged, whatever their age. {@code now} is a parameter (the job passes the
 * injected {@code Clock}'s instant) so tests pin it.
 *
 * <p>Phase 414: the same sweep then (3) revokes every live e-mail action link whose target has no OPEN report left —
 * the bulk {@code TARGET_GONE} closure above does not go through {@code ModerationService#resolve}, so this is its
 * D11 link kill — and (4) deletes action-link rows that expired more than 30 days ago (D16). Same batching.
 */
@Service
public class ContentReportRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ContentReportRetentionService.class);

    /** D7: one year after the target is gone. */
    static final int RETENTION_YEARS = 1;

    /** Phase 414 D16: expired action-link rows are kept this long past expiry, then purged. */
    static final Duration ACTION_TOKEN_GRACE = Duration.ofDays(30);

    private final ContentReportRepository contentReportRepository;
    private final ModerationActionTokenRepository actionTokenRepository;
    private final ModerationProperties.Retention retention;
    private final TransactionTemplate batchTx;

    /** Outcome of one sweep. */
    public record SweepResult(int markedGone, int purged) {}

    public ContentReportRetentionService(ContentReportRepository contentReportRepository,
                                         ModerationActionTokenRepository actionTokenRepository,
                                         ModerationProperties properties,
                                         PlatformTransactionManager transactionManager) {
        this.contentReportRepository = contentReportRepository;
        this.actionTokenRepository = actionTokenRepository;
        this.retention = properties.retention();
        this.batchTx = new TransactionTemplate(transactionManager);
        this.batchTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SweepResult sweep(Instant now) {
        Instant stamp = now.truncatedTo(ChronoUnit.MICROS);
        int markedGone = 0;
        for (BiFunction<Instant, Integer, Integer> markGone : markGoneStatements()) {
            markedGone += inBatches(batch -> markGone.apply(stamp, batch));
        }
        Instant cutoff = cutoff(stamp);
        int purged = inBatches(batch -> contentReportRepository.deleteGoneBefore(cutoff, batch));
        int revokedLinks = inBatches(batch -> actionTokenRepository.revokeLiveWithoutOpenReport(stamp, batch));
        Instant tokenCutoff = stamp.minus(ACTION_TOKEN_GRACE);
        int purgedLinks = inBatches(batch -> actionTokenRepository.deleteExpiredBefore(tokenCutoff, batch));
        log.info("Content report retention: {} reports marked target-gone, {} purged; {} action links revoked, "
                + "{} purged", markedGone, purged, revokedLinks, purgedLinks);
        return new SweepResult(markedGone, purged);
    }

    private List<BiFunction<Instant, Integer, Integer>> markGoneStatements() {
        return List.of(
                contentReportRepository::markGoneReviewTargets,
                contentReportRepository::markGoneClientReviewTargets,
                contentReportRepository::markGonePortfolioPhotoTargets,
                contentReportRepository::markGoneMasterProfileTargets,
                contentReportRepository::markGoneSalonProfileTargets,
                contentReportRepository::markGoneClientProfileTargets);
    }

    /** Runs one batched statement until a batch comes back short or the per-run budget is spent; returns the total. */
    private int inBatches(IntUnaryOperator statement) {
        int batchSize = retention.batchSize();
        int total = 0;
        for (int batch = 0; batch < retention.maxBatchesPerRun(); batch++) {
            Integer affected = batchTx.execute(status -> statement.applyAsInt(batchSize));
            int count = affected == null ? 0 : affected;
            total += count;
            if (count < batchSize) {
                break;
            }
        }
        return total;
    }

    static Instant cutoff(Instant now) {
        return now.atOffset(ZoneOffset.UTC).minusYears(RETENTION_YEARS).toInstant();
    }
}
