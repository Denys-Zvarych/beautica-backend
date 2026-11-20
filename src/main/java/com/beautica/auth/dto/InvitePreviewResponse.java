package com.beautica.auth.dto;

import com.beautica.auth.Role;

import java.time.Instant;

/**
 * Public {@code GET /api/v1/auth/invite/validate} (AUTH-21, B520 D3): enough for the accept screen to show who invited
 * the holder of the link, nothing that a forwarded link would leak. The invited phone is masked
 * ({@code +380 •• ••• •• 45}); the invitee types the full number at sign-up and the registration ticket proves it.
 * No ids, no email.
 */
public record InvitePreviewResponse(
        String phoneMasked,
        Role role,
        String salonName,
        Instant expiresAt
) {}
