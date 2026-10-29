package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ModerationActionTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 415 regression net for the user's smoke report «після видалення відгуку / фото сповіщення не прийшло ні
 * майстру, ні клієнту». Unlike {@link ModerationServiceOutcomeNotificationIT} (raw-SQL reports, DB-row assertions),
 * every step here is the real client path: reports are filed through {@code POST /api/v1/reports} with each
 * reporter's own token, support resolves through the curl internal API or the e-mail confirm POST, and every
 * recipient — and every non-recipient — reads {@code GET /api/v1/notifications} and the unread count with their
 * own token. Each feed item must carry the right {@code type}, {@code params.contentKind}, a {@code NONE} target, and
 * no UGC (review text, report comment, photo key / URL, ids, salon name).
 */
@Import(TestSecurityConfig.class)
// Same property as the other moderation ITs, so this class shares their Spring context.
@TestPropertySource(properties = "app.support.email=" + ContentReportIT.SUPPORT_EMAIL)
@DisplayName("Moderation outcome → real feed API per recipient (phase 415 regression)")
class ModerationOutcomeFeedEndToEndIT extends AbstractModerationIT {

    private static final String FEED = "/api/v1/notifications";
    private static final String UNREAD = "/api/v1/notifications/unread-count";
    private static final String ACTIONS = "/api/v1/moderation/actions/";
    private static final String REVIEW_TEXT = "ОБРАЗЛИВИЙ-ТЕКСТ-ВІДГУКУ";
    private static final String REPORT_COMMENT = "КОМЕНТАР-СКАРЖНИКА";

    @Autowired
    private ModerationActionTokenService actionTokenService;

    /** A seeded account plus its live bearer token. */
    private record Actor(UUID id, String token) {
    }

    // ── review ───────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("REVIEW removed")
    class ReviewRemoved {

        @Test
        @DisplayName("independent master + a client report the review via API, curl REMOVE → both reporters see "
                + "REPORT_CONTENT_REMOVED(REVIEW), the client author sees CONTENT_REMOVED(REVIEW) in GET /notifications")
        void should_showOutcomeInEveryRecipientsFeed_when_reviewRemovedViaCurl() throws Exception {
            String masterEmail = unique("indep");
            IndependentMaster master = createIndependentMaster(masterEmail);
            Actor author = client("author");
            UUID reviewId = insertIndependentReviewWithText(author.id(), master.masterId());
            Actor masterReporter = new Actor(master.userId(), tokenFor(masterEmail));
            Actor clientReporter = client("reporter");
            int masterUnread = unread(masterReporter);
            int clientUnread = unread(clientReporter);
            int authorUnread = unread(author);
            UUID firstReport = fileReport(masterReporter, "REVIEW", reviewId);
            fileReport(clientReporter, "REVIEW", reviewId);

            assertThat(resolve(firstReport, "REMOVE").getStatusCode()).isEqualTo(HttpStatus.OK);

            assertSingleOutcome(masterReporter, "REPORT_CONTENT_REMOVED", "REVIEW", reviewId);
            assertSingleOutcome(clientReporter, "REPORT_CONTENT_REMOVED", "REVIEW", reviewId);
            assertSingleOutcome(author, "CONTENT_REMOVED", "REVIEW", reviewId);
            assertThat(unread(masterReporter)).as("master reporter unread").isEqualTo(masterUnread + 1);
            assertThat(unread(clientReporter)).as("client reporter unread").isEqualTo(clientUnread + 1);
            assertThat(unread(author)).as("author unread").isEqualTo(authorUnread + 1);
        }

        @Test
        @DisplayName("salon review reported by a client, e-mail REMOVE → reporter + author see their item; the "
                + "salon owner and salon master (reviewed, not reporters) see nothing")
        void should_notifyReporterAndAuthorOnly_when_salonReviewRemovedViaEmail() throws Exception {
            SalonFixture salon = createSalon();
            Actor author = client("author");
            UUID reviewId = insertReview(author.id(), salon, REVIEW_TEXT);
            Actor reporter = client("reporter");
            Actor owner = actor(salon.ownerEmail());
            Actor salonMaster = actor(salon.masterEmail());
            fileReport(reporter, "REVIEW", reviewId);

            resolveByEmail(ReportTargetType.REVIEW, reviewId, ModerationAction.REMOVE);

            assertSingleOutcome(reporter, "REPORT_CONTENT_REMOVED", "REVIEW", reviewId);
            assertSingleOutcome(author, "CONTENT_REMOVED", "REVIEW", reviewId);
            assertThat(moderationItems(owner)).as("salon owner feed").isEmpty();
            assertThat(moderationItems(salonMaster)).as("salon master feed").isEmpty();
        }
    }

    // ── salon portfolio photo ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("salon PORTFOLIO_PHOTO removed")
    class SalonPhotoRemoved {

