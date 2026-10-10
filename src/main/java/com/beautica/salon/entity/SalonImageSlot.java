package com.beautica.salon.entity;

import java.util.Optional;
import java.util.UUID;

/**
 * The two owner-managed salon images (Phase 343). {@link #LOGO} is the square salon photo
 * ({@code salons.avatar_url}), {@link #COVER} the 16:9 banner on the public profile
 * ({@code salons.cover_image_url}). Bound from the {@code {slot}} path segment of
 * {@code /api/v1/salons/{salonId}/media/{slot}} by its lowercase {@link #pathSegment()}.
 */
public enum SalonImageSlot {

    LOGO("logo"),
    COVER("cover");

    /** Root of every salon-image key; must match V189's CHECKs and {@code MediaService}'s sweep guard. */
    private static final String KEY_ROOT = "salons/";

    private final String pathSegment;

    SalonImageSlot(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    /** The lowercase URL segment ({@code logo} / {@code cover}) — also the key's slot directory. */
    public String pathSegment() {
        return pathSegment;
    }

    /** {@code salons/<salonId>/<slot>/} — the ONLY prefix a key of this slot may live under (V189 D4). */
    public String keyPrefix(UUID salonId) {
        return KEY_ROOT + salonId + "/" + pathSegment + "/";
    }

    /**
     * EXACT parse of a path segment — no trimming, no case folding, so one slot has exactly one URL
     * ({@code /media/LOGO} and {@code /media/%20logo} are 400, never aliases). Empty for anything that is not
     * a known slot.
     */
    public static Optional<SalonImageSlot> fromPathSegment(String segment) {
        for (SalonImageSlot slot : values()) {
            if (slot.pathSegment.equals(segment)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }
}
