package com.beautica.service.entity;

/**
 * Lifecycle status of a {@link ServiceTypeSuggestion}.
 *
 * <p>Mirrors {@link PlatformCategoryStatus}:
 * <ul>
 *   <li>{@link #PENDING} — a self-service suggestion awaiting platform-admin
 *       decision. The row carries a hashed single-use token.</li>
 *   <li>{@link #APPROVED} — accepted by the admin ("will be added"). NOTE: this
 *       does NOT insert a {@code service_types} row — catalog promotion is a
 *       deliberate, separately-reviewed migration step (Phase 16.8 decision).</li>
 *   <li>{@link #REJECTED} — declined by the admin.</li>
 * </ul>
 */
public enum ServiceTypeSuggestionStatus {
    PENDING,
    APPROVED,
    REJECTED
}
