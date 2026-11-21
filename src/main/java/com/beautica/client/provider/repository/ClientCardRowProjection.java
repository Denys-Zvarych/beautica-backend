package com.beautica.client.provider.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * B508 — the one aggregate row behind a client card. INTERNAL: {@link #getGuestPhone()} / {@link #getVerifiedPhone()} are raw E.164 and a Spring
 * projection proxy's {@code toString} prints every column, so the service copies what it needs and never logs this
 * object or puts it in an exception message.
 */
public interface ClientCardRowProjection {

    Long getTotalVisits();

    Long getCompletedVisits();

    Long getUpcomingVisits();

    Instant getFirstVisitAt();

    Instant getLastVisitAt();

    Instant getNextVisitAt();

    /** The guest's latest booking in scope (the stable card handle); null for a registered client. */
    UUID getAnchorBookingId();

    /** Raw E.164; guest only. */
    String getGuestPhone();

    /** B518: the registered client's phone ONLY when SMS-verified (SQL-gated); null otherwise and for a guest. */
    String getVerifiedPhone();

    String getGuestName();

    String getGuestSurname();

    String getFirstName();

    String getLastName();

    String getAvatarUrl();

    BigDecimal getAvgRating();

    Integer getReviewCount();

    /**
     * CLI-73 — the salon card's master chips as JSON text ({@code [{masterId, firstName, lastName, avatarUrl}]}, most
     * recent booking first); {@code null} on the independent-master / SALON_MASTER card.
     */
    String getServedByMasters();
}
