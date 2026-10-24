package com.beautica.booking.entity;

/**
 * Phase 378 — why a booking slug was retired ({@code retired_booking_slugs.reason}, V204 CHECK).
 * {@link #DELETED} is written by phase 379 (account / salon deletion).
 */
public enum RetiredSlugReason {
    REGENERATED,
    REVOKED,
    DELETED
}
