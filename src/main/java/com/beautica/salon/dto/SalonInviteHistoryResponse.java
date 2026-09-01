package com.beautica.salon.dto;

import java.util.List;

/**
 * Payload of {@code GET /api/v1/salons/{salonId}/invites} — the invite history plus the one bit
 * the bare list could not carry: whether it is complete.
 *
 * <p><strong>Why this wrapper exists.</strong> The listing is hard-capped at
 * {@code SalonService.MAX_INVITE_HISTORY} rows and there is no pagination. Returning a bare
 * {@code List} made a truncated history indistinguishable from a complete one, so a salon past the
 * cap would be shown a silently incomplete audit trail with no way to know it — on the very
 * endpoint whose purpose is being the audit trail. {@code truncated} is the signal; the client
 * renders a "older invitations are not shown" note when it is set.
 *
 * <p><strong>Why a flag and not a total.</strong> A total would need a second {@code COUNT(*)}
 * round trip on every history load, and with no pagination the client cannot act on it — it can
 * neither request page 2 nor filter. The flag answers the only question the UI actually asks, and
 * costs nothing: {@code listSalonInvites} asks the database for {@code MAX_INVITE_HISTORY + 1} rows
 * and reports {@code truncated} when the extra row comes back, then drops it. If pagination is ever
 * added, this record is where {@code totalElements} belongs — {@code ApiResponse} stays untouched
 * so no other endpoint's envelope shifts.
 *
 * @param invites   the most recent invites, newest-first by {@code createdAt}; at most
 *                  {@code SalonService.MAX_INVITE_HISTORY} entries
 * @param truncated {@code true} when older invites exist beyond the cap and are NOT in
 *                  {@code invites}
 */
public record SalonInviteHistoryResponse(
        List<SalonInviteResponse> invites,
        boolean truncated
) {
}
