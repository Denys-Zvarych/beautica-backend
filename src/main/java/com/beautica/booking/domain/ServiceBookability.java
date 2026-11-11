package com.beautica.booking.domain;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;

/**
 * The SINGLE canonical Java definition of "this master-service assignment may be booked / may
 * expose bookable slots" — the service-side twin of {@link MasterBookability}.
 *
 * <pre>
 * BOOKABLE iff master_services.is_active = true
 *          AND service_definitions.is_active = true
 * </pre>
 *
 * <p><b>Why the definition term exists.</b> Deactivating a definition
 * ({@code ServiceRepository#deactivateById}) does NOT cascade to its {@code master_services} rows,
 * so an active assignment can point at a retired copy. Checking only the assignment flag let a
 * caller holding a stale {@code masterServiceId} book (or see slots for) a deactivated service.
 *
 * <p>Call sites: {@code VisitPlanner#resolveAssignments} ({@code POST /appointments}),
 * {@code BookingService#doCreateBooking} ({@code POST /bookings}),
 * {@code GuestBookingService#createSingleServiceBooking} ({@code POST /book/{slug}/booking}) and
 * every assignment gate in {@code SlotCalculationService}. Every caller maps {@code false} onto its
 * existing uniform 404 — unknown, foreign, inactive assignment and inactive definition must stay
 * indistinguishable (no enumeration oracle).
 *
 * <p><b>Callers must ensure {@code serviceDefinition} is initialised</b> (the {@code *WithGraph}
 * finders JOIN FETCH it) so this adds no query.
 */
public final class ServiceBookability {

    private ServiceBookability() {
    }

    public static boolean isBookable(MasterServiceAssignment assignment) {
        if (assignment == null || !assignment.isActive()) {
            return false;
        }
        ServiceDefinition definition = assignment.getServiceDefinition();
        return definition != null && definition.isActive();
    }
}
