package com.beautica.review.invite.dto;

import jakarta.validation.constraints.Size;

/**
 * Phase 401 D6 — {@code POST /api/v1/public/review-invites/{token}/opt-out}.
 *
 * @param turnstileToken the Cloudflare Turnstile response (action {@code review_submit}); never logged
 */
public record ReviewInviteOptOutRequest(

        @Size(max = 2048, message = "Verification failed")
        String turnstileToken

) {

    @Override
    public String toString() {
        return "ReviewInviteOptOutRequest[turnstileToken=<redacted>]";
    }
}
