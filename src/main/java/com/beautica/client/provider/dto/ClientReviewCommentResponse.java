package com.beautica.client.provider.dto;

import java.util.UUID;

/**
 * B565 — one comment about a client, anonymous (REV-D10, REV-44). Exactly five keys: the review id (the
 * {@code CLIENT_REVIEW} report target, MOD-41), the visit month {@code YYYY-MM} (Europe/Kyiv), stars, text and
 * {@code own} (the viewer's scope wrote it — reveals nothing to anyone else). Never the author, salon, master, service
 * or exact date; never on a client-facing response (REV-13).
 */
public record ClientReviewCommentResponse(UUID reviewId, String visitMonth, int stars, String comment, boolean own) {
}
