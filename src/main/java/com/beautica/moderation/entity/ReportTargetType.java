package com.beautica.moderation.entity;

/**
 * What a content report points at (phase 395 D1). {@code targetId} is interpreted per constant:
 * <ul>
 *   <li>{@link #REVIEW} — {@code reviews.id} (client to provider review).</li>
 *   <li>{@link #CLIENT_REVIEW} — {@code client_reviews.id} (provider to client review).</li>
 *   <li>{@link #PORTFOLIO_PHOTO} — {@code media_files.id} of a {@code PORTFOLIO} row.</li>
 *   <li>{@link #MASTER_PROFILE} — {@code masters.id}.</li>
 *   <li>{@link #SALON_PROFILE} — {@code salons.id}.</li>
 *   <li>{@link #CLIENT_PROFILE} — {@code users.id} of the client.</li>
 * </ul>
 * Booking notes are deliberately not reportable (visible to the two booking parties only).
 * Mirrors {@code chk_content_reports_target_type} (V207).
 */
public enum ReportTargetType {
    REVIEW,
    CLIENT_REVIEW,
    PORTFOLIO_PHOTO,
    MASTER_PROFILE,
    SALON_PROFILE,
    CLIENT_PROFILE;

    /**
     * The one "is this a profile?" rule (master, salon or client profile): a REMOVE only confirms the report
     * ({@code ACTIONED}), and the e-mail / confirm pages label it «Підтвердити скаргу».
     */
    public boolean isProfile() {
        return this == MASTER_PROFILE || this == SALON_PROFILE || this == CLIENT_PROFILE;
    }
}
