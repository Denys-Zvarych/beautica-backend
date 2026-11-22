package com.beautica.client.provider.repository;

import java.time.Instant;
import java.util.UUID;

/** B526 — one visible rating row in the viewer's scope (stars only; the comment column is never selected). */
public interface ClientOwnRatingProjection {

    UUID getBookingId();

    Instant getVisitAt();

    String getServiceName();

    Integer getStars();
}
