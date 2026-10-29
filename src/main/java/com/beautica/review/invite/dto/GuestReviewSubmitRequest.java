package com.beautica.review.invite.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Phase 401 — {@code POST /api/v1/public/review-invites/{token}/reviews}: any subset of the visit's services (D4), one
 * submit per token.
 *
 * @param turnstileToken the Cloudflare Turnstile response (action {@code review_submit}); verified fail-closed by
 *                       {@code TurnstileVerifier}, ignored where Turnstile is off. Never logged
 * @param items          1–{@value #MAX_ITEMS} rated services (a visit has at most 10)
 */
public record GuestReviewSubmitRequest(

        @Size(max = 2048, message = "Verification failed")
        String turnstileToken,

        @NotNull(message = "Items are required")
        @NotEmpty(message = "Items are required")
        @Size(max = GuestReviewSubmitRequest.MAX_ITEMS, message = "Too many items")
        List<@NotNull @Valid GuestReviewItemRequest> items

) {

    public static final int MAX_ITEMS = 20;

    @Override
    public String toString() {
        return "GuestReviewSubmitRequest[turnstileToken=<redacted>, items=" + (items == null ? 0 : items.size()) + "]";
    }
}
