package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ModerationActionTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 415 — in-app outcome rows written by {@code ModerationService.resolve}, per target type × resolution ×
 * recipient, over real HTTP and a real database. Reports are seeded with raw SQL (deterministic order, no rate limit);
 * resolution goes through the curl internal API, and the main cases are repeated through the phase-414 e-mail confirm
 * POST, which reaches the same resolve path.
 *
 * <p>Author rule under test: a review → its client; a client review / portfolio photo of a salon → the salon (owner +
 * active admins, never the salon master); of a master → that master's account; guests and profiles → nobody.
 */
@Import(TestSecurityConfig.class)
// Same property as the other moderation ITs, so this class shares their Spring context.
@TestPropertySource(properties = "app.support.email=" + ContentReportIT.SUPPORT_EMAIL)
@DisplayName("Moderation outcome in-app notifications (phase 415)")
class ModerationServiceOutcomeNotificationIT extends AbstractModerationIT {

    private static final String ACTIONS = "/api/v1/moderation/actions/";

    @Autowired
    private ModerationActionTokenService actionTokenService;

    /** How a test resolves the oldest OPEN report on a target. */
    private enum Path { CURL, EMAIL }

    // ── reviews ──────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("REVIEW")
    class Reviews {

        @Test
        @DisplayName("REMOVE (curl) with 2 reporters → 2 × REPORT_CONTENT_REMOVED + CONTENT_REMOVED(REVIEW) for the client")
        void should_notifyReportersAndAuthor_when_reviewRemovedViaCurl() {
            assertReviewRemoveNotifies(Path.CURL);
        }

        @Test
        @DisplayName("REMOVE (e-mail confirm POST) → the same rows as curl")
        void should_notifyReportersAndAuthor_when_reviewRemovedViaEmail() {
            assertReviewRemoveNotifies(Path.EMAIL);
        }

        @Test
        @DisplayName("DISMISS → reporters REPORT_NO_VIOLATION, the author nothing")
        void should_notifyReportersOnly_when_reviewDismissed() {
            SalonFixture salon = createSalon();
            UUID author = createUser(unique("author"), "CLIENT", null);
            UUID reviewId = insertReview(author, salon, "Нормально");
            UUID r1 = reporter();
            UUID r2 = reporter();
            insertReport(r1, "REVIEW", reviewId);
            insertReport(r2, "REVIEW", reviewId);

            resolveOldest(Path.CURL, ReportTargetType.REVIEW, reviewId, "DISMISS");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_NO_VIOLATION", "REVIEW"));
            assertThat(rowsOf(r2)).containsExactly(row("REPORT_NO_VIOLATION", "REVIEW"));
            assertThat(rowsOf(author)).isEmpty();
        }

        @Test
        @DisplayName("RESTORE of a HIDDEN_PENDING review (e-mail) → reporters REPORT_NO_VIOLATION, the author nothing")
        void should_notifyNoViolation_when_hiddenReviewRestored() {
            SalonFixture salon = createSalon();
            UUID author = createUser(unique("author"), "CLIENT", null);
            UUID reviewId = insertReview(author, salon, "Прихований");
            jdbcTemplate.update("UPDATE reviews SET moderation_status = 'HIDDEN_PENDING' WHERE id = ?", reviewId);
            UUID r1 = reporter();
            insertReport(r1, "REVIEW", reviewId);

            resolveOldest(Path.EMAIL, ReportTargetType.REVIEW, reviewId, "RESTORE");

            assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
            assertThat(rowsOf(r1)).containsExactly(row("REPORT_NO_VIOLATION", "REVIEW"));
            assertThat(rowsOf(author)).isEmpty();
        }

        @Test
        @DisplayName("an independent master's review REMOVE → the client author is told, the master is not")
        void should_notifyClientAuthor_when_independentReviewRemoved() {
            IndependentMaster master = createIndependentMaster(unique("indep"));
            UUID author = createUser(unique("author"), "CLIENT", null);
            UUID reviewId = insertIndependentReview(author, master.masterId());
            UUID r1 = reporter();
            insertReport(r1, "REVIEW", reviewId);

            resolveOldest(Path.CURL, ReportTargetType.REVIEW, reviewId, "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_CONTENT_REMOVED", "REVIEW"));
            assertThat(rowsOf(author)).containsExactly(row("CONTENT_REMOVED", "REVIEW"));
            assertThat(rowsOf(master.userId())).isEmpty();
        }

