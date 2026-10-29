package com.beautica.notification.inapp.dto;

/**
 * Phase 415 D3 — the copy variant of a moderation-outcome feed item ({@link NotificationParams#contentKind()}).
 * Mirrors {@code ReportTargetType}, with the three profile targets collapsed into {@link #PROFILE}.
 */
public enum ModeratedContentKind {
    REVIEW,
    CLIENT_REVIEW,
    PORTFOLIO_PHOTO,
    PROFILE;

    /**
     * Maps the stored {@code moderated_target_type} literal; {@code null} for null or an unknown literal (the DB
     * CHECK {@code chk_inapp_moderated_target_type} makes the latter unreachable).
     */
    public static ModeratedContentKind fromTargetType(String targetType) {
        if (targetType == null) {
            return null;
        }
        return switch (targetType) {
            case "REVIEW" -> REVIEW;
            case "CLIENT_REVIEW" -> CLIENT_REVIEW;
            case "PORTFOLIO_PHOTO" -> PORTFOLIO_PHOTO;
            case "MASTER_PROFILE", "SALON_PROFILE", "CLIENT_PROFILE" -> PROFILE;
            default -> null;
        };
    }
}
