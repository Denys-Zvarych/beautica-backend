package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.service.ContentReportRetentionJob;
import com.beautica.moderation.service.ContentReportRetentionService;
import com.beautica.review.service.RatingRecalculationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 396 — moderation end to end over HTTP against real Postgres (QA additions): auto-hide through the public
 * report endpoint and its effect on every public surface (lists, counts, cached detail pages, persisted ratings),
 * reporter eligibility by ROLE (staff holding COMPLETED bookings still never count), the burst window edge, support
 * resolve rules (D14), 404 parity for REMOVED reviews and for the author (D12), and the scheduled retention job driven
 * by a fixed {@link Clock} (D7/D15).
 */
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "app.support.email=" + ContentReportIT.SUPPORT_EMAIL)
@DisplayName("Moderation end to end over HTTP (phase 396 QA)")
class ModerationEndToEndIT extends AbstractModerationIT {

    private static final String QUEUE_URL = "/api/v1/internal/moderation/reports";

    @Autowired
    private RatingRecalculationService ratingRecalculationService;

    @Autowired
    private ContentReportRetentionService retentionService;

    // ── auto-hide → every public surface ────────────────────────────────────────

    @Nested
    @DisplayName("auto-hide over HTTP")
    class AutoHide {

        @Test
        @DisplayName("3 eligible CLIENT reports → 1★ review gone from master + salon lists, totals, CACHED master/salon "
                + "detail rating and count, persisted aggregates")
        void should_removeReviewFromEveryPublicSurface_when_threeEligibleClientsReport() throws Exception {
            SalonFixture salon = createSalon();
            UUID keptId = insertReview(createUser(unique("happy"), "CLIENT", null), salon, "Чудово", 5);
            UUID badId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано", 1);
            ratingRecalculationService.recalculateMasterRating(salon.masterId());
            ratingRecalculationService.recalculateSalonRating(salon.salonId());
            String masterDetail = "/api/v1/masters/" + salon.masterId();
            String salonDetail = "/api/v1/salons/" + salon.salonId();
            assertRating(data(okGet(masterDetail)), "3.00", 2, "precondition (and caches) master detail");
            assertRating(data(okGet(salonDetail)), "3.00", 2, "precondition (and caches) salon detail");
            assertThat(data(okGet(masterDetail + "/reviews")).path("totalElements").asInt()).as("precondition").isEqualTo(2);

            for (int i = 0; i < 3; i++) {
                assertThat(reportReview(eligibleClient(salon).email(), badId).getStatusCode())
                        .isEqualTo(HttpStatus.CREATED);
            }

            assertThat(moderationStatus(badId)).isEqualTo("HIDDEN_PENDING");
            JsonNode masterList = data(okGet(masterDetail + "/reviews"));
            JsonNode salonList = data(okGet(salonDetail + "/reviews"));
            assertThat(ids(masterList)).containsExactly(keptId);
            assertThat(masterList.path("totalElements").asInt()).isEqualTo(1);
            assertThat(ids(salonList)).containsExactly(keptId);
            assertThat(salonList.path("totalElements").asInt()).isEqualTo(1);
            assertRating(data(okGet(masterDetail)), "5.00", 1, "master detail cache evicted + recalculated");
            assertRating(data(okGet(salonDetail)), "5.00", 1, "salon detail cache evicted + recalculated");
            assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM masters WHERE id = ?", Integer.class,
                    salon.masterId())).as("persisted master count").isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM salons WHERE id = ?", Integer.class,
                    salon.salonId())).as("persisted salon count").isEqualTo(1);
        }

        @Test
        @DisplayName("staff (owner, admin, own master) WITH COMPLETED bookings, a client of ANOTHER salon and a "
                + "deleted reporter never count; the 3rd real eligible client then hides it")
        void should_countOnlyEligibleClients_when_staffHoldCompletedBookings() throws Exception {
            SalonFixture salon = createSalon();
            SalonFixture otherSalon = createSalon();
            UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
            UUID ownerId = jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class,
                    salon.salonId());
            String adminEmail = unique("admin");
            UUID adminId = createUser(adminEmail, "SALON_ADMIN", salon.salonId());
            UUID masterUserId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class,
                    salon.masterId());
            for (UUID staff : List.of(ownerId, adminId, masterUserId)) {
                insertCompletedBooking(staff, salon);
            }
            EligibleClient otherSalonClient = eligibleClient(otherSalon);
            EligibleClient leaver = eligibleClient(salon);
            List<String> ineligible = List.of(salon.ownerEmail(), adminEmail, salon.masterEmail(),
                    otherSalonClient.email());

            reportReview(leaver.email(), reviewId);
            jdbcTemplate.update("UPDATE content_reports SET reporter_user_id = NULL WHERE reporter_user_id = ?",
                    leaver.userId());
            List<HttpStatus> statuses = new ArrayList<>();
            for (String email : ineligible) {
                statuses.add(HttpStatus.valueOf(reportReview(email, reviewId).getStatusCode().value()));
            }
            // Age the 5 ineligible reports out of the 10-min burst window: 8 reports on one provider inside the
            // window would trip the D11 burst guard and hold everything, which is not what this test isolates.
            jdbcTemplate.update("UPDATE content_reports SET created_at = NOW() - interval '11 minutes' "
                    + "WHERE target_id = ?", reviewId);
            reportReview(eligibleClient(salon).email(), reviewId);
            reportReview(eligibleClient(salon).email(), reviewId);
            String afterTwoEligible = moderationStatus(reviewId);
            reportReview(eligibleClient(salon).email(), reviewId);

            assertThat(statuses).as("ineligible reports are still accepted").containsOnly(HttpStatus.CREATED);
            assertThat(countReportsOnTarget("REVIEW", reviewId)).isEqualTo(8);
            assertThat(afterTwoEligible).as("2 eligible + 4 ineligible + 1 null reporter").isEqualTo("VISIBLE");
            assertThat(moderationStatus(reviewId)).as("3rd eligible client").isEqualTo("HIDDEN_PENDING");
        }
    }

    // ── burst guard (D11) ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("burst guard over HTTP")
    class Burst {

        @Test
        @DisplayName("4 OPEN reports 11 min old + 3 eligible now → outside the 10-min window: no hold, no [BURST], "
                + "review auto-hidden")
        void should_notHold_when_earlierReportsOutsideWindow() throws Exception {
            SalonFixture salon = createSalon();
            UUID target = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
            for (int i = 0; i < 4; i++) {
                UUID other = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Відгук " + i);
                insertBackdatedReport(eligibleClient(salon).userId(), other, Duration.ofMinutes(11));
            }

            for (int i = 0; i < 3; i++) {
                reportReview(eligibleClient(salon).email(), target);
            }

            verify(emailService, never()).sendContentReportBurstEmail(anyString(), any(), anyString(), any(),
                    anyString(), any());
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports WHERE burst_hold",
                    Integer.class)).isZero();
            assertThat(moderationStatus(target)).isEqualTo("HIDDEN_PENDING");
        }

        @Test
        @DisplayName("a burst on salon A never holds salon B's reports, and B's review still auto-hides")
        void should_scopeBurstToProvider_when_otherProviderReported() throws Exception {
            SalonFixture burstSalon = createSalon();
            SalonFixture quietSalon = createSalon();
            for (int i = 0; i < 5; i++) {
                UUID review = insertReview(createUser(unique("author"), "CLIENT", null), burstSalon, "Відгук " + i);
                reportReview(eligibleClient(burstSalon).email(), review);
            }
            UUID quietReview = insertReview(createUser(unique("author"), "CLIENT", null), quietSalon, "Погано");

            for (int i = 0; i < 3; i++) {
                reportReview(eligibleClient(quietSalon).email(), quietReview);
            }

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports WHERE burst_hold",
                    Integer.class)).as("salon A's wave").isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports WHERE burst_hold "
                    + "AND target_id = ?", Integer.class, quietReview)).isZero();
            assertThat(moderationStatus(quietReview)).isEqualTo("HIDDEN_PENDING");
        }

        @Test
        @DisplayName("a review hidden BEFORE the wave reaches 5 stays HIDDEN_PENDING; the 5th report is a [BURST]")
        void should_keepEarlierHide_when_burstFollows() throws Exception {
            SalonFixture salon = createSalon();
            UUID hidden = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
            for (int i = 0; i < 3; i++) {
                reportReview(eligibleClient(salon).email(), hidden);
            }
            assertThat(moderationStatus(hidden)).as("precondition").isEqualTo("HIDDEN_PENDING");
            UUID other = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Інший");

            reportReview(eligibleClient(salon).email(), other);
            verify(emailService, never()).sendContentReportBurstEmail(anyString(), any(), anyString(), any(),
                    anyString(), any());
            reportReview(eligibleClient(salon).email(), other);

            verify(emailService, times(1)).sendContentReportBurstEmail(anyString(), any(), eq("REVIEW"), eq(other),
                    eq("OFFENSIVE"), any());
            assertThat(moderationStatus(hidden)).isEqualTo("HIDDEN_PENDING");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports WHERE burst_hold",
                    Integer.class)).isEqualTo(5);
        }
    }

    // ── support resolve (D4 / D14) ───────────────────────────────────────────────

    @Nested
    @DisplayName("support resolve via X-Internal-Key")
    class Resolve {

        @Test
        @DisplayName("REMOVE is terminal: a later RESTORE on a stray OPEN report → 409 (audit-fix D21), the review "
                + "stays REMOVED and 404; REMOVE then closes the stray report")
        void should_keepRemoved_when_restoreAfterRemove() throws Exception {
            SalonFixture salon = createSalon();
            UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
            EligibleClient reporter = eligibleClient(salon);
            reportReview(reporter.email(), reviewId);
            assertThat(resolve(reportIdOn(reporter.userId(), reviewId), "REMOVE").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            UUID stray = insertBackdatedReport(eligibleClient(salon).userId(), reviewId, Duration.ofMinutes(1));

            ResponseEntity<String> restore = resolve(stray, "RESTORE");
            ResponseEntity<String> remove = resolve(stray, "REMOVE");

            assertThat(restore.getStatusCode()).as("body: %s", restore.getBody()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(data(remove).path("resolution").asText()).isEqualTo("REMOVED");
            assertThat(moderationStatus(reviewId)).isEqualTo("REMOVED");
            assertThat(getAnonymous("/api/v1/reviews/" + reviewId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ids(data(okGet("/api/v1/masters/" + salon.masterId() + "/reviews")))).isEmpty();
        }

        @Test
        @DisplayName("resolve closes every OPEN report on its target only: a sibling report → 409 afterwards, another "
                + "target's report stays OPEN")
        void should_resolveWholeTargetOnly_when_dismissing() throws Exception {
            SalonFixture salon = createSalon();
            UUID reviewA = insertReview(createUser(unique("author"), "CLIENT", null), salon, "A");
            UUID reviewB = insertReview(createUser(unique("author"), "CLIENT", null), salon, "B");
            EligibleClient first = eligibleClient(salon);
            EligibleClient second = eligibleClient(salon);
            reportReview(first.email(), reviewA);
            reportReview(second.email(), reviewA);
            reportReview(first.email(), reviewB);

            ResponseEntity<String> dismissed = resolve(reportIdOn(first.userId(), reviewA), "DISMISS");
            ResponseEntity<String> sibling = resolve(reportIdOn(second.userId(), reviewA), "REMOVE");

            assertThat(data(dismissed).path("resolvedReports").asInt()).isEqualTo(2);
            assertThat(sibling.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(moderationStatus(reviewA)).as("409 changed nothing").isEqualTo("VISIBLE");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM content_reports WHERE id = ?", String.class,
                    reportIdOn(first.userId(), reviewB))).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("RESTORE on a portfolio photo → 400; the report stays OPEN and the photo stays listed")
        void should_return400_when_restoreOnPhoto() throws Exception {
            SalonFixture salon = createSalon();
            UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
            String email = unique("client");
            UUID clientId = createUser(email, "CLIENT", null);
            postReport(tokenFor(email), reportBody("PORTFOLIO_PHOTO", photoId, "SPAM", null));
            UUID reportId = reportIdOn(clientId, photoId);

            ResponseEntity<String> resp = resolve(reportId, "RESTORE");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM content_reports WHERE id = ?", String.class,
                    reportId)).isEqualTo("OPEN");
            assertThat(getAnonymous("/api/v1/masters/" + salon.masterId() + "/portfolio").getBody())
                    .contains(photoId.toString());
        }

        @Test
        @DisplayName("review row deleted before support acts → REMOVE resolves as TARGET_GONE with target_gone_at")
        void should_resolveTargetGone_when_reviewDeletedBeforeResolve() throws Exception {
            SalonFixture salon = createSalon();
            UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
            EligibleClient reporter = eligibleClient(salon);
            reportReview(reporter.email(), reviewId);
            UUID reportId = reportIdOn(reporter.userId(), reviewId);
            jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", reviewId);

            ResponseEntity<String> resp = resolve(reportId, "REMOVE");

            assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(data(resp).path("resolution").asText()).isEqualTo("TARGET_GONE");
            assertThat(jdbcTemplate.queryForObject("SELECT target_gone_at FROM content_reports WHERE id = ?",
                    Timestamp.class, reportId)).isNotNull();
        }

        @Test
        @DisplayName("note boundary: 500 chars accepted and stored, 501 → 400 with the report still OPEN")
        void should_enforceNoteLength_when_resolving() throws Exception {
            SalonFixture salon = createSalon();
            String email = unique("client");
            UUID clientId = createUser(email, "CLIENT", null);
            postReport(tokenFor(email), reportBody("SALON_PROFILE", salon.salonId(), "SPAM", null));
            UUID reportId = reportIdOn(clientId, salon.salonId());
            String path = QUEUE_URL + "/" + reportId + "/resolve";

            ResponseEntity<String> tooLong = internal(HttpMethod.POST, path, noteBody("n".repeat(501)), INTERNAL_KEY);
            String statusAfterReject = jdbcTemplate.queryForObject(
                    "SELECT status FROM content_reports WHERE id = ?", String.class, reportId);
            ResponseEntity<String> atMax = internal(HttpMethod.POST, path, noteBody("n".repeat(500)), INTERNAL_KEY);

            assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(statusAfterReject).isEqualTo("OPEN");
            assertThat(atMax.getStatusCode()).as("body: %s", atMax.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(jdbcTemplate.queryForObject("SELECT resolution_note FROM content_reports WHERE id = ?",
                    String.class, reportId)).hasSize(500);
        }

        @Test
        @DisplayName("RESOLVED queue is newest RESOLUTION first (D17): a report filed earlier but resolved later "
                + "comes first")
        void should_listResolvedNewestFirst_when_statusResolved() throws Exception {
            SalonFixture salon = createSalon();
            // Filed order and resolved order disagree, so a created_at DESC sort would invert the list.
            UUID olderReport = insertResolvedReport(salon.salonId(), Duration.ofHours(1), Duration.ofMinutes(30));
            UUID newerReport = insertResolvedReport(salon.salonId(), Duration.ofHours(3), Duration.ofMinutes(5));

            ResponseEntity<String> resp = internal(HttpMethod.GET, QUEUE_URL + "?status=RESOLVED", null, INTERNAL_KEY);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<String> order = new ArrayList<>();
            data(resp).path("data").forEach(row -> order.add(row.path("id").asText()));
            assertThat(order).containsExactly(newerReport.toString(), olderReport.toString());
        }
    }

    // ── 404 parity (D12) ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("hidden / removed review 404 parity")
    class NotFoundParity {

        @Test
        @DisplayName("REMOVED review: anonymous GET /reviews/{id} 404 byte-identical to an unknown id; the AUTHOR's "
                + "authenticated GET too; the author still has it in /reviews/me")
        void should_return404ToEveryone_when_reviewRemoved() throws Exception {
            SalonFixture salon = createSalon();
            String authorEmail = unique("author");
            UUID reviewId = insertReview(createUser(authorEmail, "CLIENT", null), salon, "Погано");
            String authorToken = tokenFor(authorEmail);
            assertThat(getAuthed("/api/v1/reviews/" + reviewId, authorToken).getStatusCode())
                    .as("precondition: served (and cached) while visible").isEqualTo(HttpStatus.OK);
            EligibleClient reporter = eligibleClient(salon);
            reportReview(reporter.email(), reviewId);
            resolve(reportIdOn(reporter.userId(), reviewId), "REMOVE");

            ResponseEntity<String> removedAnon = getAnonymous("/api/v1/reviews/" + reviewId);
            ResponseEntity<String> unknownAnon = getAnonymous("/api/v1/reviews/" + UUID.randomUUID());
            ResponseEntity<String> removedAuthor = getAuthed("/api/v1/reviews/" + reviewId, authorToken);
            ResponseEntity<String> mine = getAuthed("/api/v1/reviews/me", authorToken);

            assertThat(removedAnon.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(removedAnon)).isEqualTo(withoutRequestId(unknownAnon));
            assertThat(removedAuthor.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(removedAuthor)).isEqualTo(withoutRequestId(unknownAnon));
            assertThat(ids(data(mine))).containsExactly(reviewId);
        }
    }

    // ── retention job with a fixed Clock (D7 / D15) ─────────────────────────────

    @Nested
    @DisplayName("retention job with a fixed Clock")
    class Retention {

        @Test
        @DisplayName("REMOVE over HTTP stamps target_gone_at; the job at +364 d keeps the reports, at +366 d purges "
                + "them; a live target's report survives both runs")
        void should_purgeOnlyAfterOneYear_when_jobRunsWithFixedClock() throws Exception {
            SalonFixture salon = createSalon();
            UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
            EligibleClient reporter = eligibleClient(salon);
            reportReview(reporter.email(), reviewId);
            reportReview(eligibleClient(salon).email(), reviewId);
            UUID liveReport = insertResolvedReport(salon.salonId(), Duration.ofDays(30));
            resolve(reportIdOn(reporter.userId(), reviewId), "REMOVE");
            Instant goneAt = jdbcTemplate.queryForObject(
                    "SELECT MAX(target_gone_at) FROM content_reports WHERE target_id = ?", Timestamp.class, reviewId)
                    .toInstant();

            jobAt(goneAt.plus(Duration.ofDays(364))).run();
            int keptBeforeYear = countReportsOnTarget("REVIEW", reviewId);
            jobAt(goneAt.plus(Duration.ofDays(366))).run();

            assertThat(keptBeforeYear).isEqualTo(2);
            assertThat(countReportsOnTarget("REVIEW", reviewId)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports WHERE id = ?", Integer.class,
                    liveReport)).as("live salon target kept").isEqualTo(1);
        }

        private ContentReportRetentionJob jobAt(Instant instant) {
            return new ContentReportRetentionJob(retentionService, Clock.fixed(instant, ZoneOffset.UTC));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> okGet(String path) {
        ResponseEntity<String> resp = getAnonymous(path);
        assertThat(resp.getStatusCode()).as("GET %s body: %s", path, resp.getBody()).isEqualTo(HttpStatus.OK);
        return resp;
    }

    private ResponseEntity<String> getAuthed(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("data").forEach(row -> ids.add(UUID.fromString(row.path("id").asText())));
        return ids;
    }

    private static void assertRating(JsonNode detail, String avg, int count, String what) {
        assertThat(new BigDecimal(detail.path("avgRating").asText())).as("%s avgRating", what)
                .isEqualByComparingTo(avg);
        assertThat(detail.path("reviewCount").asInt()).as("%s reviewCount", what).isEqualTo(count);
    }

    private String noteBody(String note) {
        return objectMapper.createObjectNode().put("action", "DISMISS").put("note", note).toString();
    }

    private UUID insertBackdatedReport(UUID reporterId, UUID reviewId, Duration age) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, "
                        + "created_at, updated_at) VALUES (?, ?, 'REVIEW', ?, 'SPAM', ?, ?)",
                id, reporterId, reviewId, Timestamp.from(Instant.now().minus(age)), Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertResolvedReport(UUID salonId, Duration resolvedAgo) {
        return insertResolvedReport(salonId, resolvedAgo, resolvedAgo);
    }

    /** created_at and resolved_at set independently, so a sort on the wrong column is observable (D17). */
    private UUID insertResolvedReport(UUID salonId, Duration createdAgo, Duration resolvedAgo) {
        UUID id = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(Instant.now().minus(createdAgo));
        Timestamp resolvedAt = Timestamp.from(Instant.now().minus(resolvedAgo));
        jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, status, "
                        + "resolution, resolved_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'SALON_PROFILE', ?, 'SPAM', 'RESOLVED', 'DISMISSED', ?, ?, ?)",
                id, createUser(unique("r"), "CLIENT", null), salonId, resolvedAt, createdAt, resolvedAt);
        return id;
    }
}
