package com.beautica.review.repository;

import java.util.UUID;

/**
 * Phase 402 D1 — a {@code GUEST_LINK} review whose author label is about to be anonymised, with the provider keys
 * whose cached public review views must be evicted after commit ({@code salonId} {@code null} for an independent
 * master).
 */
public interface GuestReviewRef {

    UUID getId();

    UUID getMasterId();

    UUID getSalonId();
}
