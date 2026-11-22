package com.beautica.client.provider.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * B526 — one of the viewer's own visible ratings of a client. Exactly four keys: no comment text, author or review id
 * (REV-D10, REV-36).
 */
public record ClientOwnRatingResponse(UUID bookingId, OffsetDateTime visitAt, String serviceName, int stars) {
}
