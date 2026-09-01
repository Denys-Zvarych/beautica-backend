package com.beautica.user;

import com.beautica.auth.Role;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of {@link InviteToken}, carrying EXACTLY the columns the salon invite
 * history needs — the id and the five inputs of the status-derivation ladder plus the two
 * timestamps the DTO renders. Materialised by
 * {@link InviteTokenRepository#findSalonInviteHistory(UUID, org.springframework.data.domain.Pageable)}
 * via a JPQL constructor expression.
 *
 * <p><strong>Why a projection and not the entity.</strong> The history listing returns up to 200
 * rows and reads none of {@code InviteToken}'s remaining state. Loading entities would pull
 * {@code invite_tokens.token} — a 64-character SHA-256 digest of a live credential — into the heap
 * and into the persistence context on a path that can never legitimately touch it. Roughly 13 KB
 * of pure waste per full page, plus 200 managed entities dirty-checked at flush on a
 * {@code readOnly} transaction. Projecting keeps token material off the read path entirely, which
 * is a defence-in-depth complement to {@code @JsonIgnore} on {@link InviteToken#getToken()} rather
 * than a replacement for it.
 *
 * <p><strong>Field set is load-bearing.</strong> {@code used}, {@code revokedReason} and
 * {@code expiresAt} are precisely the three inputs
 * {@code com.beautica.salon.dto.SalonInviteResponse#from} needs to run its ladder. Dropping any of
 * them would force the ladder to guess. Nothing here is derived — the derivation stays in the DTO
 * so it is evaluated against ONE hoisted {@code now} for the whole page.
 *
 * @param id            invite id, passed back to {@code DELETE /salons/{salonId}/invites/{id}}
 * @param email         recipient address
 * @param role          role the invite grants
 * @param used          {@code is_used} — set by BOTH acceptance and cancellation, hence ambiguous
 *                      on its own; see {@code SalonInviteResponse#from}
 * @param revokedReason {@code null} while live, otherwise what retired the row
 * @param expiresAt     the invite deadline
 * @param createdAt     dispatch instant — the sort key of the history listing
 */
public record InviteHistoryRow(
        UUID id,
        String email,
        Role role,
        boolean used,
        RevocationReason revokedReason,
        Instant expiresAt,
        Instant createdAt
) {
}
