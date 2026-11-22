package com.beautica.client.event;

import java.util.UUID;

/**
 * B527 (CLI-62) — existing guest / walk-in bookings were attached to a CLIENT account by its verified phone.
 * Published inside the attaching transaction; {@link ClientPassportCacheEvictor} evicts the account's BEAUTY PASSPORT
 * after commit (attached COMPLETED visits move the passport and the timeline, CLI-20).
 */
public record ClientBookingsAttachedEvent(UUID clientUserId) {
}
