package com.beautica.booking.dto;

import java.util.List;

/**
 * Public payload for {@code GET /api/v1/book/{slug}/info} — the unauthenticated
 * guest booking page (Phase 13.1).
 *
 * <p>Anti-Bug §I-2: a {@code permitAll} response, so it exposes only the master's
 * display name, avatar, bio and the bookable services — never the master/user/
 * salon UUIDs or owner type. {@code masterName} is the composed "First Last".
 */
public record BookingSlugInfoResponse(
        String masterName,
        String avatarUrl,
        String bio,
        List<ServiceSummaryDto> services
) {
}
