package com.beautica.client.provider.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * B507 — one grouped client row as the native page query returns it. INTERNAL: {@link #getGuestPhone()} exists only
 * so the service can hash it for the block lookup; the service copies the row into its own redacting record at once
 * and the projection never reaches a DTO, a log line or an exception message (a Spring projection's toString prints
 * every column, phone included).
 *
 * <p>WARNING: the projection proxy's {@code toString} exposes {@code guestPhone}; never log it or put it in an
 * exception message.
 */
public interface ClientListRowProjection {

    /** Null for a guest. */
    UUID getClientId();

    /** Raw E.164; non-null only for a guest. */
    String getGuestPhone();

    Instant getLastVisitAt();

    Instant getNextVisitAt();

    Long getCompletedVisits();

    UUID getAnchorBookingId();

    String getGuestName();

    String getGuestSurname();

    String getFirstName();

    String getLastName();

    String getAvatarUrl();

    BigDecimal getAvgRating();

    Integer getReviewCount();

    /**
     * B527 — INTERNAL, raw E.164: the registered client's VERIFIED phone, read only to hash it for the block lookup (a
     * registered client is blocked by account OR by the hash of that phone, D7). Never copied into a response or a log.
     */
    String getVerifiedPhone();
}
