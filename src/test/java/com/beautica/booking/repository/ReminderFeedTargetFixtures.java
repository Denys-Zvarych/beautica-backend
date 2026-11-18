package com.beautica.booking.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 522 audit-fix 5 — the ONE test stand-in for the {@link BookingRepository.ReminderFeedTarget} projection,
 * shared by the reminder-job and in-app-service unit tests (was hand-copied into three test classes). A record, so
 * two targets built from the same values are {@code equals} — Mockito argument matching relies on that.
 */
public final class ReminderFeedTargetFixtures {

    private ReminderFeedTargetFixtures() {
    }

    /** A feed target for booking {@code id}: its client, its salon ({@code null} for an independent master), start. */
    public static BookingRepository.ReminderFeedTarget reminderFeedTarget(
            UUID id, UUID clientId, UUID salonId, OffsetDateTime startsAt) {
        return new ReminderFeedTargetStub(id, clientId, salonId, startsAt);
    }

    private record ReminderFeedTargetStub(UUID id, UUID clientId, UUID salonId, OffsetDateTime startsAt)
            implements BookingRepository.ReminderFeedTarget {
        @Override public UUID getId() { return id; }
        @Override public UUID getClientId() { return clientId; }
        @Override public UUID getSalonId() { return salonId; }
        @Override public OffsetDateTime getStartsAt() { return startsAt; }
    }
}
