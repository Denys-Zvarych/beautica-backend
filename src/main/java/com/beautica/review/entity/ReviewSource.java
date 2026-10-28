package com.beautica.review.entity;

/**
 * Phase 399 D1 — who wrote a {@link Review}. Mirrors {@code chk_reviews_source} / {@code chk_reviews_source_author}
 * (V212).
 *
 * <ul>
 *   <li>{@link #APP} — a CLIENT account in the app; the default. {@code client} may still be {@code null} after the
 *       author's account self-delete (phase 300 D3).</li>
 *   <li>{@link #GUEST_LINK} — a LINK guest through an SMS review invite (phases 400-401): no account, the author
 *       name is the {@code guestDisplayName} snapshot.</li>
 * </ul>
 */
public enum ReviewSource {
    APP,
    GUEST_LINK
}
