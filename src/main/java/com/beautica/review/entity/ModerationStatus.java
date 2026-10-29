package com.beautica.review.entity;

/**
 * Phase 396 D1 — moderation state of a {@link Review} or {@link ClientReview}. Mirrors
 * {@code chk_reviews_moderation_status} / {@code chk_client_reviews_moderation_status} (V208).
 *
 * <ul>
 *   <li>{@link #VISIBLE} — the default; the only state public reads and rating aggregates include.</li>
 *   <li>{@link #HIDDEN_PENDING} — auto-hidden by reports, waiting for support (restorable).</li>
 *   <li>{@link #REMOVED} — taken down by support; terminal.</li>
 * </ul>
 * Every transition fires a rating recalculation (D3).
 */
public enum ModerationStatus {
    VISIBLE,
    HIDDEN_PENDING,
    REMOVED
}
