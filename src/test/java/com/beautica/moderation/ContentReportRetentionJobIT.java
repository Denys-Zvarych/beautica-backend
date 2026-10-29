package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.moderation.service.ContentReportRetentionJob;
import com.beautica.moderation.service.ContentReportRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 396 D7 — report retention against real Postgres with pinned instants (the service takes {@code now}; the
 * job passes its injected {@code Clock}). Reports on a live target are kept at any age; a gone target's reports are
 * closed as TARGET_GONE, stamped, and purged only once the stamp is more than one year old.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Content report retention (phase 396 D7)")
class ContentReportRetentionJobIT extends AbstractModerationIT {

    private static final Instant T0 = Instant.parse("2027-01-15T03:37:00Z");

    @Autowired
    private ContentReportRetentionService retentionService;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ContentReportRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("live target: a resolved 5-year-old report is kept, even a sweep 10 years later")
    void should_keepReport_when_targetLive() {
        SalonFixture salon = createSalon();
        UUID reportId = insertReport("SALON_PROFILE", salon.salonId(), "RESOLVED", "DISMISSED",
                T0.minus(Duration.ofDays(5 * 365)));

        retentionService.sweep(T0);
        retentionService.sweep(T0.plus(Duration.ofDays(3650)));

        Map<String, Object> row = row(reportId);
        assertThat(row.get("target_gone_at")).isNull();
        assertThat(row.get("resolution")).isEqualTo("DISMISSED");
    }

    @Test
    @DisplayName("gone target: OPEN → RESOLVED/TARGET_GONE + target_gone_at; an earlier resolution is kept; purged "
            + "only after one year")
    void should_closeStampAndPurgeAfterOneYear_when_targetGone() {
        SalonFixture salon = createSalon();
        UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
        UUID openId = insertReport("PORTFOLIO_PHOTO", photoId, "OPEN", null, T0.minus(Duration.ofDays(2)));
        UUID dismissedId = insertReport("PORTFOLIO_PHOTO", photoId, "RESOLVED", "DISMISSED",
                T0.minus(Duration.ofDays(30)));
        UUID liveId = insertReport("SALON_PROFILE", salon.salonId(), "OPEN", null, T0.minus(Duration.ofDays(2)));
        jdbcTemplate.update("DELETE FROM media_files WHERE id = ?", photoId);

        ContentReportRetentionService.SweepResult first = retentionService.sweep(T0);
        Map<String, Object> open = row(openId);
        Map<String, Object> dismissed = row(dismissedId);
        retentionService.sweep(T0.plus(Duration.ofDays(365)));
        boolean keptAtOneYear = exists(openId) && exists(dismissedId);
        ContentReportRetentionService.SweepResult purge = retentionService.sweep(T0.plus(Duration.ofDays(366)));

        assertThat(first.markedGone()).isEqualTo(2);
        assertThat(open.get("status")).isEqualTo("RESOLVED");
        assertThat(open.get("resolution")).isEqualTo("TARGET_GONE");
        assertThat(((Timestamp) open.get("target_gone_at")).toInstant()).isEqualTo(T0);
        assertThat(dismissed.get("resolution")).as("earlier resolution kept").isEqualTo("DISMISSED");
        assertThat(((Timestamp) dismissed.get("target_gone_at")).toInstant()).isEqualTo(T0);
        assertThat(keptAtOneYear).as("exactly one year is not yet past the window").isTrue();
        assertThat(purge.purged()).isEqualTo(2);
        assertThat(exists(openId)).isFalse();
        assertThat(exists(dismissedId)).isFalse();
        assertThat(row(liveId).get("status")).as("live target untouched").isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a deleted account (CLIENT_PROFILE target) and a detached master are gone targets")
    void should_markGone_when_profileAccountDeleted() {
        SalonFixture salon = createSalon();
        UUID clientId = createUser(unique("gone"), "CLIENT", null);
        UUID clientReport = insertReport("CLIENT_PROFILE", clientId, "OPEN", null, T0.minus(Duration.ofDays(1)));
        UUID masterReport = insertReport("MASTER_PROFILE", salon.masterId(), "OPEN", null, T0.minus(Duration.ofDays(1)));
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", clientId);
        jdbcTemplate.update("UPDATE masters SET user_id = NULL, detached_first_name = 'Т', detached_last_name = 'М', "
                + "detached_at = NOW(), is_active = false WHERE id = ?", salon.masterId());

        retentionService.sweep(T0);

        assertThat(row(clientReport).get("resolution")).isEqualTo("TARGET_GONE");
        assertThat(row(masterReport).get("resolution")).isEqualTo("TARGET_GONE");
    }

    @Test
    @DisplayName("RESTORE after auto-hide keeps the reports (target alive) through any later sweep")
    void should_keepReports_when_restoredAfterAutoHide() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
        EligibleClient first = eligibleClient(salon);
        reportReview(first.email(), reviewId);
        reportReview(eligibleClient(salon).email(), reviewId);
        reportReview(eligibleClient(salon).email(), reviewId);
        resolve(reportIdOn(first.userId(), reviewId), "RESTORE");

        retentionService.sweep(Instant.now().plus(Duration.ofDays(3 * 365)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE target_id = ? AND resolution = 'RESTORED' "
                        + "AND target_gone_at IS NULL", Integer.class, reviewId)).isEqualTo(3);
    }

