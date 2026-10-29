package com.beautica.moderation.entity;

/**
 * Lifecycle of a content report. Every report is created {@link #OPEN}; phase 396's moderation actions move it
 * to {@link #RESOLVED}. Mirrors {@code chk_content_reports_status} (V207).
 */
public enum ReportStatus {
    OPEN,
    RESOLVED
}
