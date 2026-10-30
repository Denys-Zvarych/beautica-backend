package com.beautica.moderation.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.dto.ModerationActionLink;
import com.beautica.moderation.dto.ModerationActionOutcome;
import com.beautica.moderation.dto.ResolveReportRequest;
import com.beautica.moderation.dto.ResolveReportResponse;
import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ModerationActionLinkService.ConfirmForm;
import com.beautica.moderation.service.ModerationActionLinkService.ConfirmView;
import com.beautica.moderation.service.ModerationActionLinkService.OutcomeView;
import com.beautica.moderation.service.ModerationActionTokenService.ClaimedToken;
import com.beautica.moderation.service.ModerationActionTokenService.IssuedNonce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("ModerationActionLinkService — e-mail link facade (phase 414)")
class ModerationActionLinkServiceTest {

    private static final String TOKEN = "T".repeat(43);
    private static final String NONCE = "N".repeat(43);
    private static final UUID TARGET_ID = UUID.fromString("0123abcd-1111-2222-3333-444455556666");
    private static final UUID REPORT_ID = UUID.randomUUID();
    private static final Instant CLAIMED_AT = Instant.parse("2026-10-29T10:00:00Z");

    @Mock
    private ModerationActionTokenService tokenService;

    @Mock
    private ModerationService moderationService;

    private ModerationActionLinkService service;

    @BeforeEach
    void setUp() {
        PublicBaseUrlProperties baseUrl = new PublicBaseUrlProperties();
        baseUrl.setPublicBaseUrl("https://api.beautica.test");
        service = new ModerationActionLinkService(tokenService, moderationService, baseUrl);
    }

    // ── mintFor (D3, D6) ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HIDDEN_PENDING review → REMOVE, DISMISS, RESTORE, every URL = PUBLIC_BASE_URL + path + raw token")
    void should_mintThreeLinks_when_hiddenPendingReview() {
        stubMint(ReportTargetType.REVIEW, List.of(ModerationAction.REMOVE, ModerationAction.DISMISS,
                ModerationAction.RESTORE));

        List<ModerationActionLink> links = service.mintFor(ReportTargetType.REVIEW, TARGET_ID, true);

        assertThat(links).extracting(ModerationActionLink::action).containsExactly(
                ModerationAction.REMOVE, ModerationAction.DISMISS, ModerationAction.RESTORE);
        assertThat(links).allSatisfy(link -> assertThat(link.url())
                .isEqualTo("https://api.beautica.test/api/v1/moderation/actions/raw-" + link.action()));
    }

    @Test
    @DisplayName("visible review → no RESTORE")
    void should_omitRestore_when_reviewVisible() {
        stubMint(ReportTargetType.CLIENT_REVIEW, List.of(ModerationAction.REMOVE, ModerationAction.DISMISS));

        List<ModerationActionLink> links = service.mintFor(ReportTargetType.CLIENT_REVIEW, TARGET_ID, false);

        assertThat(links).extracting(ModerationActionLink::action)
                .containsExactly(ModerationAction.REMOVE, ModerationAction.DISMISS);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ReportTargetType.class, names = {"PORTFOLIO_PHOTO", "MASTER_PROFILE", "SALON_PROFILE",
            "CLIENT_PROFILE"})
    @DisplayName("photo / profiles never get RESTORE, even if flagged hidden")
    void should_neverMintRestore_when_targetNotReview(ReportTargetType type) {
        stubMint(type, List.of(ModerationAction.REMOVE, ModerationAction.DISMISS));

        List<ModerationActionLink> links = service.mintFor(type, TARGET_ID, true);

        assertThat(links).extracting(ModerationActionLink::action).doesNotContain(ModerationAction.RESTORE);
    }

    // ── renderConfirm (D7, D8) ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("live token with an OPEN report → confirm form with the issued nonce")
    void should_renderForm_when_nonceIssued() {
        IssuedNonce nonce = new IssuedNonce(NONCE, ReportTargetType.REVIEW, ModerationAction.REMOVE, 2);
        when(tokenService.issueNonce(TOKEN)).thenReturn(new ModerationActionTokenService.Issued(nonce));

        ConfirmView view = service.renderConfirm(TOKEN);

        assertThat(view).isEqualTo(new ConfirmForm(TOKEN, nonce));
    }

