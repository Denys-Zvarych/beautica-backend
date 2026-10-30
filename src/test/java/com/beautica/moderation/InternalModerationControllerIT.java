package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.beautica.moderation.service.ContentReportRetentionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 396 D4 — {@code /api/v1/internal/moderation/**} end to end: {@code X-Internal-Key} gate, the queue, and every
 * resolve action against real Postgres. R2 is a {@code @MockBean}; the photo blob purge runs after commit on
 * {@code blobPurgeExecutor}, so it is verified with a Mockito {@code timeout}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Internal moderation API — queue + resolve (phase 396)")
class InternalModerationControllerIT extends AbstractModerationIT {

    private static final String QUEUE_URL = "/api/v1/internal/moderation/reports";

    @MockBean
    private R2StorageService r2;

    @Autowired
    private ContentReportRetentionService retentionService;

    @Autowired
    private Clock clock;

    @BeforeEach
    void stubR2() {
        when(r2.deleteFiles(any())).thenReturn(Set.of());
    }

    // ── X-Internal-Key gate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("no key / wrong key → 401 on the queue and on resolve; nothing resolved")
    void should_return401_when_internalKeyMissingOrWrong() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        postReport(tokenFor(clientEmail), reportBody("SALON_PROFILE", salon.salonId(), "SPAM", null));
        UUID reportId = reportIdOn(clientId, salon.salonId());
        String resolveBody = "{\"action\":\"DISMISS\"}";
        String resolvePath = QUEUE_URL + "/" + reportId + "/resolve";

        List<ResponseEntity<String>> responses = List.of(
                internal(HttpMethod.GET, QUEUE_URL, null, null),
                internal(HttpMethod.GET, QUEUE_URL, null, "wrong-key"),
                internal(HttpMethod.POST, resolvePath, resolveBody, null),
                internal(HttpMethod.POST, resolvePath, resolveBody, "wrong-key"),
                getWithClientJwt(tokenFor(clientEmail)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM content_reports WHERE id = ?", String.class, reportId))
                .isEqualTo("OPEN");
    }

    // ── queue ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("queue pages OPEN reports oldest first with a target snapshot (review text, rating, status)")
    void should_pageOpenReportsOldestFirst_withSnapshot() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Грубий текст", 2);
        List<UUID> reporters = List.of(eligibleClient(salon).userId(), eligibleClient(salon).userId(),
                eligibleClient(salon).userId());
        for (int i = 0; i < reporters.size(); i++) {
            jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, "
                            + "created_at, updated_at) VALUES (?, ?, 'REVIEW', ?, 'SPAM', NOW() - (? * interval '1 hour'), NOW())",
                    UUID.randomUUID(), reporters.get(i), reviewId, 3 - i);
        }

        ResponseEntity<String> first = internal(HttpMethod.GET, QUEUE_URL + "?status=OPEN&page=0&size=2", null,
                INTERNAL_KEY);
        ResponseEntity<String> second = internal(HttpMethod.GET, QUEUE_URL + "?status=OPEN&page=1&size=2", null,
                INTERNAL_KEY);

        assertThat(first.getStatusCode()).as("body: %s", first.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode page = data(first);
        assertThat(page.path("totalElements").asInt()).isEqualTo(3);
        assertThat(page.path("data")).hasSize(2);
        assertThat(page.path("data").get(0).path("reporterUserId").asText()).isEqualTo(reporters.get(0).toString());
        assertThat(page.path("data").get(1).path("reporterUserId").asText()).isEqualTo(reporters.get(1).toString());
        JsonNode target = page.path("data").get(0).path("target");
        assertThat(target.path("exists").asBoolean()).isTrue();
        assertThat(target.path("text").asText()).isEqualTo("Грубий текст");
        assertThat(target.path("rating").asInt()).isEqualTo(2);
        assertThat(target.path("moderationStatus").asText()).isEqualTo("VISIBLE");
        assertThat(data(second).path("data").get(0).path("reporterUserId").asText())
                .isEqualTo(reporters.get(2).toString());
    }

    // ── resolve ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REMOVE on a portfolio photo → row deleted, R2 object purged, gone from the public portfolio, "
            + "reports REMOVED + target_gone_at")
    void should_deletePhotoAndBlob_when_removeOnPhoto() throws Exception {
        SalonFixture salon = createSalon();
        UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
        String r2Key = jdbcTemplate.queryForObject("SELECT r2_key FROM media_files WHERE id = ?", String.class, photoId);
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        postReport(tokenFor(clientEmail), reportBody("PORTFOLIO_PHOTO", photoId, "SEXUAL_CONTENT", null));
        String portfolioPath = "/api/v1/masters/" + salon.masterId() + "/portfolio";
        assertThat(getAnonymous(portfolioPath).getBody()).as("precondition: listed").contains(photoId.toString());

        ResponseEntity<String> resp = resolve(reportIdOn(clientId, photoId), "REMOVE");

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(data(resp).path("resolution").asText()).isEqualTo("REMOVED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_files WHERE id = ?", Integer.class, photoId))
                .isZero();
        verify(r2, timeout(10_000)).deleteFiles(argThat(keys -> keys.contains(r2Key)));
        assertThat(getAnonymous(portfolioPath).getBody()).doesNotContain(photoId.toString());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, resolution, resolution_note, target_gone_at FROM content_reports WHERE target_id = ?",
                photoId);
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(row.get("resolution")).isEqualTo("REMOVED");
        assertThat(row.get("resolution_note")).isEqualTo("checked by support");
        assertThat(row.get("target_gone_at")).isNotNull();
    }

    @Test
    @DisplayName("RESTORE after auto-hide → VISIBLE; old reports cannot re-hide it, new eligible reports count from "
            + "zero (3 new → hidden again)")
    void should_restoreAndRecountFromZero_when_restoredAfterAutoHide() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
        List<EligibleClient> first = List.of(eligibleClient(salon), eligibleClient(salon), eligibleClient(salon));
        for (EligibleClient c : first) {
            reportReview(c.email(), reviewId);
        }
        assertThat(moderationStatus(reviewId)).as("precondition").isEqualTo("HIDDEN_PENDING");

        ResponseEntity<String> restored = resolve(reportIdOn(first.get(0).userId(), reviewId), "RESTORE");
        // Outside the 24 h per-provider auto-hide cap (D16), so the recount — not the cap — decides the re-hide.
        jdbcTemplate.update("UPDATE reviews SET auto_hidden_at = auto_hidden_at - interval '25 hours' WHERE id = ?",
                reviewId);
        ResponseEntity<String> repeat = reportReview(first.get(1).email(), reviewId);
        reportReview(eligibleClient(salon).email(), reviewId);
        reportReview(eligibleClient(salon).email(), reviewId);
        String afterTwoNew = moderationStatus(reviewId);
        reportReview(eligibleClient(salon).email(), reviewId);

        assertThat(restored.getStatusCode()).as("body: %s", restored.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(data(restored).path("resolution").asText()).isEqualTo("RESTORED");
        assertThat(data(restored).path("resolvedReports").asInt()).isEqualTo(3);
        assertThat(repeat.getStatusCode()).as("old reporter: idempotent repeat of the RESOLVED report").isEqualTo(HttpStatus.OK);
        assertThat(afterTwoNew).as("2 new + 3 resolved old reports").isEqualTo("VISIBLE");
        assertThat(moderationStatus(reviewId)).as("3 new eligible reports").isEqualTo("HIDDEN_PENDING");
    }

    @Test
    @DisplayName("DISMISS on an auto-hidden review → VISIBLE again, reports DISMISSED")
    void should_unhideReview_when_dismissed() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
        EligibleClient reporter = eligibleClient(salon);
        reportReview(reporter.email(), reviewId);
        reportReview(eligibleClient(salon).email(), reviewId);
        reportReview(eligibleClient(salon).email(), reviewId);

        ResponseEntity<String> resp = resolve(reportIdOn(reporter.userId(), reviewId), "DISMISS");

        assertThat(data(resp).path("resolution").asText()).isEqualTo("DISMISSED");
        assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
        assertThat(getAnonymous("/api/v1/reviews/" + reviewId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("REMOVE on a review → REMOVED, GET /reviews/{id} 404, target_gone_at on every report")
    void should_removeReview_when_removeOnReview() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
        EligibleClient reporter = eligibleClient(salon);
        reportReview(reporter.email(), reviewId);

        ResponseEntity<String> resp = resolve(reportIdOn(reporter.userId(), reviewId), "REMOVE");

        assertThat(data(resp).path("resolution").asText()).isEqualTo("REMOVED");
        assertThat(moderationStatus(reviewId)).isEqualTo("REMOVED");
        assertThat(getAnonymous("/api/v1/reviews/" + reviewId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE target_id = ? AND target_gone_at IS NULL",
                Integer.class, reviewId)).isZero();
    }

    @Test
    @DisplayName("RESTORE / DISMISS on a REMOVED review (a report that raced the takedown) → 409, report stays OPEN; "
            + "the retention sweep then closes it TARGET_GONE with target_gone_at, so it is not kept forever")
    void should_conflictThenSweepGone_when_restoreOrDismissOnRemovedReview() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
        EligibleClient reporter = eligibleClient(salon);
        reportReview(reporter.email(), reviewId);
        UUID reportId = reportIdOn(reporter.userId(), reviewId);
        jdbcTemplate.update("UPDATE reviews SET moderation_status = 'REMOVED' WHERE id = ?", reviewId);

        ResponseEntity<String> restore = resolve(reportId, "RESTORE");
        ResponseEntity<String> dismiss = resolve(reportId, "DISMISS");
        String statusAfterConflicts = jdbcTemplate.queryForObject(
                "SELECT status FROM content_reports WHERE id = ?", String.class, reportId);
        retentionService.sweep(clock.instant());

        assertThat(List.of(restore, dismiss)).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.CONFLICT);
        assertThat(statusAfterConflicts).isEqualTo("OPEN");
        assertThat(moderationStatus(reviewId)).as("REMOVED is final").isEqualTo("REMOVED");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, resolution, target_gone_at FROM content_reports WHERE id = ?", reportId);
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(row.get("resolution")).isEqualTo("TARGET_GONE");
        assertThat(row.get("target_gone_at")).isNotNull();
    }

    @Test
    @DisplayName("profile REMOVE → ACTIONED; RESTORE on a profile → 400; resolving it again → 409; unknown id → 404")
    void should_enforceProfileRules_when_resolvingProfileReport() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        postReport(tokenFor(clientEmail), reportBody("SALON_PROFILE", salon.salonId(), "FALSE_INFO", null));
        UUID reportId = reportIdOn(clientId, salon.salonId());

        ResponseEntity<String> restore = resolve(reportId, "RESTORE");
        ResponseEntity<String> remove = resolve(reportId, "REMOVE");
        ResponseEntity<String> again = resolve(reportId, "DISMISS");
        ResponseEntity<String> unknown = resolve(UUID.randomUUID(), "DISMISS");

        assertThat(restore.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(data(remove).path("resolution").asText()).isEqualTo("ACTIONED");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM salons WHERE id = ?", Boolean.class,
                salon.salonId())).as("profile untouched").isTrue();
    }

    @Test
    @DisplayName("invalid action / control characters in the note → 400")
    void should_return400_when_bodyInvalid() {
        UUID reportId = UUID.randomUUID();
        String path = QUEUE_URL + "/" + reportId + "/resolve";

        ResponseEntity<String> badAction = internal(HttpMethod.POST, path, "{\"action\":\"NUKE\"}", INTERNAL_KEY);
        ResponseEntity<String> noAction = internal(HttpMethod.POST, path, "{}", INTERNAL_KEY);
        ResponseEntity<String> badNote = internal(HttpMethod.POST, path,
                "{\"action\":\"DISMISS\",\"note\":\"a\\u202Eb\"}", INTERNAL_KEY);

        assertThat(List.of(badAction, noAction, badNote)).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.BAD_REQUEST);
        assertThat(badAction.getBody()).doesNotContain("REMOVE", "RESTORE");
    }

    private ResponseEntity<String> getWithClientJwt(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(QUEUE_URL, HttpMethod.GET, new HttpEntity<>(headers),
                String.class);
    }
}
