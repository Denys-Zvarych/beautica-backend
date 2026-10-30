package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.dto.ContentReportEmailModel;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.dto.ModerationActionLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * Phase 414 — the support e-mail's one-click moderation links end to end over real HTTP: report (listener mints the
 * links), GET confirm page (no side effect), POST confirm (phase-396 resolve), link death on any resolution, D10
 * outcome pages + status codes, D12 headers, D14 security, D15 no secrets in logs.
 */
@Import(TestSecurityConfig.class)
// Same property as ContentReportIT (shared context): the listener only mints and sends with SUPPORT_EMAIL set.
@TestPropertySource(properties = "app.support.email=" + ContentReportIT.SUPPORT_EMAIL)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Moderation e-mail action links — HTTP (phase 414)")
class ModerationActionLinkControllerIT extends AbstractModerationIT {

    private static final String ACTIONS = "/api/v1/moderation/actions/";

    @Test
    @DisplayName("GET has no side effect: report stays OPEN, token unused, nonce stored; a repeat GET rotates it and "
            + "the old nonce POSTs INVALID (410)")
    void should_notAct_when_getAndRotateNonce() throws Exception {
        UUID reviewId = reportedReview(1);
        String token = tokenOf(linkFor(ModerationAction.REMOVE));

        ResponseEntity<String> first = get(token);
        String firstNonce = nonceOf(first);
        String secondNonce = nonceOf(get(token));
        ResponseEntity<String> stale = postConfirm(token, firstNonce);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("Підтвердіть рішення", "Видалити", "Відгук клієнта")
                .doesNotContain("Грубий текст");
        assertThat(secondNonce).isNotEqualTo(firstNonce);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(stale.getBody()).contains("Посилання недійсне");
        assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
        assertThat(openReports(reviewId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM moderation_action_tokens WHERE used_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    @DisplayName("GET then POST REMOVE: review REMOVED, every OPEN report RESOLVED, every sibling link revoked")
    void should_removeAndRevokeSiblings_when_removeConfirmed() throws Exception {
        UUID reviewId = reportedReview(2);
        String token = tokenOf(linkFor(ModerationAction.REMOVE));

        ResponseEntity<String> done = postConfirm(token, nonceOf(get(token)));

        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).contains("Готово");
        assertThat(moderationStatus(reviewId)).isEqualTo("REMOVED");
        assertThat(openReports(reviewId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE target_id = ? AND resolution_note = ?", Integer.class,
                reviewId, "Resolved via email link")).isEqualTo(2);
        assertThat(liveTokens(reviewId)).as("D11: zero live links after any resolution").isZero();
    }

    @Test
    @DisplayName("POST RESTORE on an auto-hidden review restores it; RESTORE is only offered for HIDDEN_PENDING")
    void should_restore_when_restoreConfirmedOnHiddenReview() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Грубий текст");
        for (int i = 0; i < 3; i++) {
            reportReview(eligibleClient(salon).email(), reviewId);
        }
        List<ContentReportEmailModel> emails = sentEmails();
        String token = tokenOf(linkIn(emails.get(emails.size() - 1), ModerationAction.RESTORE));

        ResponseEntity<String> done = postConfirm(token, nonceOf(get(token)));

        assertThat(emails.subList(0, emails.size() - 1)).allSatisfy(model -> assertThat(model.actions())
                .extracting(ModerationActionLink::action).doesNotContain(ModerationAction.RESTORE));
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
        assertThat(openReports(reviewId)).isZero();
        assertThat(liveTokens(reviewId)).isZero();
    }

    @Test
    @DisplayName("POST DISMISS closes the reports and keeps the content")
    void should_dismiss_when_dismissConfirmed() throws Exception {
        UUID reviewId = reportedReview(1);
        String token = tokenOf(linkFor(ModerationAction.DISMISS));

        ResponseEntity<String> done = postConfirm(token, nonceOf(get(token)));

        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
        assertThat(openReports(reviewId)).isZero();
    }

    @Test
    @DisplayName("a used link → friendly 410 on both GET and POST")
    void should_beGone_when_linkUsed() throws Exception {
        reportedReview(1);
        String token = tokenOf(linkFor(ModerationAction.DISMISS));
        String nonce = nonceOf(get(token));
        postConfirm(token, nonce);

        ResponseEntity<String> reGet = get(token);
        ResponseEntity<String> rePost = postConfirm(token, nonce);

        assertThat(reGet.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(rePost.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(reGet.getBody()).contains("Посилання недійсне або термін його дії минув");
    }

    @Test
    @DisplayName("an expired link → 410, and an expired confirm nonce → 410 with nothing resolved")
    void should_beGone_when_linkOrNonceExpired() throws Exception {
        UUID reviewId = reportedReview(1);
        String expiredToken = tokenOf(linkFor(ModerationAction.REMOVE));
        String token = tokenOf(linkFor(ModerationAction.DISMISS));
        jdbcTemplate.update("UPDATE moderation_action_tokens SET expires_at = NOW() - interval '1 second' "
                + "WHERE action = 'REMOVE'");
        String nonce = nonceOf(get(token));
        jdbcTemplate.update("UPDATE moderation_action_tokens SET confirm_nonce_expires_at = NOW() - interval '1 second' "
                + "WHERE action = 'DISMISS'");

        ResponseEntity<String> expiredGet = get(expiredToken);
        ResponseEntity<String> staleNonce = postConfirm(token, nonce);

        assertThat(expiredGet.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(staleNonce.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(openReports(reviewId)).isEqualTo(1);
    }

    @Test
    @DisplayName("resolved via curl, then the e-mail POST → 410 (the link was revoked by the resolve)")
    void should_beGone_when_resolvedViaCurlFirst() throws Exception {
        UUID reviewId = reportedReview(1);
        String token = tokenOf(linkFor(ModerationAction.REMOVE));
        String nonce = nonceOf(get(token));

        ResponseEntity<String> curl = resolve(reportIdOnTarget(reviewId), "DISMISS");
        ResponseEntity<String> email = postConfirm(token, nonce);

        assertThat(curl.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(email.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(liveTokens(reviewId)).isZero();
        assertThat(moderationStatus(reviewId)).isEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("resolved via e-mail, then curl → the existing 409 is unchanged")
    void should_keepCurlConflict_when_resolvedViaEmailFirst() throws Exception {
        UUID reviewId = reportedReview(1);
        UUID reportId = reportIdOnTarget(reviewId);
        String token = tokenOf(linkFor(ModerationAction.DISMISS));
        postConfirm(token, nonceOf(get(token)));

        ResponseEntity<String> curl = resolve(reportId, "REMOVE");

        assertThat(curl.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("target deleted before the click → 409 CONTENT_GONE page")
    void should_beContentGone_when_targetDeleted() throws Exception {
        UUID reviewId = reportedReview(1);
        String token = tokenOf(linkFor(ModerationAction.REMOVE));
        String nonce = nonceOf(get(token));
        jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", reviewId);

        ResponseEntity<String> resp = postConfirm(token, nonce);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).contains("Вміст уже видалено");
        assertThat(openReports(reviewId)).isZero();
    }

    @Test
    @DisplayName("D12 + CSP: no-store, Referrer-Policy, X-Robots-Tag, CSP on every page (confirm, result, invalid) + "
            + "meta robots; the result page still renders under the CSP")
    void should_sendNoStoreAndNoIndexHeaders_when_anyPage() throws Exception {
        reportedReview(1);
        String token = tokenOf(linkFor(ModerationAction.DISMISS));
        ResponseEntity<String> confirmPage = get(token);
        ResponseEntity<String> result = postConfirm(token, nonceOf(confirmPage));
        ResponseEntity<String> invalid = get("x".repeat(43));
        ResponseEntity<String> missingParams = postConfirm(null, null);

        assertThat(List.of(confirmPage, result, invalid, missingParams)).allSatisfy(resp -> {
            assertThat(resp.getHeaders().getCacheControl()).contains("no-store");
            assertThat(resp.getHeaders().getFirst("Referrer-Policy")).isEqualTo("same-origin");
            assertThat(resp.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex, nofollow");
            assertThat(resp.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(
                    "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; form-action 'self'; "
                            + "frame-ancestors 'none'; base-uri 'none'");
            assertThat(resp.getHeaders().getContentType()).isNotNull()
                    .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
            assertThat(resp.getBody()).contains("<meta name=\"robots\" content=\"noindex,nofollow\"/>");
        });
        assertThat(missingParams.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).contains("<h1", "Готово").doesNotContain("<script").doesNotContain("<link");
    }

    @Test
    @DisplayName("D14: both routes need no JWT; /internal/** still requires the key")
    void should_beReachableWithoutJwt_when_tokenRoutes() {
        ResponseEntity<String> page = get("y".repeat(43));
        ResponseEntity<String> confirm = postConfirm("y".repeat(43), "z".repeat(43));
        ResponseEntity<String> internalWithoutKey =
                internal(HttpMethod.GET, "/api/v1/internal/moderation/reports", null, null);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(internalWithoutKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("D15: no token, nonce, link path or UGC in the logs during a full report → GET → POST run")
    void should_logNoSecretsOrUgc_when_fullFlow(CapturedOutput output) throws Exception {
        reportedReview(1);
        ModerationActionLink link = linkFor(ModerationAction.REMOVE);
        String token = tokenOf(link);
        String nonce = nonceOf(get(token));
        postConfirm(token, nonce);

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(nonce).doesNotContain(link.url())
                .doesNotContain("Грубий текст").doesNotContain("REPORTER-NOTE");
    }

    // ── profile flow (follow-up) ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("profile follow-up: the confirm page labels a profile REMOVE «Підтвердити скаргу», the result page "
            + "says «Скаргу підтверджено», reports ACTIONED, and nothing suggests a deletion")
    void should_sayConfirmedNotDeleted_when_profileReportConfirmed() throws Exception {
        SalonFixture salon = createSalon();
        clearInvocations(emailService);
        String email = unique("reporter");
        createUser(email, "CLIENT", null);
        assertThat(postReport(tokenFor(email), reportBody("SALON_PROFILE", salon.salonId(), "OFFENSIVE", null))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = tokenOf(linkFor(ModerationAction.REMOVE));

        ResponseEntity<String> page = get(token);
        ResponseEntity<String> done = postConfirm(token, nonceOf(page));

        assertThat(page.getBody()).contains("Профіль салону", "Підтвердити скаргу").doesNotContain("Видалити");
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).contains("Скаргу підтверджено").doesNotContainIgnoringCase("видал");
        assertThat(jdbcTemplate.queryForList("SELECT resolution FROM content_reports WHERE target_id = ?",
                String.class, salon.salonId())).containsExactly("ACTIONED");
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM salons WHERE id = ?", Boolean.class,
                salon.salonId())).as("nothing is suspended").isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** A salon review with {@code reports} OPEN reports from distinct (ineligible) clients; e-mails captured. */
    private UUID reportedReview(int reports) throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Грубий текст");
        clearInvocations(emailService);
        for (int i = 0; i < reports; i++) {
            String email = unique("reporter");
            createUser(email, "CLIENT", null);
            ResponseEntity<String> resp = postReport(tokenFor(email),
                    reportBody("REVIEW", reviewId, "OFFENSIVE", "REPORTER-NOTE"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        return reviewId;
    }

    private List<ContentReportEmailModel> sentEmails() {
        ArgumentCaptor<ContentReportEmailModel> captor = ArgumentCaptor.forClass(ContentReportEmailModel.class);
        verify(emailService, atLeastOnce()).sendContentReportEmail(anyString(), captor.capture());
        return captor.getAllValues();
    }

    /** The {@code action} link of the most recent report e-mail. */
    private ModerationActionLink linkFor(ModerationAction action) {
        List<ContentReportEmailModel> emails = sentEmails();
        return linkIn(emails.get(emails.size() - 1), action);
    }

    private static ModerationActionLink linkIn(ContentReportEmailModel model, ModerationAction action) {
        Map<ModerationAction, ModerationActionLink> byAction = model.actions().stream()
                .collect(Collectors.toMap(ModerationActionLink::action, l -> l));
        assertThat(byAction).as("e-mail offers %s", action).containsKey(action);
        return byAction.get(action);
    }

    private static String tokenOf(ModerationActionLink link) {
        assertThat(link.url()).startsWith("https://api.beautica.test" + ACTIONS);
        return link.url().substring(("https://api.beautica.test" + ACTIONS).length());
    }

    private ResponseEntity<String> get(String token) {
        return restTemplate.exchange(ACTIONS + token, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private UUID reportIdOnTarget(UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM content_reports WHERE target_id = ? ORDER BY created_at LIMIT 1", UUID.class, targetId);
    }
}
