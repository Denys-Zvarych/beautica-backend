package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Phase 375 — which public booking page a {@link BookingLinkResponse} points at. */
@Schema(description = "MASTER = the caller's own master booking page; SALON = a salon booking page (phase 376)")
public enum BookingLinkKind {
    MASTER,
    SALON
}
