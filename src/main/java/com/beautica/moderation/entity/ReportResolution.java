package com.beautica.moderation.entity;

/**
 * How a {@link ReportStatus#RESOLVED} report was closed (phase 396). Mirrors {@code chk_content_reports_resolution}
 * (V208).
 * <ul>
 *   <li>{@link #REMOVED} — support took the content down (review/client review {@code REMOVED}, photo deleted).</li>
 *   <li>{@link #RESTORED} — support put an auto-hidden review back.</li>
 *   <li>{@link #DISMISSED} — reports closed, content untouched (an auto-hidden review becomes visible again).</li>
 *   <li>{@link #ACTIONED} — a profile REMOVE: support contacts the owner manually.</li>
 *   <li>{@link #TARGET_GONE} — the target no longer exists (D7 retention job, or found gone at resolve time).</li>
 * </ul>
 */
public enum ReportResolution {
    REMOVED,
    RESTORED,
    DISMISSED,
    ACTIONED,
    TARGET_GONE
}
