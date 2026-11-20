package com.beautica.review.service;

import com.beautica.common.RatingBucket;

import java.math.BigDecimal;
import java.util.List;

/**
 * B526 — a client's aggregate rating: {@code avg} is null when never reviewed, {@code distribution} is always 5
 * buckets (5 down to 1), zero-filled. Visible ratings only.
 */
public record ClientRatingSummary(BigDecimal avg, int count, List<RatingBucket> distribution) {
}
