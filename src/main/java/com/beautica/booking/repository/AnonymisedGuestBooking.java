package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Phase 409 D1 — one booking a retention batch just anonymised, with the guest phone it carried BEFORE the wipe
 * (read from the claim CTE's snapshot; the row itself now holds {@code NULL}). The phone feeds only the Telegram link
 * cleanup of the same batch transaction; it is never logged or returned beyond it.
 */
public interface AnonymisedGuestBooking {

    UUID getBookingId();

    /** E.164 guest phone the row had before anonymisation (never {@code NULL} for LINK; may be for a STAFF row). */
    String getGuestPhone();
}
