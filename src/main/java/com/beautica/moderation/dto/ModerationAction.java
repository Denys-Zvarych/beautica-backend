package com.beautica.moderation.dto;

/**
 * Support's decision on a reported target (phase 396 D4) — {@code POST /internal/moderation/reports/{id}/resolve}.
 * <ul>
 *   <li>{@link #REMOVE} — review / client review {@code REMOVED}; portfolio photo deleted (R2 + row); profiles
 *       {@code ACTIONED} only (support contacts the owner).</li>
 *   <li>{@link #RESTORE} — a review back to {@code VISIBLE}; reviews and client reviews only.</li>
 *   <li>{@link #DISMISS} — reports closed, content untouched (an auto-hidden review becomes visible again).</li>
 * </ul>
 */
public enum ModerationAction {
    REMOVE,
    RESTORE,
    DISMISS
}
