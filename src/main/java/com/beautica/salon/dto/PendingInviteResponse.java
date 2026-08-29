package com.beautica.salon.dto;

import com.beautica.user.InviteToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/salons/{salonId}/invites/pending} (Phase 23.1). Deliberately
 * narrow — never exposes the token value or its hash (see {@link InviteToken#getToken()}, which
 * is {@code @JsonIgnore}'d on the entity itself as a second layer of defense). {@code role} is
 * the raw {@link com.beautica.auth.Role} enum name (e.g. {@code "SALON_ADMIN"}), matching the
 * wire convention already used for role fields elsewhere (Anti-Bug §A — no enum surface leak
 * risk here since these are the caller's OWN pending invites, not an error message).
 */
public record PendingInviteResponse(
        UUID inviteId,
        String recipientEmail,
        String role,
        Instant createdAt,
        Instant expiresAt
) {
    public static PendingInviteResponse from(InviteToken token) {
        return new PendingInviteResponse(
                token.getId(),
                token.getEmail(),
                token.getRole().name(),
                token.getCreatedAt(),
                token.getExpiresAt());
    }
}