        @Test
        @DisplayName("client reports a salon photo via API, curl REMOVE → reporter REPORT_CONTENT_REMOVED, owner + "
                + "admin CONTENT_REMOVED(PORTFOLIO_PHOTO) in their feeds; the salon master's feed stays empty")
        void should_showOutcomeToReporterOwnerAndAdmin_when_salonPhotoRemovedViaCurl() throws Exception {
            SalonFixture salon = createSalon();
            String adminEmail = unique("admin");
            createUser(adminEmail, "SALON_ADMIN", salon.salonId());
            Actor admin = actor(adminEmail);
            Actor owner = actor(salon.ownerEmail());
            Actor salonMaster = actor(salon.masterEmail());
            UUID photoId = insertSalonPortfolioPhoto(salon.salonId());
            Actor reporter = client("reporter");
            int ownerUnread = unread(owner);
            int adminUnread = unread(admin);
            UUID reportId = fileReport(reporter, "PORTFOLIO_PHOTO", photoId);

            assertThat(resolve(reportId, "REMOVE").getStatusCode()).isEqualTo(HttpStatus.OK);

            assertSingleOutcome(reporter, "REPORT_CONTENT_REMOVED", "PORTFOLIO_PHOTO", photoId, salon.salonId());
            assertSingleOutcome(owner, "CONTENT_REMOVED", "PORTFOLIO_PHOTO", photoId, salon.salonId());
            assertSingleOutcome(admin, "CONTENT_REMOVED", "PORTFOLIO_PHOTO", photoId, salon.salonId());
            assertThat(unread(owner)).as("owner unread").isEqualTo(ownerUnread + 1);
            assertThat(unread(admin)).as("admin unread").isEqualTo(adminUnread + 1);
            assertThat(moderationItems(salonMaster)).as("salon master is never the author of salon content")
                    .isEmpty();
        }

        @Test
        @DisplayName("the salon's own master reports the salon photo, e-mail REMOVE → that master gets only "
                + "REPORT_CONTENT_REMOVED (as reporter), never CONTENT_REMOVED; owner gets CONTENT_REMOVED")
        void should_notifySalonMasterAsReporterOnly_when_salonPhotoRemovedViaEmail() throws Exception {
            SalonFixture salon = createSalon();
            Actor owner = actor(salon.ownerEmail());
            Actor salonMaster = actor(salon.masterEmail());
            UUID photoId = insertSalonPortfolioPhoto(salon.salonId());
            fileReport(salonMaster, "PORTFOLIO_PHOTO", photoId);

            resolveByEmail(ReportTargetType.PORTFOLIO_PHOTO, photoId, ModerationAction.REMOVE);

            assertSingleOutcome(salonMaster, "REPORT_CONTENT_REMOVED", "PORTFOLIO_PHOTO", photoId, salon.salonId());
            assertSingleOutcome(owner, "CONTENT_REMOVED", "PORTFOLIO_PHOTO", photoId, salon.salonId());
        }
    }

    // ── salon client review (salon-owned content) ────────────────────────────────────────────

