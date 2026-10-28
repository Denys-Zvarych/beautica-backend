package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.service.ContentReportRetentionJob;
import com.beautica.moderation.service.ContentReportRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

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
