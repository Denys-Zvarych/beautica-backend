package com.beautica.auth.dto;

import java.time.Instant;

public record InviteResponse(
        String invitedEmail,
        Instant expiresAt
) {}
