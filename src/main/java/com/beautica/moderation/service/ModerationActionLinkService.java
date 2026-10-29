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
import com.beautica.moderation.service.ModerationActionTokenService.ClaimedToken;
import com.beautica.moderation.service.ModerationActionTokenService.ConfirmPageState;
import com.beautica.moderation.service.ModerationActionTokenService.IssuedNonce;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phase 414 — one-click moderation from the support e-mail. A NON-transactional facade (D9): each step below runs in
 * its own transaction ({@link ModerationActionTokenService}, then the existing phase-396
 * {@link ModerationService#resolve}), so a 409 from resolve can never roll back, or poison, the single-use claim.
 * There is no parallel resolve path: the e-mail button resolves exactly as {@code POST /internal/.../resolve} does.
 *
 * <p>Telemetry (D15): log lines carry the target type, the first 8 characters of the target id, the action and the
 * outcome only — never the token, the nonce, the link, UGC or names.
 */
@Service
@RequiredArgsConstructor
public class ModerationActionLinkService {

    private static final Logger log = LoggerFactory.getLogger(ModerationActionLinkService.class);

    /** D9: the resolution note stored on every report closed through an e-mail link. */
    static final String RESOLUTION_NOTE = "Resolved via email link";
    /** D6: the link path; the raw token is the trailing segment. */
    public static final String ACTION_PATH = "/api/v1/moderation/actions/";
    /** 32 random bytes base64url = 43 chars; anything else cannot be ours and is rejected before hashing. */
    private static final Pattern RAW_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int TARGET_ID_LOG_PREFIX = 8;

    private final ModerationActionTokenService tokenService;
    private final ModerationService moderationService;
    private final PublicBaseUrlProperties publicBaseUrl;

    /** The GET page: either the confirm form (nonce issued) or a D10 outcome page. */
    public sealed interface ConfirmView permits ConfirmForm, OutcomeView {}

    public record ConfirmForm(String token, IssuedNonce nonce) implements ConfirmView {}

    public record OutcomeView(ModerationActionOutcome outcome) implements ConfirmView {}

    /**
     * D3 + D5: mints this e-mail's buttons — REMOVE and DISMISS always, RESTORE only for a review / client review that
     * is {@code HIDDEN_PENDING} at render time.
     */
    public List<ModerationActionLink> mintFor(ReportTargetType targetType, UUID targetId, boolean hiddenPending) {
        List<ModerationAction> actions = new ArrayList<>(List.of(ModerationAction.REMOVE, ModerationAction.DISMISS));
        if (hiddenPending && isReview(targetType)) {
            actions.add(ModerationAction.RESTORE);
        }
        Map<ModerationAction, String> rawTokens = tokenService.mint(targetType, targetId, actions);
        return actions.stream()
                .map(action -> new ModerationActionLink(action, linkFor(rawTokens.get(action))))
                .toList();
    }

    /** D7/D8: the GET. Never acts; at most rotates the confirm nonce. */
    public ConfirmView renderConfirm(String token) {
        if (!isRawToken(token)) {
            return new OutcomeView(ModerationActionOutcome.INVALID);
        }
        ConfirmPageState state = tokenService.issueNonce(token);
        return switch (state) {
            case ModerationActionTokenService.Issued issued -> new ConfirmForm(token, issued.nonce());
            case ModerationActionTokenService.Unusable unusable -> new OutcomeView(ModerationActionOutcome.INVALID);
            case ModerationActionTokenService.NothingOpen nothing ->
                    new OutcomeView(ModerationActionOutcome.ALREADY_RESOLVED);
        };
    }

    /** D9: the POST — claim, pick the oldest OPEN report, run the phase-396 resolve, map to a D10 outcome. */
    public ModerationActionOutcome confirm(String token, String nonce) {
        if (!isRawToken(token) || !isRawToken(nonce)) {
            return ModerationActionOutcome.INVALID;
        }
        Optional<ClaimedToken> claimed = tokenService.claim(token, nonce);
        if (claimed.isEmpty()) {
            log.info("Moderation link: outcome {}", ModerationActionOutcome.INVALID);
            return ModerationActionOutcome.INVALID;
        }
        ClaimedToken target = claimed.get();
        ModerationActionOutcome outcome = resolveOrRelease(token, target);
        log.info("Moderation link: {} {} action {} outcome {}", target.targetType(),
                target.targetId().toString().substring(0, TARGET_ID_LOG_PREFIX), target.action(), outcome);
        return outcome;
    }

    /**
     * Audit-fix: an unexpected failure (anything but the mapped 404 / 409) releases the claim before propagating, so
     * the link stays usable for a retry; the controller renders the FAILED page.
     */
    private ModerationActionOutcome resolveOrRelease(String token, ClaimedToken target) {
        try {
            return resolve(target);
        } catch (RuntimeException ex) {
            tokenService.releaseClaim(token, target);
            log.warn("Moderation link: {} {} action {} failed ({}), claim released", target.targetType(),
                    target.targetId().toString().substring(0, TARGET_ID_LOG_PREFIX), target.action(),
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private ModerationActionOutcome resolve(ClaimedToken target) {
        Optional<UUID> reportId = moderationService.findOldestOpenReportId(target.targetType(), target.targetId());
        if (reportId.isEmpty()) {
            return ModerationActionOutcome.ALREADY_RESOLVED;
        }
        try {
            ResolveReportResponse response = moderationService.resolve(
                    reportId.get(), new ResolveReportRequest(target.action(), RESOLUTION_NOTE));
            return switch (response.resolution()) {
                case TARGET_GONE -> ModerationActionOutcome.CONTENT_GONE;
                // A confirmed profile report: nothing was deleted, so never the «видалено» page.
                case ACTIONED -> ModerationActionOutcome.DONE_ACTIONED;
                default -> ModerationActionOutcome.doneFor(target.action());
            };
        } catch (NotFoundException ex) {
            // The report row was purged between the lookup and the resolve: nothing left to act on.
            return ModerationActionOutcome.ALREADY_RESOLVED;
        } catch (BusinessException ex) {
            if (ex.getStatus() == HttpStatus.CONFLICT) {
                return ModerationActionOutcome.ALREADY_RESOLVED;
            }
            throw ex;
        }
    }

    private String linkFor(String rawToken) {
        return publicBaseUrl.getPublicBaseUrl() + ACTION_PATH + rawToken;
    }

    private static boolean isRawToken(String value) {
        return value != null && RAW_TOKEN.matcher(value).matches();
    }

    private static boolean isReview(ReportTargetType type) {
        return type == ReportTargetType.REVIEW || type == ReportTargetType.CLIENT_REVIEW;
    }
}