    @Test
    @DisplayName("a REMOVED review's reports are stamped at REMOVE and purged one year later")
    void should_purgeRemovedReviewReports_after_oneYear() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
        EligibleClient reporter = eligibleClient(salon);
        reportReview(reporter.email(), reviewId);
        resolve(reportIdOn(reporter.userId(), reviewId), "REMOVE");
        Instant goneAt = jdbcTemplate.queryForObject(
                "SELECT target_gone_at FROM content_reports WHERE target_id = ?", Timestamp.class, reviewId).toInstant();

        retentionService.sweep(goneAt.plus(Duration.ofDays(200)));
        boolean keptBeforeYear = countReportsOnTarget("REVIEW", reviewId) == 1;
        retentionService.sweep(goneAt.plus(Duration.ofDays(367)));

        assertThat(keptBeforeYear).isTrue();
        assertThat(countReportsOnTarget("REVIEW", reviewId)).isZero();
        assertThat(moderationStatus(reviewId)).as("the review row itself is not touched").isEqualTo("REMOVED");
    }

    @Test
    @DisplayName("test profile: no scheduled job bean (app.moderation.retention.enabled=false)")
    void should_notCreateJobBean_when_retentionDisabled() {
        assertThat(context.getBeanNamesForType(ContentReportRetentionJob.class)).isEmpty();
    }

    /**
     * Regression (same defect as InAppNotificationRepository#deleteCreatedBefore, commit c8d7173e) — each gone-target
     * batch must touch exactly {@code batchSize} rows whatever plan PostgreSQL picks. The former {@code UPDATE ... WHERE
     * id IN (SELECT ... LIMIT :n FOR UPDATE SKIP LOCKED)} form, under a nested-loop semi-join that re-scans the locking
     * sublink per outer row, skips the rows it already updated and returns further ones past the LIMIT. The planner
     * settings force that plan shape; the {@code MATERIALIZED} CTE evaluates the LIMIT exactly once.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"REVIEW", "CLIENT_REVIEW", "PORTFOLIO_PHOTO", "MASTER_PROFILE", "SALON_PROFILE",
            "CLIENT_PROFILE"})
    @DisplayName("gone-target batch marks exactly `batchSize` reports under a re-scanning nested-loop plan")
    void should_markExactlyBatchSize_when_plannerForcesRescanningNestedLoop(String targetType) {
        insertReportsOnGoneTargets(targetType, 20, null);

        int marked = inForcedRescanPlan(() -> switch (targetType) {
            case "REVIEW" -> repository.markGoneReviewTargets(T0, 5);
            case "CLIENT_REVIEW" -> repository.markGoneClientReviewTargets(T0, 5);
            case "PORTFOLIO_PHOTO" -> repository.markGonePortfolioPhotoTargets(T0, 5);
            case "MASTER_PROFILE" -> repository.markGoneMasterProfileTargets(T0, 5);
            case "SALON_PROFILE" -> repository.markGoneSalonProfileTargets(T0, 5);
            case "CLIENT_PROFILE" -> repository.markGoneClientProfileTargets(T0, 5);
            default -> throw new IllegalArgumentException(targetType);
        });

        assertThat(marked).as("one batch must mark exactly `batchSize` reports, never more").isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE target_type = ? AND target_gone_at IS NOT NULL",
                Integer.class, targetType)).isEqualTo(5);
    }

    @Test
    @DisplayName("purge batch deletes exactly `batchSize` reports under a re-scanning nested-loop plan")
    void should_purgeExactlyBatchSize_when_plannerForcesRescanningNestedLoop() {
        insertReportsOnGoneTargets("SALON_PROFILE", 20, T0.minus(Duration.ofDays(400)));

        int purged = inForcedRescanPlan(() -> repository.deleteGoneBefore(T0.minus(Duration.ofDays(365)), 5));

        assertThat(purged).as("one batch must delete exactly `batchSize` reports, never more").isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports", Integer.class))
                .as("15 of the 20 eligible reports must remain after one batch of 5").isEqualTo(15);
    }

    /** Runs {@code work} in one transaction whose planner can only pick a re-scanning nested loop (reverts at commit). */
    private int inForcedRescanPlan(IntSupplier work) {
        Integer result = new TransactionTemplate(transactionManager).execute(status -> {
            for (String knob : List.of("enable_hashagg", "enable_hashjoin", "enable_mergejoin", "enable_material",
                    "enable_sort", "enable_incremental_sort")) {
                jdbcTemplate.execute("SET LOCAL " + knob + " = off");
            }
            return work.getAsInt();
        });
        return result;
    }

    /** {@code count} reports, one reporter, each on its own random (so non-existent) target; gone-stamped if given. */
    private void insertReportsOnGoneTargets(String targetType, int count, Instant goneAt) {
        UUID reporter = createUser(unique("r"), "CLIENT", null);
        Timestamp created = Timestamp.from(T0.minus(Duration.ofDays(500)));
        Timestamp gone = goneAt == null ? null : Timestamp.from(goneAt);
        jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, status, "
                        + "resolution, resolved_at, target_gone_at, created_at, updated_at) "
                        + "SELECT gen_random_uuid(), ?, ?, gen_random_uuid(), 'SPAM', "
                        + "CASE WHEN ?::timestamptz IS NULL THEN 'OPEN' ELSE 'RESOLVED' END, "
                        + "CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE 'TARGET_GONE' END, "
                        + "?::timestamptz, ?::timestamptz, ?, ? FROM generate_series(1, ?)",
                reporter, targetType, gone, gone, gone, gone, created, created, count);
    }

    private UUID insertReport(String targetType, UUID targetId, String status, String resolution, Instant createdAt) {
        UUID id = UUID.randomUUID();
        UUID reporter = createUser(unique("r"), "CLIENT", null);
        Timestamp created = Timestamp.from(createdAt.truncatedTo(ChronoUnit.MICROS));
        jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, status, "
                        + "resolution, resolved_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'SPAM', ?, ?, ?, ?, ?)",
                id, reporter, targetType, targetId, status, resolution, resolution == null ? null : created,
                created, created);
        return id;
    }

    private Map<String, Object> row(UUID reportId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, resolution, target_gone_at FROM content_reports WHERE id = ?", reportId);
    }

    private boolean exists(UUID reportId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports WHERE id = ?", Integer.class,
                reportId) == 1;
    }
}
