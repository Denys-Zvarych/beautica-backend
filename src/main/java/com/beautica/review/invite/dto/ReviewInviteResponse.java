package com.beautica.review.invite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 401 — {@code GET /api/v1/public/review-invites/{token}}. Either the open form ({@link #STATE_OPEN}, every
 * field set) or a spent token ({@link #STATE_ALREADY_REVIEWED}, only {@code state}; website 019 «Відгук уже
 * залишено»). Public ({@code permitAll}): no phone, no surname, no provider/owner ids (§I-2) — the booking ids are
 * the visit's own, already known to the token holder.
 *
 * @param state        {@link #STATE_OPEN} or {@link #STATE_ALREADY_REVIEWED}
 * @param providerName the salon's name ({@code SALON}) or the master's display name ({@code MASTER})
 * @param providerType {@code MASTER} or {@code SALON}
 * @param visitDate    the visit's Kyiv calendar date (first item's start)
 * @param items        the visit's reviewable services, in start order
 * @param displayName  the «Ім'я П.» label the review will be published under
 * @param avatarUrl         the provider card's image (master: user avatar; salon: salon logo), or {@code null}
 * @param avgRating         the provider's rating; {@code null} while it has no reviews (never a fabricated 0)
 * @param reviewCount       the provider's published review count ({@code 0} when none); set on every OPEN form
 * @param professionalTitle the master's professional title; always {@code null} for a salon
 *
 * <p>The last four feed the website MasterStrip card and are sourced like the public booking-link info endpoints
 * ({@code GuestReviewService} / {@code ReviewInviteProviderCard}); a deactivated, detached or closed provider gets
 * {@code null}/{@code 0} for all four.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewInviteResponse(
        String state,
        String providerName,
        String providerType,
        LocalDate visitDate,
        List<ReviewInviteItemResponse> items,
        String displayName,
        @Schema(types = {"string", "null"}, nullable = true)
        String avatarUrl,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "null while the provider has no reviews (never a fabricated 0).")
        BigDecimal avgRating,
        @Schema(description = "Set on every OPEN form (0 when none); absent on ALREADY_REVIEWED.")
        Integer reviewCount,
        @Schema(types = {"string", "null"}, nullable = true, description = "Master only; null for a salon.")
        String professionalTitle
) {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_ALREADY_REVIEWED = "ALREADY_REVIEWED";
    public static final String PROVIDER_MASTER = "MASTER";
    public static final String PROVIDER_SALON = "SALON";

    public ReviewInviteResponse {
        items = items == null ? null : List.copyOf(items);
    }

    public static ReviewInviteResponse open(String providerName, String providerType, LocalDate visitDate,
                                            List<ReviewInviteItemResponse> items, String displayName,
                                            String avatarUrl, BigDecimal avgRating, int reviewCount,
                                            String professionalTitle) {
        return new ReviewInviteResponse(STATE_OPEN, providerName, providerType, visitDate, items, displayName,
                avatarUrl, avgRating, reviewCount, professionalTitle);
    }

    public static ReviewInviteResponse alreadyReviewed() {
        return new ReviewInviteResponse(STATE_ALREADY_REVIEWED, null, null, null, null, null, null, null, null, null);
    }
}
