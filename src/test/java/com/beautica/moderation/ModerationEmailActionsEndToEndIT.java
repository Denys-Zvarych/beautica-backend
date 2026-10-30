package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.config.SupportProperties;
import com.beautica.moderation.dto.ContentReportEmailModel;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.event.ContentReportCreatedEvent;
import com.beautica.moderation.event.ContentReportEmailDispatcher;
import com.beautica.moderation.service.ContentReportRetentionService;
import com.beautica.notification.EmailService;
import com.beautica.review.service.RatingRecalculationService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 414 — the support e-mail's moderation buttons end to end, driven from the RENDERED e-mail HTML (the real
 * {@code templates/email/content-report.html} rendered by a real {@link EmailService} over the context's own Thymeleaf
 * engine) rather than from the captured model: report → e-mail → href → GET confirm page → POST → resolve, ratings,
 * link death; the button set per target type; a mail-scanner prefetch; the retention sweep's orphaned-link revoke.
 * {@link ModerationActionLinkControllerIT} covers the per-outcome HTTP contract; this class covers the whole chain.
 */
@Import(TestSecurityConfig.class)
// Same property as ContentReportIT / ModerationActionLinkControllerIT (shared context): SUPPORT_EMAIL set → mint + send.
@TestPropertySource(properties = "app.support.email=" + ContentReportIT.SUPPORT_EMAIL)
@DisplayName("Moderation e-mail action links — end to end from the rendered e-mail (phase 414)")
class ModerationEmailActionsEndToEndIT extends AbstractModerationIT {

    private static final String BASE_URL = "https://api.beautica.test/api/v1/moderation/actions/";
    private static final Pattern ANCHOR = Pattern.compile("<a\\b[^>]*>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern DATA_ACTION = Pattern.compile("data-action=\"([A-Z]+)\"");
    private static final String INVALID_PAGE = "Посилання недійсне або термін його дії минув";

    @Autowired
    private SpringTemplateEngine templateEngine;

    @Autowired
    private RatingRecalculationService ratingRecalculationService;

    @Autowired
    private ContentReportRetentionService retentionService;

    @Autowired
    private ContentReportEmailDispatcher dispatcher;

    @Autowired
    private SupportProperties supportProperties;