        @Test
        @DisplayName("a guest-written review REMOVE → reporters only (no author account)")
        void should_notifyReportersOnly_when_guestReviewRemoved() {
            SalonFixture salon = createSalon();
            UUID bookingId = insertGuestBooking(salon, "LINK", "+380501112233");
            UUID reviewId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, source, "
                            + "guest_display_name, created_at, updated_at) "
                            + "VALUES (?, ?, NULL, ?, ?, 1, 'Гість', 'GUEST_LINK', 'Марія Ш.', NOW(), NOW())",
                    reviewId, bookingId, salon.masterId(), salon.salonId());
            UUID r1 = reporter();
            insertReport(r1, "REVIEW", reviewId);

            resolveOldest(Path.CURL, ReportTargetType.REVIEW, reviewId, "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_CONTENT_REMOVED", "REVIEW"));
            assertThat(countRowsOfType("CONTENT_REMOVED", reviewIdBatch(r1))).isZero();
        }

        private void assertReviewRemoveNotifies(Path path) {
            SalonFixture salon = createSalon();
            UUID author = createUser(unique("author"), "CLIENT", null);
            UUID reviewId = insertReview(author, salon, "Грубо");
            UUID r1 = reporter();
            UUID r2 = reporter();
            insertReport(r1, "REVIEW", reviewId);
            insertReport(r2, "REVIEW", reviewId);

            resolveOldest(path, ReportTargetType.REVIEW, reviewId, "REMOVE");

            assertThat(moderationStatus(reviewId)).isEqualTo("REMOVED");
            assertThat(rowsOf(r1)).containsExactly(row("REPORT_CONTENT_REMOVED", "REVIEW"));
            assertThat(rowsOf(r2)).containsExactly(row("REPORT_CONTENT_REMOVED", "REVIEW"));
            assertThat(rowsOf(author)).containsExactly(row("CONTENT_REMOVED", "REVIEW"));
            assertThat(rowsOf(ownerIdOf(salon))).as("the reviewed provider is not the author").isEmpty();
        }
    }

    // ── client reviews ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CLIENT_REVIEW")
    class ClientReviews {

        @Test
        @DisplayName("salon client review REMOVE → owner + admin get CONTENT_REMOVED(CLIENT_REVIEW); the salon master none")
        void should_notifySalonOwnerAndAdmin_when_salonClientReviewRemoved() {
            SalonFixture salon = createSalon();
            UUID admin = createUser(unique("admin"), "SALON_ADMIN", salon.salonId());
            UUID client = createUser(unique("client"), "CLIENT", null);
            UUID clientReviewId = insertClientReview(insertCompletedBooking(client, salon), client, salon);
            UUID r1 = reporter();
            insertReport(r1, "CLIENT_REVIEW", clientReviewId);

            resolveOldest(Path.CURL, ReportTargetType.CLIENT_REVIEW, clientReviewId, "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_CONTENT_REMOVED", "CLIENT_REVIEW"));
            assertThat(rowsOf(ownerIdOf(salon))).containsExactly(row("CONTENT_REMOVED", "CLIENT_REVIEW"));
            assertThat(rowsOf(admin)).containsExactly(row("CONTENT_REMOVED", "CLIENT_REVIEW"));
            assertThat(rowsOf(userIdOf(salon.masterEmail()))).isEmpty();
            assertThat(rowsOf(client)).as("the reviewed client is not the author").isEmpty();
        }

        @Test
        @DisplayName("independent master's client review REMOVE (e-mail) → that master gets CONTENT_REMOVED(CLIENT_REVIEW)")
        void should_notifyIndependentMaster_when_clientReviewRemovedViaEmail() {
            IndependentMaster master = createIndependentMaster(unique("indep"));
            UUID client = createUser(unique("client"), "CLIENT", null);
            UUID bookingId = insertIndependentBooking(client, master.masterId());
            UUID clientReviewId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, salon_id, rating, "
                            + "comment, created_at, updated_at) VALUES (?, ?, ?, ?, NULL, 2, 'Пізно', NOW(), NOW())",
                    clientReviewId, bookingId, client, master.masterId());
            UUID r1 = reporter();
            insertReport(r1, "CLIENT_REVIEW", clientReviewId);

            resolveOldest(Path.EMAIL, ReportTargetType.CLIENT_REVIEW, clientReviewId, "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_CONTENT_REMOVED", "CLIENT_REVIEW"));
            assertThat(rowsOf(master.userId())).containsExactly(row("CONTENT_REMOVED", "CLIENT_REVIEW"));
        }
    }

    // ── portfolio photos ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PORTFOLIO_PHOTO")
    class Photos {

        @Test
        @DisplayName("salon portfolio photo REMOVE (e-mail) → owner + active admin get CONTENT_REMOVED(PORTFOLIO_PHOTO); "
                + "an inactive admin, another salon's admin and the salon master none")
        void should_notifySalonOwnerAndAdmin_when_salonPhotoRemoved() {
            SalonFixture salon = createSalon();
            UUID admin = createUser(unique("admin"), "SALON_ADMIN", salon.salonId());
            UUID inactiveAdmin = createUser(unique("admin-off"), "SALON_ADMIN", salon.salonId());
            jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", inactiveAdmin);
            UUID foreignAdmin = createUser(unique("admin-x"), "SALON_ADMIN", createSalon().salonId());
            UUID photoId = insertSalonPortfolioPhoto(salon.salonId());
            UUID r1 = reporter();
            insertReport(r1, "PORTFOLIO_PHOTO", photoId);

            resolveOldest(Path.EMAIL, ReportTargetType.PORTFOLIO_PHOTO, photoId, "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_CONTENT_REMOVED", "PORTFOLIO_PHOTO"));
            assertThat(rowsOf(ownerIdOf(salon))).containsExactly(row("CONTENT_REMOVED", "PORTFOLIO_PHOTO"));
            assertThat(rowsOf(admin)).containsExactly(row("CONTENT_REMOVED", "PORTFOLIO_PHOTO"));
            assertThat(rowsOf(inactiveAdmin)).isEmpty();
            assertThat(rowsOf(foreignAdmin)).isEmpty();
            assertThat(rowsOf(userIdOf(salon.masterEmail()))).isEmpty();
        }

        @Test
        @DisplayName("salon master's own portfolio photo REMOVE → that salon master is the author, not the owner")
        void should_notifySalonMaster_when_masterPhotoRemoved() {
            SalonFixture salon = createSalon();
            UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
            UUID r1 = reporter();
            insertReport(r1, "PORTFOLIO_PHOTO", photoId);

            resolveOldest(Path.CURL, ReportTargetType.PORTFOLIO_PHOTO, photoId, "REMOVE");

            assertThat(rowsOf(userIdOf(salon.masterEmail()))).containsExactly(row("CONTENT_REMOVED", "PORTFOLIO_PHOTO"));
            assertThat(rowsOf(ownerIdOf(salon))).isEmpty();
        }

        @Test
        @DisplayName("independent master photo REMOVE → that master gets CONTENT_REMOVED(PORTFOLIO_PHOTO)")
        void should_notifyIndependentMaster_when_photoRemoved() {
            IndependentMaster master = createIndependentMaster(unique("indep"));
            UUID photoId = insertMasterPortfolioPhoto(master.masterId());
            UUID r1 = reporter();
            insertReport(r1, "PORTFOLIO_PHOTO", photoId);

            resolveOldest(Path.CURL, ReportTargetType.PORTFOLIO_PHOTO, photoId, "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_CONTENT_REMOVED", "PORTFOLIO_PHOTO"));
            assertThat(rowsOf(master.userId())).containsExactly(row("CONTENT_REMOVED", "PORTFOLIO_PHOTO"));
        }

        @Test
        @DisplayName("photo DISMISS → reporter REPORT_NO_VIOLATION, the author nothing")
        void should_notifyReporterOnly_when_photoDismissed() {
            IndependentMaster master = createIndependentMaster(unique("indep"));
            UUID photoId = insertMasterPortfolioPhoto(master.masterId());
            UUID r1 = reporter();
            insertReport(r1, "PORTFOLIO_PHOTO", photoId);

            resolveOldest(Path.CURL, ReportTargetType.PORTFOLIO_PHOTO, photoId, "DISMISS");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_NO_VIOLATION", "PORTFOLIO_PHOTO"));
            assertThat(rowsOf(master.userId())).isEmpty();
        }
    }

    // ── profiles ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("profiles")
    class Profiles {

        @Test
        @DisplayName("MASTER_PROFILE REMOVE (ACTIONED) → reporter REPORT_REVIEWED, nobody else")
        void should_notifyReviewed_when_masterProfileActioned() {
            IndependentMaster master = createIndependentMaster(unique("indep"));
            UUID r1 = reporter();
            insertReport(r1, "MASTER_PROFILE", master.masterId());

            resolveOldest(Path.CURL, ReportTargetType.MASTER_PROFILE, master.masterId(), "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_REVIEWED", "MASTER_PROFILE"));
            assertThat(rowsOf(master.userId())).isEmpty();
        }

        @Test
        @DisplayName("SALON_PROFILE REMOVE (e-mail, ACTIONED) → reporter REPORT_REVIEWED, owner nothing")
        void should_notifyReviewed_when_salonProfileActioned() {
            SalonFixture salon = createSalon();
            UUID r1 = reporter();
            insertReport(r1, "SALON_PROFILE", salon.salonId());

            resolveOldest(Path.EMAIL, ReportTargetType.SALON_PROFILE, salon.salonId(), "REMOVE");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_REVIEWED", "SALON_PROFILE"));
            assertThat(rowsOf(ownerIdOf(salon))).isEmpty();
        }

        @Test
        @DisplayName("CLIENT_PROFILE DISMISS → reporter REPORT_NO_VIOLATION, the client nothing")
        void should_notifyNoViolation_when_clientProfileDismissed() {
            UUID client = createUser(unique("client"), "CLIENT", null);
            UUID r1 = reporter();
            insertReport(r1, "CLIENT_PROFILE", client);

            resolveOldest(Path.CURL, ReportTargetType.CLIENT_PROFILE, client, "DISMISS");

            assertThat(rowsOf(r1)).containsExactly(row("REPORT_NO_VIOLATION", "CLIENT_PROFILE"));
            assertThat(rowsOf(client)).isEmpty();
        }
    }

    // ── recipients, idempotency, rollback, feed API ──────────────────────────────────────────

    @Nested
    @DisplayName("recipients and feed")
    class RecipientsAndFeed {

        @Test
        @DisplayName("a deleted reporter (reporter_user_id NULL) is skipped; the live reporter still gets one row")
        void should_skipDeletedReporter_when_resolved() {
            SalonFixture salon = createSalon();
            UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Текст");
            UUID gone = reporter();
            UUID live = reporter();
            UUID goneReport = insertReport(gone, "REVIEW", reviewId);
            insertReport(live, "REVIEW", reviewId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", gone);

            ResponseEntity<String> resp = resolve(goneReport, "DISMISS");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(rowsOf(live)).containsExactly(row("REPORT_NO_VIOLATION", "REVIEW"));
            assertThat(countRowsOfType("REPORT_NO_VIOLATION", "REPORT_NO_VIOLATION:" + goneReport)).isEqualTo(1);
        }

        @Test
        @DisplayName("a second resolve of an already-resolved report → 409 and no new row (rolled back)")
        void should_writeNothing_when_resolveConflicts() {
            SalonFixture salon = createSalon();
            UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Текст");
            UUID r1 = reporter();
            UUID reportId = insertReport(r1, "REVIEW", reviewId);
            assertThat(resolve(reportId, "DISMISS").getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> again = resolve(reportId, "REMOVE");

            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(rowsOf(r1)).containsExactly(row("REPORT_NO_VIOLATION", "REVIEW"));
            assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
        }

        @Test
        @DisplayName("GET /notifications: NONE target, params.contentKind only, no UGC; unread count increments")
        void should_serialiseNoneTargetAndContentKind_when_feedRead() throws Exception {
            SalonFixture salon = createSalon();
            String authorEmail = unique("author");
            UUID author = createUser(authorEmail, "CLIENT", null);
            UUID reviewId = insertReview(author, salon, "СЕКРЕТНИЙ-ТЕКСТ-ВІДГУКУ");
            String reporterEmail = unique("reporter");
            UUID r1 = createUser(reporterEmail, "CLIENT", null);
            jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, "
                    + "comment, created_at, updated_at) VALUES (?, ?, 'REVIEW', ?, 'OFFENSIVE', 'СКАРГА-КОМЕНТАР', "
                    + "NOW(), NOW())", UUID.randomUUID(), r1, reviewId);
            String reporterToken = tokenFor(reporterEmail);
            int unreadBefore = unread(reporterToken);

            resolveOldest(Path.CURL, ReportTargetType.REVIEW, reviewId, "REMOVE");

            ResponseEntity<String> feed = exchange(HttpMethod.GET, "/api/v1/notifications", reporterToken);
            JsonNode item = objectMapper.readTree(feed.getBody()).path("data").get(0);
            assertThat(item.path("type").asText()).isEqualTo("REPORT_CONTENT_REMOVED");
            assertThat(item.path("target").path("kind").asText()).isEqualTo("NONE");
            assertThat(item.path("target").path("bookingId").isNull()).isTrue();
            assertThat(item.path("target").path("salonId").isNull()).isTrue();
            assertThat(item.path("params").path("contentKind").asText()).isEqualTo("REVIEW");
            assertThat(feed.getBody()).doesNotContain("СЕКРЕТНИЙ", "СКАРГА", "checked by support",
                    reviewId.toString(), salon.salonId().toString());
            assertThat(unread(reporterToken)).isEqualTo(unreadBefore + 1);
            JsonNode authorItem = objectMapper.readTree(exchange(HttpMethod.GET, "/api/v1/notifications",
                    tokenFor(authorEmail)).getBody()).path("data").get(0);
            assertThat(authorItem.path("type").asText()).isEqualTo("CONTENT_REMOVED");
            assertThat(authorItem.path("params").path("contentKind").asText()).isEqualTo("REVIEW");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private UUID reporter() {
        return createUser(unique("reporter"), "CLIENT", null);
    }

    /** A raw OPEN report, each one a millisecond later than the previous on its target (deterministic oldest). */
    private UUID insertReport(UUID reporterId, String targetType, UUID targetId) {
        UUID id = UUID.randomUUID();
        int existing = countReportsOnTarget(targetType, targetId);
        jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'OFFENSIVE', "
                        + "NOW() - interval '1 hour' + (? * interval '1 millisecond'), NOW())",
                id, reporterId, targetType, targetId, existing);
        return id;
    }

    private UUID oldestOpenReport(UUID targetId) {
        return jdbcTemplate.queryForObject("SELECT id FROM content_reports WHERE target_id = ? AND status = 'OPEN' "
                + "ORDER BY created_at, id LIMIT 1", UUID.class, targetId);
    }

    private void resolveOldest(Path path, ReportTargetType type, UUID targetId, String action) {
        if (path == Path.CURL) {
            ResponseEntity<String> resp = resolve(oldestOpenReport(targetId), action);
            assertThat(resp.getStatusCode()).as("curl resolve: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
            return;
        }
        String token = actionTokenService.mint(type, targetId, List.of(ModerationAction.valueOf(action)))
                .get(ModerationAction.valueOf(action));
        ResponseEntity<String> page = restTemplate.exchange(ACTIONS + token, HttpMethod.GET, HttpEntity.EMPTY,
                String.class);
        ResponseEntity<String> done = postConfirm(token, nonceOf(page));
        assertThat(done.getStatusCode()).as("e-mail confirm: %s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(openReports(targetId)).isZero();
    }

    private static Map.Entry<String, String> row(String type, String targetType) {
        return Map.entry(type, targetType);
    }

    private List<Map.Entry<String, String>> rowsOf(UUID recipientId) {
        return jdbcTemplate.queryForList("SELECT type, moderated_target_type FROM in_app_notification "
                        + "WHERE recipient_user_id = ? ORDER BY created_at, id", recipientId).stream()
                .map(r -> row((String) r.get("type"), (String) r.get("moderated_target_type")))
                .collect(Collectors.toList());
    }

    private int countRowsOfType(String type, String dedupKey) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE type = ? AND dedup_key = ?",
                Integer.class, type, dedupKey);
    }

    /** The dedup key the batch of {@code reporterId}'s single report would carry for CONTENT_REMOVED. */
    private String reviewIdBatch(UUID reporterId) {
        UUID reportId = jdbcTemplate.queryForObject("SELECT id FROM content_reports WHERE reporter_user_id = ?",
                UUID.class, reporterId);
        return "CONTENT_REMOVED:" + reportId;
    }

    private UUID ownerIdOf(SalonFixture salon) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private int unread(String token) {
        return data(exchange(HttpMethod.GET, "/api/v1/notifications/unread-count", token)).path("count").asInt();
    }
}
