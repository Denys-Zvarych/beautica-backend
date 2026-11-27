package com.beautica.client.provider.dto;

import com.beautica.common.RatingBucket;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * B508 — one client as the viewing provider sees them (CLI-42..44). Own scope only (D4): the counts, dates and visits
 * are the viewer's; the only cross-provider data are the rating number, count and 5-to-1 distribution of visible ratings (B526, supersedes
 * B508 D7) — never a comment (REV-D10).
 *
 * @param userId         REGISTERED only
 * @param guestBookingId GUEST only: the guest's latest booking in the viewer's scope (a stable handle whichever of
 *                       that guest's bookings opened the card)
 * @param displayName    null when blank (the app renders «Гість»)
 * @param avatarUrl      REGISTERED only
 * @param phone          E.164, only for the independent master / salon owner / assigned salon admin (GST-D14); never
 *                       for a SALON_MASTER. A GUEST's phone, or a REGISTERED client's SMS-VERIFIED phone (B518, GST-80); null otherwise
 * @param ratingAvg      REGISTERED only; null when unrated, always null for a guest
 * @param ratingCount    visible ratings received; 0 when unrated and always 0 for a guest
 * @param ratingDistribution always 5 buckets (5 down to 1), zero-filled; all zeros for a guest
 * @param firstVisitAt   earliest booking start in scope, any status
 * @param servedByMasters CLI-73: SALON scope only — the salon's masters with at least one booking of this client (any
 *                       status), most recent booking first, salon-wide whatever {@code ?masterId=}; always empty for
 *                       the independent-master and SALON_MASTER cards
 * @param firstName      B550 (CLI-79): REGISTERED = the account first name; GUEST = the guest name of the card's named
 *                       booking (the same booking as {@code lastName}); null when blank. Added last: additive only
 * @param lastName       B550 (CLI-79): REGISTERED = the account last name; GUEST = the guest surname of that same
 *                       booking; null when blank
 * @param nextVisit      B530 (CLI-89): the visit (visit-history row shape) holding the earliest CONFIRMED booking
 *                       that starts after now in the caller's scope ({@code ?masterId=} honoured); null when none.
 *                       Added last: additive only
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
        int ratingCount,
        List<RatingBucket> ratingDistribution,
        OffsetDateTime firstVisitAt,
        OffsetDateTime lastVisitAt,
        OffsetDateTime nextVisitAt,
        Stats stats,
        List<ServedMaster> servedByMasters,
        String firstName,
        String lastName,
        @Schema(nullable = true, description = "Visit holding the earliest CONFIRMED booking starting after now in "
                + "the caller's scope (masterId honoured); null when none")
        ClientVisitResponse nextVisit
) {

    public ClientCardResponse {
        servedByMasters = servedByMasters == null ? List.of() : List.copyOf(servedByMasters);
    }

    /** Redacted: the generated record {@code toString} would print the client's phone and name in clear. */
    @Override
    public String toString() {
        return "ClientCardResponse[kind=" + kind + ", userId=" + userId + ", guestBookingId=" + guestBookingId
                + ", phone=" + (phone == null ? "null" : "+380***") + "]";
    }

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

    /**
     * CLI-73 — one master chip on the salon card. {@code name} follows the visit items' master name (a removed staff
     * account shows its kept name snapshot); {@code avatarUrl} is null for such a master.
     */
    @Schema(description = "A salon master who has served this client; the chip's masterId is a valid ?masterId= filter.")
    public record ServedMaster(UUID masterId, String name, String avatarUrl) {
    }
}
