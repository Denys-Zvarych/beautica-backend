package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.service.ContentReportRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 414 D11 / D16 — the report retention sweep also maintains {@code moderation_action_tokens}: live links whose
 * target has no OPEN report left are revoked (the bulk TARGET_GONE path does not go through resolve), and rows that
 * expired more than 30 days before the sweep are deleted while fresher ones are kept.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Content report retention — e-mail action links (phase 414)")
class ContentReportRetentionServiceIT extends AbstractModerationIT {

    private static final Instant T0 = Instant.parse("2027-01-15T03:37:00Z");

    @Autowired
    private ContentReportRetentionService retentionService;

    @Test
    @DisplayName("tokens expired > 30 days before the sweep are deleted; tokens expired 29 days ago are kept")
    void should_purgeOnlyTokensPastGrace_when_swept() {
        UUID old = insertToken(UUID.randomUUID(), T0.minus(Duration.ofDays(31)));
        UUID fresh = insertToken(UUID.randomUUID(), T0.minus(Duration.ofDays(29)));

        retentionService.sweep(T0);

        assertThat(exists(old)).isFalse();
        assertThat(exists(fresh)).isTrue();
    }

    @Test
    @DisplayName("a gone target's reports closed as TARGET_GONE → its live links are revoked; a live target's kept")
    void should_revokeLinks_when_targetGoneClosesReports() {
        SalonFixture salon = createSalon();
        UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
        insertOpenReport("PORTFOLIO_PHOTO", photoId);
        UUID goneLink = insertToken(photoId, T0.plus(Duration.ofDays(7)), "PORTFOLIO_PHOTO");
        insertOpenReport("SALON_PROFILE", salon.salonId());
        UUID liveLink = insertToken(salon.salonId(), T0.plus(Duration.ofDays(7)), "SALON_PROFILE");
        jdbcTemplate.update("DELETE FROM media_files WHERE id = ?", photoId);

        retentionService.sweep(T0);

        assertThat(revoked(goneLink)).isTrue();
        assertThat(revoked(liveLink)).isFalse();
    }

    private void insertOpenReport(String targetType, UUID targetId) {
        jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SPAM', ?, ?)",
                UUID.randomUUID(), createUser(unique("r"), "CLIENT", null), targetType, targetId,
                Timestamp.from(T0.minus(Duration.ofDays(1))), Timestamp.from(T0.minus(Duration.ofDays(1))));
    }

    private UUID insertToken(UUID targetId, Instant expiresAt) {
        return insertToken(targetId, expiresAt, "REVIEW");
    }

    private UUID insertToken(UUID targetId, Instant expiresAt, String targetType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO moderation_action_tokens (id, target_type, target_id, action, token_hash, "
                        + "expires_at) VALUES (?, ?, ?, 'REMOVE', ?, ?)",
                id, targetType, targetId, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(expiresAt));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM moderation_action_tokens WHERE id = ?",
                Integer.class, id) == 1;
    }

    private boolean revoked(UUID id) {
        return jdbcTemplate.queryForObject("SELECT revoked_at IS NOT NULL FROM moderation_action_tokens WHERE id = ?",
                Boolean.class, id);
    }
}
