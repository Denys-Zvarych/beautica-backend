package com.beautica.salon.dto;

import com.beautica.user.InviteHistoryRow;
import com.beautica.user.InviteToken;
import com.beautica.user.RevocationReason;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the salon invite HISTORY — {@code GET /api/v1/salons/{salonId}/invites}.
 *
 * <p>Deliberately narrow — never exposes the token value or its hash (see
 * {@link InviteToken#getToken()}, which is {@code @JsonIgnore}'d on the entity itself as a second
 * layer of defence). {@code role} and {@code status} are raw enum names on the wire (e.g.
 * {@code "SALON_ADMIN"}, {@code "PENDING"}), matching the wire convention already used for role
 * fields elsewhere. Anti-Bug §A's enum-surface concern does not apply: this is a successful
 * response listing the caller's OWN invites, not a parse error echoing the valid-constant set.
 *
 * @param inviteId       id to pass back to {@code DELETE /salons/{salonId}/invites/{inviteId}}
 * @param recipientEmail address the invite was sent to, verbatim. <b>There is no tombstone</b> —
 *                       this javadoc previously documented phase 291's
 *                       {@code deleted+<uuid>@beautica-deleted.invalid} rewrite, and phase 295
 *                       deleted that redaction apparatus outright along with the PII-scrub track
 *                       it belonged to (corrected by the phase 295 audit, LOW-10). Deleting a
 *                       salon now DELETES its invite rows for that salon's own staff
 *                       ({@code InviteTokenRepository#deleteBySalonIdAndStaffUserIds}), so a
 *                       scrubbed address does not appear here rewritten; it does not appear here
 *                       at all. An address still listed by this endpoint therefore belongs to a
 *                       LIVE invite of a salon the caller owns.
 * @param role           {@link com.beautica.auth.Role} name the invite grants
 * @param status         derived {@link InviteStatus} name — see {@link #from(InviteHistoryRow, Instant)}
 * @param createdAt      when the invite was dispatched (the sort key of the history listing)
 * @param expiresAt      when the invite lapses; already in the past for an EXPIRED row
 */
public record SalonInviteResponse(
        UUID inviteId,
        String recipientEmail,
        String role,
        String status,
        Instant createdAt,
        Instant expiresAt
) {

    /**
     * Derives the display status for one invite as of {@code now}.
     *
     * <p><strong>THE ORDER OF THESE FIVE CHECKS IS LOAD-BEARING.</strong> {@code isUsed} is
     * written by BOTH acceptance ({@code InviteService#acceptInvite}) and cancellation
     * ({@code InviteToken#markCancelled}, which keeps the flag so every downstream
     * "already consumed?" guard is unchanged). Testing {@code isUsed} first would therefore
     * report EVERY cancelled invite as ACCEPTED. {@link RevocationReason#CANCELLED} must be
     * checked BEFORE it.
     *
     * <p>The remaining order matters less but is not arbitrary: SUPERSEDED is checked before the
     * clock so a superseded row reads EXPIRED regardless of what {@code expiresAt} says (in
     * practice it is always already past, since only an expired token is ever superseded — but
     * the classification should not depend on that invariant holding). {@code expiresAt} is
     * compared against the caller-supplied {@code now} rather than a fresh clock read so an
     * entire page is classified against ONE instant (Anti-Bug §G; the caller hoists it).
     *
     * <p>Rung 4 delegates to {@link InviteToken#isExpired(Instant, Instant)} — the single
     * canonical expiry predicate shared with {@code InviteService#previewInvite},
     * {@code InviteService#acceptInvite}, {@code InvitePersistenceService}'s recycle filter and
     * {@code SalonService#cancelInvite}. Open-coding the comparison here is what let the display
     * boundary drift a nanosecond away from the acceptance boundary; see that method's Javadoc.
     *
     * <p><strong>Known, unrecoverable gap:</strong> rows written before V153 carry
     * {@code is_used = true} with no {@code revoked_reason}, because the distinguishing bit was
     * never recorded. Those pre-migration cancellations classify as ACCEPTED. No backfill can
     * recover the information and no heuristic should guess at it.
     *
     * @param row the projected invite row
     * @param now the single instant the whole response is classified against
     */
    public static SalonInviteResponse from(InviteHistoryRow row, Instant now) {
        return new SalonInviteResponse(
                row.id(),
                row.email(),
                row.role().name(),
                deriveStatus(row, now).name(),
                row.createdAt(),
                row.expiresAt());
    }

    private static InviteStatus deriveStatus(InviteHistoryRow row, Instant now) {
        // 1. Revoked by a human. MUST precede the used check — markCancelled sets isUsed too.
        if (row.revokedReason() == RevocationReason.CANCELLED) {
            return InviteStatus.CANCELLED;
        }
        // 2. Consumed, and not by a cancellation — so it was genuinely accepted.
        if (row.used()) {
            return InviteStatus.ACCEPTED;
        }
        // 3. Retired to free the active slot for a re-invite; never accepted.
        if (row.revokedReason() == RevocationReason.SUPERSEDED) {
            return InviteStatus.EXPIRED;
        }
        // 4. Lapsed on its own, with no write ever touching the row.
        if (InviteToken.isExpired(row.expiresAt(), now)) {
            return InviteStatus.EXPIRED;
        }
        // 5. Live and awaiting the recipient.
        return InviteStatus.PENDING;
    }
}