    @Test
    @DisplayName("salon client review reported via API by another provider who served the client, curl REMOVE → "
            + "owner sees CONTENT_REMOVED(CLIENT_REVIEW); the salon master whose name it carries sees nothing")
    void should_neverNotifySalonMaster_when_salonClientReviewRemoved() throws Exception {
        SalonFixture salon = createSalon();
        Actor owner = actor(salon.ownerEmail());
        Actor salonMaster = actor(salon.masterEmail());
        Actor subject = client("subject");
        UUID clientReviewId = insertClientReview(insertCompletedBooking(subject.id(), salon), subject.id(), salon);
        String otherEmail = unique("indep");
        IndependentMaster other = createIndependentMaster(otherEmail);
        insertIndependentBooking(subject.id(), other.masterId());
        Actor reporter = new Actor(other.userId(), tokenFor(otherEmail));
        UUID reportId = fileReport(reporter, "CLIENT_REVIEW", clientReviewId);

        assertThat(resolve(reportId, "REMOVE").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertSingleOutcome(reporter, "REPORT_CONTENT_REMOVED", "CLIENT_REVIEW", clientReviewId, salon.salonId());
        assertSingleOutcome(owner, "CONTENT_REMOVED", "CLIENT_REVIEW", clientReviewId, salon.salonId());
        assertThat(moderationItems(salonMaster)).as("salon master feed").isEmpty();
        assertThat(moderationItems(subject)).as("the reviewed client is not the author").isEmpty();
    }

    // ── independent master photo ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("client reports an independent master's photo via API, curl REMOVE → reporter "
            + "REPORT_CONTENT_REMOVED, the master CONTENT_REMOVED(PORTFOLIO_PHOTO) in GET /notifications")
    void should_showOutcomeToReporterAndMaster_when_independentPhotoRemoved() throws Exception {
        String masterEmail = unique("indep");
        IndependentMaster master = createIndependentMaster(masterEmail);
        Actor masterActor = new Actor(master.userId(), tokenFor(masterEmail));
        UUID photoId = insertMasterPortfolioPhoto(master.masterId());
        Actor reporter = client("reporter");
        int masterUnread = unread(masterActor);
        UUID reportId = fileReport(reporter, "PORTFOLIO_PHOTO", photoId);

        assertThat(resolve(reportId, "REMOVE").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertSingleOutcome(reporter, "REPORT_CONTENT_REMOVED", "PORTFOLIO_PHOTO", photoId, master.masterId());
        assertSingleOutcome(masterActor, "CONTENT_REMOVED", "PORTFOLIO_PHOTO", photoId, master.masterId());
        assertThat(unread(masterActor)).as("master unread").isEqualTo(masterUnread + 1);
    }

    // ── D11 telemetry ────────────────────────────────────────────────────────────────────────

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("D11: the resolve + notify log lines carry types and counts only — no reporter / author / target id, "
            + "review text or report comment")
    void should_logCountsOnly_when_outcomeNotified(CapturedOutput output) throws Exception {
        SalonFixture salon = createSalon();
        Actor author = client("author");
        UUID reviewId = insertReview(author.id(), salon, REVIEW_TEXT);
        Actor r1 = client("reporter");
        Actor r2 = client("reporter");
        UUID reportId = fileReport(r1, "REVIEW", reviewId);
        fileReport(r2, "REVIEW", reviewId);
        int before = output.getAll().length();

        assertThat(resolve(reportId, "REMOVE").getStatusCode()).isEqualTo(HttpStatus.OK);

        String resolveWindow = output.getAll().substring(before);
        assertThat(resolveWindow).as("positive control: the outcome line was written")
                .contains("in-app moderation outcome targetType=REVIEW resolution=REMOVED "
                        + "reporterType=REPORT_CONTENT_REMOVED reporters=2 authors=1");
        assertThat(resolveWindow).doesNotContain(r1.id().toString(), r2.id().toString(), author.id().toString(),
                reviewId.toString(), reportId.toString(), REVIEW_TEXT, REPORT_COMMENT, "checked by support");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private Actor client(String prefix) throws Exception {
        String email = unique(prefix);
        createUser(email, "CLIENT", null);
        return actor(email);
    }

    private Actor actor(String email) throws Exception {
        UUID id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        return new Actor(id, tokenFor(email));
    }

    /** The phase-395 client path: {@code POST /reports} with a UGC comment; returns the new report id. */
    private UUID fileReport(Actor reporter, String targetType, UUID targetId) {
        ResponseEntity<String> resp = postReport(reporter.token(),
                reportBody(targetType, targetId, "OTHER", REPORT_COMMENT));
        assertThat(resp.getStatusCode()).as("POST /reports: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(data(resp).path("id").asText());
    }

    private void resolveByEmail(ReportTargetType type, UUID targetId, ModerationAction action) {
        String token = actionTokenService.mint(type, targetId, List.of(action)).get(action);
        ResponseEntity<String> page = restTemplate.exchange(ACTIONS + token, HttpMethod.GET, HttpEntity.EMPTY,
                String.class);
        ResponseEntity<String> done = postConfirm(token, nonceOf(page));
        assertThat(done.getStatusCode()).as("e-mail confirm: %s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(openReports(targetId)).isZero();
    }

    private UUID insertIndependentReviewWithText(UUID clientId, UUID masterId) {
        UUID reviewId = insertIndependentReview(clientId, masterId);
        jdbcTemplate.update("UPDATE reviews SET comment = ? WHERE id = ?", REVIEW_TEXT, reviewId);
        return reviewId;
    }

    /**
     * The recipient's feed, read through the API with their own token, holds exactly one moderation item of the
     * expected type and content kind, with a NONE target, and the raw body leaks no UGC or identifier.
     */
    private void assertSingleOutcome(Actor recipient, String type, String contentKind, UUID... forbiddenIds)
            throws Exception {
        ResponseEntity<String> feed = exchange(HttpMethod.GET, FEED, recipient.token());
        assertThat(feed.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> items = moderationItems(feed);
        assertThat(items).as("moderation items in %s's feed", type).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.path("type").asText()).as("type").isEqualTo(type);
        assertThat(item.path("params").path("contentKind").asText()).as("contentKind").isEqualTo(contentKind);
        assertThat(item.path("target").path("kind").asText()).as("target.kind").isEqualTo("NONE");
        assertThat(item.path("read").asBoolean(true)).as("unread").isFalse();
        assertThat(feed.getBody()).doesNotContain(REVIEW_TEXT, REPORT_COMMENT, "checked by support",
                "r2.dev", "portfolio/", "Salon-");
        for (UUID id : forbiddenIds) {
            assertThat(feed.getBody()).as("feed must not expose id %s", id).doesNotContain(id.toString());
        }
    }

    private List<JsonNode> moderationItems(Actor recipient) throws Exception {
        return moderationItems(exchange(HttpMethod.GET, FEED, recipient.token()));
    }

    private List<JsonNode> moderationItems(ResponseEntity<String> feed) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(feed.getBody()).path("data")) {
            String type = item.path("type").asText();
            if (type.startsWith("REPORT_") || type.equals("CONTENT_REMOVED")) {
                items.add(item);
            }
        }
        return items;
    }

    private int unread(Actor actor) {
        return data(exchange(HttpMethod.GET, UNREAD, actor.token())).path("count").asInt();
    }
}
