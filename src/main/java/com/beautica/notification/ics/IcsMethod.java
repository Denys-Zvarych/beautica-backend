package com.beautica.notification.ics;

/**
 * Phase 496 (NTF-50) — the iTIP method of a booking .ics (RFC 5546). {@link #REQUEST} creates or updates the
 * client's calendar event (confirmation, reschedule by either side); {@link #CANCEL} removes it (the client's cancel
 * receipt, a provider decline). Both share the booking's UID, so a later message updates the earlier one.
 */
public enum IcsMethod {
    REQUEST("CONFIRMED"),
    CANCEL("CANCELLED");

    private final String eventStatus;

    IcsMethod(String eventStatus) {
        this.eventStatus = eventStatus;
    }

    /** The VEVENT {@code STATUS} value that goes with this method. */
    public String eventStatus() {
        return eventStatus;
    }
}
