package com.beautica.moderation.entity;

/** Why the content is reported (phase 395 D3). Mirrors {@code chk_content_reports_reason} (V207). */
public enum ReportReason {
    SPAM,
    OFFENSIVE,
    HARASSMENT,
    FALSE_INFO,
    SEXUAL_CONTENT,
    /** Requires a reporter comment of at least 5 characters. */
    OTHER
}
