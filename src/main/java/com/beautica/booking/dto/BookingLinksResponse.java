package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Phase 375 — {@code GET /api/v1/booking-links/mine}. When {@code shareEnabled} is {@code false}
 * (the server-side kill-switch) {@code links} is always empty and the app hides sharing entirely.
 */
@Schema(description = "The caller's shareable public booking links")
public record BookingLinksResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Server kill-switch; false = sharing is hidden and links is empty")
        boolean shareEnabled,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "MASTER link first, then SALON links by salon name")
        List<BookingLinkResponse> links
) {

    public BookingLinksResponse {
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** The kill-switch-off response: sharing hidden, no links, no slug issued. */
    public static BookingLinksResponse disabled() {
        return new BookingLinksResponse(false, List.of());
    }
}