    @Test
    @DisplayName("unusable token → INVALID; live token without OPEN report → ALREADY_RESOLVED")
    void should_renderOutcome_when_noNonce() {
        when(tokenService.issueNonce(TOKEN)).thenReturn(new ModerationActionTokenService.Unusable());
        when(tokenService.issueNonce("U".repeat(43))).thenReturn(new ModerationActionTokenService.NothingOpen());

        assertThat(service.renderConfirm(TOKEN)).isEqualTo(new OutcomeView(ModerationActionOutcome.INVALID));
        assertThat(service.renderConfirm("U".repeat(43)))
                .isEqualTo(new OutcomeView(ModerationActionOutcome.ALREADY_RESOLVED));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "short", "has space in it padded to forty three chars..", "../../../etc/passwd"})
    @DisplayName("malformed token → INVALID without touching the database")
    void should_rejectMalformedToken_when_rendering(String token) {
        assertThat(service.renderConfirm(token)).isEqualTo(new OutcomeView(ModerationActionOutcome.INVALID));
        verifyNoInteractions(tokenService);
    }

    // ── confirm (D9, D10) ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(ModerationAction.class)
    @DisplayName("claimed + OPEN report → resolve(oldest OPEN report, action, fixed note) → DONE_*")
    void should_resolveOldestReport_when_claimed(ModerationAction action) {
        stubClaim(action);
        stubResolve(action, ReportResolution.DISMISSED);

        ModerationActionOutcome outcome = service.confirm(TOKEN, NONCE);

        assertThat(outcome).isEqualTo(ModerationActionOutcome.doneFor(action));
        verify(moderationService).resolve(REPORT_ID, new ResolveReportRequest(action, "Resolved via email link"));
    }

    @Test
    @DisplayName("claim fails (unknown / used / revoked / expired / nonce mismatch) → INVALID, no resolve")
    void should_beInvalid_when_claimFails() {
        when(tokenService.claim(TOKEN, NONCE)).thenReturn(Optional.empty());

        assertThat(service.confirm(TOKEN, NONCE)).isEqualTo(ModerationActionOutcome.INVALID);
        verifyNoInteractions(moderationService);
    }

    @Test
    @DisplayName("missing / malformed token or nonce → INVALID without a claim")
    void should_beInvalid_when_paramsMalformed() {
        assertThat(service.confirm(null, NONCE)).isEqualTo(ModerationActionOutcome.INVALID);
        assertThat(service.confirm(TOKEN, null)).isEqualTo(ModerationActionOutcome.INVALID);
        assertThat(service.confirm(TOKEN, "x")).isEqualTo(ModerationActionOutcome.INVALID);
        verifyNoInteractions(tokenService, moderationService);
    }

    @Test
    @DisplayName("claimed but no OPEN report left → ALREADY_RESOLVED, no resolve")
    void should_beAlreadyResolved_when_noOpenReport() {
        when(tokenService.claim(TOKEN, NONCE)).thenReturn(Optional.of(
                new ClaimedToken(ReportTargetType.REVIEW, TARGET_ID, ModerationAction.REMOVE, CLAIMED_AT)));
        when(moderationService.findOldestOpenReportId(ReportTargetType.REVIEW, TARGET_ID)).thenReturn(Optional.empty());

        assertThat(service.confirm(TOKEN, NONCE)).isEqualTo(ModerationActionOutcome.ALREADY_RESOLVED);
    }

    @Test
    @DisplayName("396's 409 (concurrent resolve / REMOVED is final) → ALREADY_RESOLVED")
    void should_beAlreadyResolved_when_resolveConflicts() {
        stubClaim(ModerationAction.DISMISS);
        when(moderationService.resolve(any(), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Report already resolved"));

        assertThat(service.confirm(TOKEN, NONCE)).isEqualTo(ModerationActionOutcome.ALREADY_RESOLVED);
    }

    @Test
    @DisplayName("report purged between lookup and resolve (404) → ALREADY_RESOLVED")
    void should_beAlreadyResolved_when_reportVanished() {
        stubClaim(ModerationAction.DISMISS);
        when(moderationService.resolve(any(), any())).thenThrow(new NotFoundException("Report not found"));

        assertThat(service.confirm(TOKEN, NONCE)).isEqualTo(ModerationActionOutcome.ALREADY_RESOLVED);
    }

    @Test
    @DisplayName("audit-fix: an unexpected (non-409) resolve failure releases the claim, then propagates (FAILED page)")
    void should_releaseClaimAndRethrow_when_resolveFailsUnexpectedly() {
        stubClaim(ModerationAction.REMOVE);
        IllegalStateException boom = new IllegalStateException("R2 down");
        when(moderationService.resolve(any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> service.confirm(TOKEN, NONCE)).isSameAs(boom);

        verify(tokenService).releaseClaim(TOKEN,
                new ClaimedToken(ReportTargetType.REVIEW, TARGET_ID, ModerationAction.REMOVE, CLAIMED_AT));
    }

    @Test
    @DisplayName("audit-fix: a non-409 BusinessException (e.g. 400) also releases the claim")
    void should_releaseClaim_when_resolveAnswers400() {
        stubClaim(ModerationAction.RESTORE);
        when(moderationService.resolve(any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "RESTORE applies to reviews only"));

        assertThatThrownBy(() -> service.confirm(TOKEN, NONCE)).isInstanceOf(BusinessException.class);

        verify(tokenService).releaseClaim(any(), any());
    }

    @Test
    @DisplayName("a 409 is an outcome, not a failure: the claim stays consumed")
    void should_keepClaim_when_conflict() {
        stubClaim(ModerationAction.DISMISS);
        when(moderationService.resolve(any(), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Report already resolved"));

        service.confirm(TOKEN, NONCE);

        verify(tokenService, never()).releaseClaim(any(), any());
    }

    @Test
    @DisplayName("resolution TARGET_GONE → CONTENT_GONE")
    void should_beContentGone_when_targetGone() {
        stubClaim(ModerationAction.REMOVE);
        stubResolve(ModerationAction.REMOVE, ReportResolution.TARGET_GONE);

        assertThat(service.confirm(TOKEN, NONCE)).isEqualTo(ModerationActionOutcome.CONTENT_GONE);
    }

    @Test
    @DisplayName("D15: the raw token and nonce never reach a log line; the target id is cut to 8 chars")
    void should_notLogTokenOrNonce_when_confirming(CapturedOutput output) {
        stubClaim(ModerationAction.REMOVE);
        stubResolve(ModerationAction.REMOVE, ReportResolution.REMOVED);

        service.confirm(TOKEN, NONCE);
        when(tokenService.claim(TOKEN, NONCE)).thenReturn(Optional.empty());
        service.confirm(TOKEN, NONCE);

        assertThat(output.getAll()).contains("0123abcd", "DONE_REMOVED", "INVALID")
                .doesNotContain(TOKEN).doesNotContain(NONCE).doesNotContain(TARGET_ID.toString());
    }

    private void stubMint(ReportTargetType type, List<ModerationAction> expected) {
        Map<ModerationAction, String> raw = new EnumMap<>(ModerationAction.class);
        expected.forEach(action -> raw.put(action, "raw-" + action));
        when(tokenService.mint(org.mockito.ArgumentMatchers.eq(type), org.mockito.ArgumentMatchers.eq(TARGET_ID),
                anyList())).thenAnswer(invocation -> {
                    assertThat(invocation.<List<ModerationAction>>getArgument(2))
                            .containsExactlyElementsOf(expected);
                    return raw;
                });
    }

    private void stubClaim(ModerationAction action) {
        when(tokenService.claim(TOKEN, NONCE)).thenReturn(Optional.of(
                new ClaimedToken(ReportTargetType.REVIEW, TARGET_ID, action, CLAIMED_AT)));
        when(moderationService.findOldestOpenReportId(ReportTargetType.REVIEW, TARGET_ID))
                .thenReturn(Optional.of(REPORT_ID));
    }

    private void stubResolve(ModerationAction action, ReportResolution resolution) {
        when(moderationService.resolve(REPORT_ID, new ResolveReportRequest(action, "Resolved via email link")))
                .thenReturn(new ResolveReportResponse(ReportTargetType.REVIEW, TARGET_ID, resolution, 1));
    }
}
