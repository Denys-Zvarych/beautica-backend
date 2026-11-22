package com.beautica.client.provider.repository;

import java.time.Instant;
import java.util.UUID;

/** B508 — one entry of the visit page; {@code visitKey} is null on the single row of an empty page. */
public interface ClientVisitPageRowProjection {

    Long getTotal();

    UUID getVisitKey();

    Instant getStartsAt();
}
