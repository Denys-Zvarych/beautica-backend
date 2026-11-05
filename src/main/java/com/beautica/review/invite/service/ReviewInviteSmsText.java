package com.beautica.review.invite.service;

import com.beautica.review.invite.config.ReviewInviteProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 400 D5 / 435 — the review-invite SMS (phase 435 copy, user decision 2026-11-04):
 * <pre>
 * Beautica: Дякуємо, що обрали наш сервіс. Залиште свій відгук про Ваш візит!
 *
 * {app.frontend.base-url}/r/{token}
 * </pre>
 * The copy is {@link ReviewInviteProperties#SMS_COPY} (code, not config); the link origin is
 * {@code app.frontend.base-url} ({@code FRONTEND_BASE_URL}), as in {@code GuestCancelUrlBuilder}. In prod
 * ({@value ReviewInviteProperties#PROD_ORIGIN}) it renders to 122 chars with the 22-char token — two UCS-2 segments.
 * The two-segment budget ({@value ReviewInviteProperties#MAX_UCS2_CHARS}) is checked once at construction: an
 * overrun fails the boot on the prod origin and is only warned about on a longer non-prod (staging) origin, so staging
 * still sends the full copy. No provider text, no guest data. The rendered text carries the raw token, so neither it
 * nor the text is ever logged.
 */
@Slf4j
@Component
public class ReviewInviteSmsText {

    private final String linkPrefix;

    public ReviewInviteSmsText(@Value("${app.frontend.base-url}") String frontendBaseUrl) {
        // Stripped like EnvironmentFingerprintGuard#checkProdReviewInviteLink, so the guard and the link agree.
        String origin = frontendBaseUrl.strip();
        this.linkPrefix = ReviewInviteProperties.SMS_COPY + origin + ReviewInviteProperties.REVIEW_PATH;
        requireWithinBudget(ReviewInviteProperties.SMS_COPY, origin);
    }

    public String render(String rawToken) {
        if (rawToken == null || rawToken.length() != ReviewInviteProperties.TOKEN_LENGTH) {
            throw new IllegalArgumentException("Review invite token must be " + ReviewInviteProperties.TOKEN_LENGTH
                    + " chars");
        }
        return linkPrefix + rawToken;
    }

    /**
     * Two-segment budget check, package-private and copy-parameterised for tests. The prod-origin throw is
     * unreachable with today's copy (122 chars); it guards future copy growth from silently going to three segments.
     */
    static void requireWithinBudget(String copy, String origin) {
        int rendered = copy.length() + origin.length() + ReviewInviteProperties.REVIEW_PATH.length()
                + ReviewInviteProperties.TOKEN_LENGTH;
        if (rendered <= ReviewInviteProperties.MAX_UCS2_CHARS) {
            return;
        }
        if (ReviewInviteProperties.PROD_ORIGIN.equals(origin)) {
            throw new IllegalStateException("review-invite SMS renders to " + rendered
                    + " chars; two UCS-2 segments are " + ReviewInviteProperties.MAX_UCS2_CHARS);
        }
        log.warn("event=review_invite_sms rule=two-segment-budget length={} (non-prod origin, not enforced)", rendered);
    }
}
