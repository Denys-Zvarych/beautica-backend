package com.beautica.client.provider.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * B508 — one client as the viewing provider sees them (CLI-42..44). Own scope only (D4): the counts, dates and visits
 * are the viewer's; the only cross-provider datum is the rating number — NO review count, no comment (D7).
 *
 * @param userId         REGISTERED only
 * @param guestBookingId GUEST only: the guest's latest booking in the viewer's scope (a stable handle whichever of
 *                       that guest's bookings opened the card)
 * @param displayName    null when blank (the app renders «Гість»)
 * @param avatarUrl      REGISTERED only
 * @param phone          E.164, only for the independent master / salon owner / assigned salon admin (GST-D14); never
 *                       for a SALON_MASTER. A GUEST's phone; a REGISTERED client's stays null until SMS-verified (B514)
 * @param ratingAvg      REGISTERED only; null when unrated, always null for a guest
 * @param firstVisitAt   earliest booking start in scope, any status
 */
public record ClientCardResponse(
        ClientKind kind,
        UUID userId,
        UUID guestBookingId,
        String displayName,
        String avatarUrl,
        String phone,
        boolean blocked,
        BigDecimal ratingAvg,
        OffsetDateTime firstVisitAt,
        OffsetDateTime lastVisitAt,
        OffsetDateTime nextVisitAt,
        Stats stats
) {

    /**
     * Exactly three counts (D3); a cancelled / declined / no-show / legacy NOT_COMPLETED visit counts only in
     * {@code totalVisits}.
     *
     * @param totalVisits     distinct visits in scope, any status
     * @param completedVisits visits with at least one COMPLETED row (a partly cancelled visit with a COMPLETED row counts)
     * @param upcomingVisits  visits with at least one CONFIRMED row starting after now
     */
    @Schema(description = "Own-scope visit counts; a visit = one appointment (or one single booking). A partly "
            + "cancelled visit that still has a COMPLETED row counts in completedVisits.")
    public record Stats(int totalVisits, int completedVisits, int upcomingVisits) {
    }
}
