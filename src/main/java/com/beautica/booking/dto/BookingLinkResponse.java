package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Phase 375 — one shareable public booking link of the caller. Authenticated, caller-scoped
 * response: it carries only the caller's own slug and (for SALON links) their own salon id.
 * MASTER links go to {@code INDEPENDENT_MASTER} only; SALON links to {@code SALON_OWNER} (one per active
 * owned salon, never a MASTER link — 2026-10-20) and {@code SALON_ADMIN} (its bound salon).
 * Phase 378 adds {@code status}: a REVOKED link keeps its kind / name / salon but carries no slug or url.
 */
@Schema(description = "A ready-to-share public booking link owned by the caller. MASTER: INDEPENDENT_MASTER "
        + "only. SALON: SALON_OWNER (one per active owned salon, no MASTER link) and SALON_ADMIN (bound salon)")
public record BookingLinkResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        BookingLinkKind kind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Phase 378: ACTIVE, or REVOKED (slug and url null, bookable false)")
        BookingLinkStatus status,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, nullable = true,
                example = "olena-koval-x7k2",
                description = "Public slug; null when status is REVOKED. Never changes on rename; changes only "
                        + "by an explicit regenerate (the old slug is retired for good)")
        String slug,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, nullable = true,
                example = "https://beautica.app/book/olena-koval-x7k2",
                description = "Absolute https canonical URL (the app adds its own share-channel flag): "
                        + "MASTER https://beautica.app/book/{slug}, SALON https://beautica.app/book/s/{slug}; "
                        + "null when status is REVOKED")
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

    /** An ACTIVE link (the phase 375/376 shape). */
    public BookingLinkResponse(
            BookingLinkKind kind, String slug, String url, String displayName, UUID salonId, boolean bookable) {
        this(kind, BookingLinkStatus.ACTIVE, slug, url, displayName, salonId, bookable);
    }

    /** Phase 378 D15 — a REVOKED link: no slug, no url, never bookable. */
    public static BookingLinkResponse revoked(BookingLinkKind kind, String displayName, UUID salonId) {
        return new BookingLinkResponse(kind, BookingLinkStatus.REVOKED, null, null, displayName, salonId, false);
    }
}
