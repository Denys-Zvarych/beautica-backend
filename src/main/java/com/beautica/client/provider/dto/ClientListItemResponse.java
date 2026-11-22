package com.beautica.client.provider.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * B507 — one row of the provider's client list (CLI-40). Deliberately carries NO phone, e-mail or comment text: the
 * guest phone is used server-side only to group and to look up blocks (REV-D10: rating number + count only).
 *
 * @param userId         REGISTERED only
 * @param guestBookingId GUEST only: the guest's latest booking in scope (the card handle for B508)
 * @param displayName    null when blank (the app renders «Гість»)
 * @param avatarUrl      REGISTERED only
 * @param ratingAvg      REGISTERED only; null when the client has no visible review
 */
public record ClientListItemResponse(
        ClientKind kind,
        UUID userId,
        UUID guestBookingId,
        String displayName,
        String avatarUrl,
        OffsetDateTime lastVisitAt,
        OffsetDateTime nextVisitAt,
        int completedVisits,
        BigDecimal ratingAvg,
        int ratingCount,
        boolean blocked
) {
}
