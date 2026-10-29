package com.beautica.review.invite.service;

import com.beautica.review.invite.config.ReviewInviteProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 400 D5 — the review-invite SMS: {@code Оцініть ваш візит: beautica.app/r/{token}} (56 chars with the 22-char
 * token, one UCS-2 segment). The copy is the config key {@code app.booking.review-invite.sms-text}; its
 * single-segment budget is enforced at bind time by {@link ReviewInviteProperties}. No provider text, no guest data.
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
