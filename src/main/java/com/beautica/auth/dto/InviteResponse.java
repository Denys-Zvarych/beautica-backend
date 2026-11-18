package com.beautica.auth.dto;

import java.time.Instant;

/**
 * Response of both invite-create endpoints (201). B520: {@code invitedPhone} is the normalised {@code +380…} number
 * the SMS goes to — echoed to the inviter who typed it, so the app can show what was actually used.
 */
public record InviteResponse(
        String invitedPhone,
        Instant expiresAt
) {}
