package com.beautica.moderation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Phase 397 D2 — the block entry point is always a booking; the server derives the target from it. The app never
 * sends a phone or a user id.
 */
public record CreateBlockRequest(
        @NotNull
        UUID bookingId
) {}
