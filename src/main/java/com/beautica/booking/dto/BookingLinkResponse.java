package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Phase 375 — one shareable public booking link of the caller. Authenticated, caller-scoped
 * response: it carries only the caller's own slug and (for SALON links) their own salon id.
 */
@Schema(description = "A ready-to-share public booking link owned by the caller")
public record BookingLinkResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        BookingLinkKind kind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "olena-koval-x7k2",
                description = "Permanent public slug; never changes once issued")
        String slug,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "https://beautica.app/book/olena-koval-x7k2",
                description = "Absolute https URL, no UTM (the app appends UTM per share action): "
                        + "MASTER https://beautica.app/book/{slug}, SALON https://beautica.app/book/s/{slug}")
        String url,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Олена Коваль",
                description = "Master full name, or salon name for SALON links")
        String displayName,

        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "Salon id for SALON links; null for MASTER links")
        UUID salonId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "false = clients see the page but cannot book yet (MASTER: no active service or no "
                        + "schedule; SALON: no bookable master)")
        boolean bookable
) {
}