    // ── 1. the whole path ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("report → rendered e-mail → REMOVE href → GET confirm (nonce) → POST: review REMOVED, ratings "
            + "recalculated, every report RESOLVED, and every other link of BOTH e-mails for the target is dead (410)")
    void should_removeReviewRecalculateRatingsAndKillAllLinks_when_removeClickedFromRenderedEmail() throws Exception {
        SalonFixture salon = createSalon();
        insertReview(createUser(unique("happy"), "CLIENT", null), salon, "Чудово", 5);
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Грубий текст", 1);
        ratingRecalculationService.recalculateMasterRating(salon.masterId());
        ratingRecalculationService.recalculateSalonRating(salon.salonId());
        assertRatings(salon, "3.00", 2);
        clearInvocations(emailService);
        reportAsNewClient("REVIEW", reviewId);
        reportAsNewClient("REVIEW", reviewId);
        List<String> emails = renderedEmails();
        assertThat(emails).as("one e-mail per report").hasSize(2);
        Map<String, String> latest = buttons(emails.get(1));
        String removeHref = latest.get("REMOVE");

        ResponseEntity<String> confirmPage = get(removeHref);
        ResponseEntity<String> done = postConfirm(tokenOf(removeHref), nonceOf(confirmPage));

        assertThat(confirmPage.getBody()).as("D7: confirm page, not an action")
                .contains("Підтвердіть рішення", "Видалити");
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).contains("Готово");
        assertThat(moderationStatus(reviewId)).isEqualTo("REMOVED");
        assertThat(resolutions(reviewId)).as("both reports closed by the one click").containsExactly("REMOVED", "REMOVED");
        assertRatings(salon, "5.00", 1);
        assertThat(liveTokens(reviewId)).as("D11: zero live links").isZero();
        for (String email : emails) {
            for (String href : buttons(email).values()) {
                ResponseEntity<String> dead = get(href);
                assertThat(dead.getStatusCode()).as("dead link %s", href.equals(removeHref) ? "(used)" : "(sibling)")
                        .isEqualTo(HttpStatus.GONE);
                assertThat(dead.getBody()).contains(INVALID_PAGE).doesNotContain("name=\"nonce\"");
            }
        }
    }

    @Test
    @DisplayName("curl resolve on a second target kills that target's rendered e-mail links (410) and leaves another "
            + "target of the same type live")
    void should_killOnlyThatTargetsLinks_when_secondTargetResolvedViaCurl() throws Exception {
        SalonFixture salon = createSalon();
        // Same target TYPE on purpose: the revoke must be scoped by target id, not only by type.
        UUID otherPhotoId = insertMasterPortfolioPhoto(salon.masterId());
        UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
        clearInvocations(emailService);
        reportAsNewClient("PORTFOLIO_PHOTO", otherPhotoId);
        reportAsNewClient("PORTFOLIO_PHOTO", photoId);
        List<String> emails = renderedEmails();
        Map<String, String> otherLinks = buttons(emails.get(0));
        Map<String, String> photoLinks = buttons(emails.get(1));

        ResponseEntity<String> curl = resolve(oldestReport(photoId), "DISMISS");

        assertThat(curl.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(photoLinks.values()).allSatisfy(href -> {
            ResponseEntity<String> dead = get(href);
            assertThat(dead.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(dead.getBody()).contains(INVALID_PAGE);
        });
        assertThat(liveTokens(photoId)).isZero();
        assertThat(get(otherLinks.get("REMOVE")).getStatusCode()).as("other target of the same type untouched")
                .isEqualTo(HttpStatus.OK);
        assertThat(liveTokens(otherPhotoId)).isEqualTo(2);
    }

    // ── 2. the button set per target type ───────────────────────────────────────────────────

    /** Every report e-mail variant: REMOVE + DISMISS always, RESTORE only on a HIDDEN_PENDING review (D3). */
    enum Variant {
        REVIEW_VISIBLE(ReportTargetType.REVIEW, Set.of("REMOVE", "DISMISS")),
        REVIEW_HIDDEN_PENDING(ReportTargetType.REVIEW, Set.of("REMOVE", "DISMISS", "RESTORE")),
        CLIENT_REVIEW_VISIBLE(ReportTargetType.CLIENT_REVIEW, Set.of("REMOVE", "DISMISS")),
        CLIENT_REVIEW_HIDDEN_PENDING(ReportTargetType.CLIENT_REVIEW, Set.of("REMOVE", "DISMISS", "RESTORE")),
        PORTFOLIO_PHOTO(ReportTargetType.PORTFOLIO_PHOTO, Set.of("REMOVE", "DISMISS")),
        MASTER_PROFILE(ReportTargetType.MASTER_PROFILE, Set.of("REMOVE", "DISMISS")),
        SALON_PROFILE(ReportTargetType.SALON_PROFILE, Set.of("REMOVE", "DISMISS")),
        CLIENT_PROFILE(ReportTargetType.CLIENT_PROFILE, Set.of("REMOVE", "DISMISS"));

        final ReportTargetType type;
        final Set<String> expected;

        Variant(ReportTargetType type, Set<String> expected) {
            this.type = type;
            this.expected = expected;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Variant.class)
    @DisplayName("each target type's rendered e-mail carries exactly its button set; the stored rows match the hrefs")
    void should_renderExactButtonSet_when_targetTypeReported(Variant variant) throws Exception {
        clearInvocations(emailService);
        UUID targetId = emailFor(variant);
        List<String> emails = renderedEmails();
        Map<String, String> buttons = buttons(emails.get(emails.size() - 1));

        assertThat(buttons.keySet()).as("%s buttons", variant).containsExactlyInAnyOrderElementsOf(variant.expected);
        assertThat(buttons.values()).allSatisfy(href -> assertThat(href).startsWith(BASE_URL));
        assertThat(jdbcTemplate.queryForList("SELECT action FROM moderation_action_tokens WHERE target_type = ? "
                        + "AND target_id = ? AND created_at = (SELECT MAX(created_at) FROM moderation_action_tokens "
                        + "WHERE target_id = ?)", String.class, variant.type.name(), targetId, targetId))
                .as("one live row per rendered button").containsExactlyInAnyOrderElementsOf(variant.expected);
        assertThat(get(buttons.get("REMOVE")).getStatusCode()).as("the rendered REMOVE href resolves to a live token")
                .isEqualTo(HttpStatus.OK);
    }

    // ── 3. mail-scanner prefetch ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a mail scanner GETs every button twice → nothing acts (status, reports, tokens unchanged) and the "
            + "moderator can still act afterwards")
    void should_neverAct_when_scannerPrefetchesEveryLinkTwice() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Грубий текст");
        clearInvocations(emailService);
        for (int i = 0; i < 3; i++) {
            reportReview(eligibleClient(salon).email(), reviewId);
        }
        List<String> emails = renderedEmails();
        Map<String, String> buttons = buttons(emails.get(emails.size() - 1));
        assertThat(moderationStatus(reviewId)).as("precondition: auto-hidden").isEqualTo("HIDDEN_PENDING");
        assertThat(buttons).containsKeys("REMOVE", "DISMISS", "RESTORE");

        for (int pass = 0; pass < 2; pass++) {
            for (String href : buttons.values()) {
                assertThat(get(href).getStatusCode()).as("prefetch renders the confirm page")
                        .isEqualTo(HttpStatus.OK);
            }
        }

        assertThat(moderationStatus(reviewId)).isEqualTo("HIDDEN_PENDING");
        assertThat(openReports(reviewId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM moderation_action_tokens WHERE target_id = ? "
                + "AND (used_at IS NOT NULL OR revoked_at IS NOT NULL)", Integer.class, reviewId)).isZero();
        String restore = buttons.get("RESTORE");
        ResponseEntity<String> done = postConfirm(tokenOf(restore), nonceOf(get(restore)));
        assertThat(done.getStatusCode()).as("the human click still works").isEqualTo(HttpStatus.OK);
        assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
    }

    // ── 4. retention: orphaned links of a TARGET_GONE target ────────────────────────────────

    @Test
    @DisplayName("target deleted → retention sweep closes its reports TARGET_GONE and revokes its e-mailed links "
            + "(410 page); a live target's links survive the sweep")
    void should_revokeOrphanedLinks_when_retentionClosesTargetGone() throws Exception {
        SalonFixture salon = createSalon();
        UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
        clearInvocations(emailService);
        reportAsNewClient("PORTFOLIO_PHOTO", photoId);
        reportAsNewClient("SALON_PROFILE", salon.salonId());
        List<String> emails = renderedEmails();
        Map<String, String> photoLinks = buttons(emails.get(0));
        Map<String, String> salonLinks = buttons(emails.get(1));
        jdbcTemplate.update("DELETE FROM media_files WHERE id = ?", photoId);

        retentionService.sweep(Instant.now());

        assertThat(resolutions(photoId)).containsExactly("TARGET_GONE");
        assertThat(liveTokens(photoId)).as("D11: orphaned links revoked by the sweep").isZero();
        assertThat(photoLinks.values()).allSatisfy(href -> {
            ResponseEntity<String> dead = get(href);
            assertThat(dead.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(dead.getBody()).contains(INVALID_PAGE);
        });
        assertThat(liveTokens(salon.salonId())).isEqualTo(2);
        assertThat(get(salonLinks.get("DISMISS")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── fixtures per variant ────────────────────────────────────────────────────────────────

    /** Files the report(s) that produce {@code variant}'s e-mail over HTTP (or, where HTTP cannot, the dispatcher). */
    private UUID emailFor(Variant variant) throws Exception {
        SalonFixture salon = createSalon();
        switch (variant) {
            case REVIEW_VISIBLE -> {
                UUID id = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
                reportAsNewClient("REVIEW", id);
                return id;
            }
            case REVIEW_HIDDEN_PENDING -> {
                UUID id = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано");
                for (int i = 0; i < 3; i++) {
                    reportReview(eligibleClient(salon).email(), id);
                }
                assertThat(moderationStatus(id)).as("precondition: auto-hidden").isEqualTo("HIDDEN_PENDING");
                return id;
            }
            case CLIENT_REVIEW_VISIBLE -> {
                UUID clientId = createUser(unique("client"), "CLIENT", null);
                UUID id = insertClientReview(insertCompletedBooking(clientId, salon), clientId, salon);
                assertCreated(postReport(tokenFor(otherProviderOf(clientId)),
                        reportBody("CLIENT_REVIEW", id, "FALSE_INFO", null)));
                return id;
            }
            case CLIENT_REVIEW_HIDDEN_PENDING -> {
                // A HIDDEN_PENDING client review is a 404 to POST /reports and auto-hide applies to client→provider
                // reviews only, so HTTP cannot produce this e-mail today; drive the real dispatcher (real loader,
                // real minting, real DB) with the event a report would publish.
                UUID clientId = createUser(unique("client"), "CLIENT", null);
                UUID id = insertClientReview(insertCompletedBooking(clientId, salon), clientId, salon);
                jdbcTemplate.update("UPDATE client_reviews SET moderation_status = 'HIDDEN_PENDING' WHERE id = ?", id);
                UUID reportId = UUID.randomUUID();
                UUID reporterId = createUser(unique("reporter"), "INDEPENDENT_MASTER", null);
                jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, "
                        + "created_at, updated_at) VALUES (?, ?, 'CLIENT_REVIEW', ?, 'FALSE_INFO', NOW(), NOW())",
                        reportId, reporterId, id);
                dispatcher.dispatch(supportProperties.getEmail(), new ContentReportCreatedEvent(reportId,
                        ReportTargetType.CLIENT_REVIEW, id, ReportReason.FALSE_INFO, reporterId, false, null)).join();
                return id;
            }
            case PORTFOLIO_PHOTO -> {
                UUID id = insertMasterPortfolioPhoto(salon.masterId());
                reportAsNewClient("PORTFOLIO_PHOTO", id);
                return id;
            }
            case MASTER_PROFILE -> {
                reportAsNewClient("MASTER_PROFILE", salon.masterId());
                return salon.masterId();
            }
            case SALON_PROFILE -> {
                reportAsNewClient("SALON_PROFILE", salon.salonId());
                return salon.salonId();
            }
            case CLIENT_PROFILE -> {
                UUID clientId = createUser(unique("client"), "CLIENT", null);
                insertCompletedBooking(clientId, salon);
                assertCreated(postReport(tokenFor(salon.masterEmail()),
                        reportBody("CLIENT_PROFILE", clientId, "HARASSMENT", null)));
                return clientId;
            }
            default -> throw new IllegalStateException(variant.name());
        }
    }

    /** An independent master who also served {@code clientId} — allowed to report another provider's client review. */
    private String otherProviderOf(UUID clientId) {
        String email = unique("indep");
        insertIndependentBooking(clientId, createIndependentMaster(email).masterId());
        return email;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void reportAsNewClient(String targetType, UUID targetId) throws Exception {
        String email = unique("reporter");
        createUser(email, "CLIENT", null);
        assertCreated(postReport(tokenFor(email), reportBody(targetType, targetId, "OFFENSIVE", "REPORTER-NOTE")));
    }

    private static void assertCreated(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as("report: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    /** Every report e-mail sent so far, rendered to HTML by a real {@link EmailService} over the real template. */
    private List<String> renderedEmails() throws Exception {
        ArgumentCaptor<ContentReportEmailModel> captor = ArgumentCaptor.forClass(ContentReportEmailModel.class);
        verify(emailService, atLeastOnce()).sendContentReportEmail(anyString(), captor.capture());
        List<String> html = new ArrayList<>();
        for (ContentReportEmailModel model : captor.getAllValues()) {
            html.add(render(model));
        }
        return html;
    }

    private String render(ContentReportEmailModel model) throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        new EmailService(sender, templateEngine, "noreply@beautica.test")
                .sendContentReportEmail(ContentReportIT.SUPPORT_EMAIL, model).join();
        verify(sender).send(message);
        return message.getContent().toString();
    }

    /** The e-mail's buttons as rendered: {@code data-action} → {@code href}, in document order. */
    private static Map<String, String> buttons(String html) {
        Map<String, String> byAction = new LinkedHashMap<>();
        Matcher anchors = ANCHOR.matcher(html);
        while (anchors.find()) {
            Matcher action = DATA_ACTION.matcher(anchors.group());
            Matcher href = HREF.matcher(anchors.group());
            if (action.find() && href.find()) {
                assertThat(byAction.put(action.group(1), href.group(1))).as("one button per action").isNull();
            }
        }
        return byAction;
    }

    private static String tokenOf(String href) {
        assertThat(href).startsWith(BASE_URL);
        return href.substring(BASE_URL.length());
    }

    private ResponseEntity<String> get(String href) {
        return restTemplate.exchange(URI.create(href).getPath(), HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private List<String> resolutions(UUID targetId) {
        return jdbcTemplate.queryForList("SELECT COALESCE(resolution, status) FROM content_reports WHERE target_id = ? "
                + "ORDER BY created_at", String.class, targetId);
    }

    private UUID oldestReport(UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM content_reports WHERE target_id = ? ORDER BY created_at LIMIT 1", UUID.class, targetId);
    }

    private void assertRatings(SalonFixture salon, String avg, int count) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT m.avg_rating AS master_avg, m.review_count AS master_count, "
                        + "s.avg_rating AS salon_avg, s.review_count AS salon_count "
                        + "FROM masters m JOIN salons s ON s.id = m.salon_id WHERE m.id = ?",
                salon.masterId());
        assertThat((BigDecimal) row.get("master_avg")).isEqualByComparingTo(avg);
        assertThat(((Number) row.get("master_count")).intValue()).isEqualTo(count);
        assertThat((BigDecimal) row.get("salon_avg")).isEqualByComparingTo(avg);
        assertThat(((Number) row.get("salon_count")).intValue()).isEqualTo(count);
    }
}
