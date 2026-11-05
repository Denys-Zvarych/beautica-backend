package com.beautica.notification.telegram.link;

import java.util.List;

/**
 * Phase 409 orphan backstop — the phones that still have a non-anonymised guest/walk-in booking, read in keyset pages.
 * Implemented by the booking feature, so this package never depends on booking retention (no package cycle).
 */
public interface OpenGuestPhoneSource {

    /** Distinct E.164 phones after {@code after} in ascending order, at most {@code limit}. */
    List<String> openGuestPhonesPage(String after, int limit);
}
