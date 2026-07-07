package com.beautica.salon.dto;

import com.beautica.user.User;

import java.util.UUID;

/**
 * Response for {@code PATCH /api/v1/salons/{salonId}/admins/{userId}/salon} (Phase 21.3
 * rotate-admin). Deliberately narrow — only the fields a caller needs to confirm the rotation
 * landed: the admin's user id, email (for display), and their new {@code salonId}. Never expose
 * the full {@link User} entity (§I) — {@code passwordHash} and other internal columns must not
 * reach the wire.
 */
public record SalonAdminResponse(
        UUID userId,
        String email,
        UUID salonId
) {
    public static SalonAdminResponse from(User user) {
        return new SalonAdminResponse(user.getId(), user.getEmail(), user.getSalonId());
    }
}
