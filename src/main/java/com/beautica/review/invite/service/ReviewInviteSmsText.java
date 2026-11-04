package com.beautica.review.invite.service;

import com.beautica.review.invite.config.ReviewInviteProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 400 D5 / 435 — the review-invite SMS (phase 435 copy, user decision 2026-11-04):
 * <pre>
 * Beautica: Дякуємо, що обрали наш сервіс. Залиште свій відгук про Ваш візит!
 *
 * https://beautica.app/r/{token}
 * </pre>
 * 122 chars with the 22-char token, two UCS-2 segments. The copy is the config key {@code app.booking.review-invite.sms-text}; its two-segment budget is enforced
 * at bind time by {@link ReviewInviteProperties}. No provider text, no guest data.
 * The rendered text carries the raw token, so it is never logged.
 */
@Component
public class ReviewInviteSmsText {

    private final String template;

    public ReviewInviteSmsText(ReviewInviteProperties properties) {
        this.template = properties.smsText();
    }

    public String render(String rawToken) {
        if (rawToken == null || rawToken.length() != ReviewInviteProperties.TOKEN_LENGTH) {
            throw new IllegalArgumentException("Review invite token must be " + ReviewInviteProperties.TOKEN_LENGTH
                    + " chars");
        }
        return template.replace(ReviewInviteProperties.TOKEN_PLACEHOLDER, rawToken);
    }
}
