package com.beautica.salon.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One selectable row of the rotate-admin destination picker
 * ({@code GET /salons/{salonId}/sibling-salons}, Phase 21.3b) — deliberately the NARROWEST shape
 * that lets a caller render and submit a choice: the id it will send as
 * {@code destinationSalonId} to {@code PATCH /salons/{salonId}/admins/{userId}/salon}, plus enough
 * text to tell two of one owner's salons apart.
 *
 * <p><b>Why not {@code SalonResponse} (Security LOW / Perf LOW-1).</b> This endpoint is reachable
 * by an assigned {@code SALON_ADMIN}, and {@code canManageSalon} places that admin inside the trust
 * boundary of exactly ONE salon — the one in the path. The siblings are salons they hold no
 * assignment to and can otherwise only learn the identity of by attempting a rotation. Returning
 * the full {@code SalonResponse} handed them the whole portfolio's {@code description},
 * {@code phone}, {@code instagramUrl}, legacy free-text city/region/address, {@code isPrimary},
 * {@code createdAt} <em>and</em> the owner's UUID — none of which a destination picker needs.
 * Narrowing to id + name + short address + logo keeps the endpoint's disclosure equal to what the
 * mutation it feeds already reveals.
 *
 * <p><b>{@code avatarUrl} (Phase 369 follow-up).</b> The salon logo URL ({@code salons.avatar_url}),
 * added so the picker can render the logo like every other salon surface. It widens nothing: every
 * row here is an ACTIVE salon, and an active salon's {@code avatarUrl} is already served to
 * unauthenticated callers by {@code GET /salons/{id}} ({@code PublicSalonResponse}). The R2 object key
 * ({@code salons.avatar_r2_key}) is deliberately NOT projected — it is a storage-internal pointer
 * that no client surface carries. The key's value is not secret — the public URL is built as
 * {@code <public-url-prefix>/<key>}, so the key is that URL's path; what is withheld is the separate
 * key column, not the key itself. Nullable: a salon with no logo returns {@code null}.
 *
 * <p><b>No {@code ownerId}.</b> Every sibling shares the source salon's owner by construction, so
 * the field carried no information the caller could act on — only the owner's user UUID.
 *
 * <p>{@code street}/{@code buildingNo} are the Phase 10.6 structured address and are the only
 * address surface here: they are what distinguishes two same-named branches. They are nullable —
 * a salon persisted before Phase 10.6 may have neither — so the client must fall back to
 * {@code name} alone.
 */
@Schema(description = "A salon offered as a rotate-admin destination: id, name, short address and "
        + "logo URL.")
public record SiblingSalonOption(
        @Schema(
                format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Send this as destinationSalonId to PATCH "
                        + "/salons/{salonId}/admins/{userId}/salon.")
        UUID id,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Salon display name.")
        String name,

        @Schema(description = "Street of the structured address (Phase 10.6). May be null for a "
                + "salon persisted before that phase.")
        String street,

        @Schema(description = "Building number of the structured address (Phase 10.6). May be null "
                + "for a salon persisted before that phase.")
        String buildingNo,

        @Schema(
                types = {"string", "null"},
                nullable = true,
                description = "Salon logo image URL (same value as SalonResponse.avatarUrl); null "
                        + "when the salon has no logo.")
        String avatarUrl
) {
    /*
     * No static from(Salon) mapper, deliberately (Perf LOW-B). This record is built by
     * SalonRepository#findActiveSiblingsBySalonId's JPQL constructor projection — Hibernate calls
     * the canonical constructor with five scalars and never materialises a Salon entity — so a
     * mapper taking an entity would be dead code AND the one seam through which an association
     * dereference could creep back onto this path. If a second caller ever needs to build this
     * record from a loaded Salon, note that it would reintroduce the 22-column read this projection
     * exists to remove; prefer widening the projection.
     */
}
