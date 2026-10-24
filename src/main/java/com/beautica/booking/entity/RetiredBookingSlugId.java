package com.beautica.booking.entity;

import com.beautica.booking.dto.BookingLinkKind;

import java.io.Serializable;

/**
 * Phase 378 — composite key of {@link RetiredBookingSlug}: {@code (kind, slugHash)} (D3; V205). Master and salon slugs
 * live in different URL namespaces, so an equal slug of the other kind is a different tombstone.
 */
public record RetiredBookingSlugId(BookingLinkKind kind, String slugHash) implements Serializable {
}
