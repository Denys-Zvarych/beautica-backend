package com.beautica.booking.retention;

/**
 * Constants for guest-PII anonymisation (phase 373).
 *
 * <p>No reusable «Гість» sentinel existed: the detached-client label in
 * {@code ClientAccountDeletionService} is «Видалений клієнт» (a registered user who deleted their
 * account), which must stay distinguishable from an anonymised guest/walk-in.
 */
public final class GuestPii {

    /** Replacement {@code guest_name} for an anonymised guest/walk-in row — "guest". */
    public static final String ANONYMISED_NAME = "Гість";

    private GuestPii() {
    }
}
