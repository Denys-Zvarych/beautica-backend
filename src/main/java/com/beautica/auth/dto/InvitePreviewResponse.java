package com.beautica.auth.dto;

import com.beautica.auth.Role;

import java.time.Instant;

public record InvitePreviewResponse(
        String invitedEmail,
        Role role,
        Instant expiresAt
) {}
