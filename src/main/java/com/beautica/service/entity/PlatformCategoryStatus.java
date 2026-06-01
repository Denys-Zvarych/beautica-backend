package com.beautica.service.entity;

/**
 * Lifecycle status of a {@link PlatformCategory}.
 *
 * <ul>
 *   <li>{@link #PENDING} — a self-service request awaiting platform-admin decision.
 *       The row is {@code active = false} and carries a hashed single-use token.</li>
 *   <li>{@link #APPROVED} — selectable platform-wide. The 7 seeds and every
 *       ops-created (internal CRUD) category are APPROVED.</li>
 *   <li>{@link #REJECTED} — declined by the admin; never selectable.</li>
 * </ul>
 */
public enum PlatformCategoryStatus {
    PENDING,
    APPROVED,
    REJECTED
}
