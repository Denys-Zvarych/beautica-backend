package com.beautica.booking.repository;

/**
 * Test-only {@link PostLockSlotCheck} implementation — Mockito cannot instantiate a projection
 * interface for a stubbed return value the way Spring Data's proxy does for a real query result, so
 * every unit test that stubs {@link BookingRepository#findPostLockBookabilityAndOverlap} needs a
 * concrete implementation to hand back. ONE shared record instead of an anonymous class per call
 * site (REUSE-FIRST) — used by {@code BookingServiceTest}, {@code GuestBookingServiceTest} and
 * {@code StaffBookingServiceTest}.
 */
public record TestPostLockSlotCheck(Boolean masterBookable, Boolean overlapExists) implements PostLockSlotCheck {

    @Override
    public Boolean getMasterBookable() {
        return masterBookable;
    }

    @Override
    public Boolean getOverlapExists() {
        return overlapExists;
    }
}
