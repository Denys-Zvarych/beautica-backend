package com.beautica.review.invite.repository;

import java.util.UUID;

/**
 * Phase 400 — native projection of {@link ReviewInviteRepository#findVisitViews} and
 * {@link ReviewInviteRepository#findBookingViews}: the visit columns the invite gates
 * read. {@code getGuestPhone()} is raw PII — callers never log it.
 */
public interface ReviewInviteVisitView {

    UUID getAppointmentId();

    /** Phase 400 D7 — set only by {@link ReviewInviteRepository#findBookingViews}; {@code null} for a visit. */
    UUID getBookingId();

    String getBookingSource();

    boolean getHasClient();

    String getGuestPhone();

    boolean getAnonymised();

    String getPolicyVersion();

    boolean getHasUnreviewedCompleted();
}
