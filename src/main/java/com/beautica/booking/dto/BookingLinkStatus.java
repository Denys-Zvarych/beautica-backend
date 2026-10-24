package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Phase 378 D15 — whether a {@link BookingLinkResponse} is live or switched off by its owner. */
@Schema(description = "ACTIVE = the link works (slug and url set); REVOKED = the owner switched the link off "
        + "(slug and url null, bookable false) — «Створити нове посилання» regenerates it")
public enum BookingLinkStatus {
    ACTIVE,
    REVOKED
}
