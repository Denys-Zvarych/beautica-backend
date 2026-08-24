package com.beautica.booking;

import org.junit.jupiter.api.DisplayName;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 22.15 — the LEGACY arm of the dual-shape parity matrix: a STAFF booking exactly as it
 * looked before this track ({@code appointment_id = NULL}), built by a <b>direct JDBC insert</b> —
 * never through {@code StaffBookingService} or {@code POST /masters/&#123;masterId&#125;/bookings}.
 *
 * <p><b>Why raw JDBC and not the old create path.</b> There is no old create path left after Phase
 * 259: {@code StaffBookingService#createStaffBooking} always chains through
 * {@code AppointmentService}/{@code VisitPlanner} now and always leaves an {@code appointments}
 * header (Phase 258 D2 — no backfill, no size short-circuit at N = 1). A fixture built through any
 * widened code path cannot produce this shape at all; it would just be a slower way to build the
 * NEW one. This class inserts the row exactly the way a pre-track-22 walk-in was persisted:
 * {@code CONFIRMED}/{@code STAFF}, {@code client_id} / {@code cancel_token} / {@code appointment_id}
 * all NULL, guest triple populated (V137 {@code chk_bookings_guest_fields}).
 *
 * <p>Multiple "services" of a legacy visit are simply N independent standalone bookings on the same
 * master at sequential windows — there was never a grouping concept before this track, so that is
 * the faithful legacy shape of "the master did three things for the same walk-in that afternoon".
 */
@DisplayName("LegacyStaffBookingShapeIT — appointment_id NULL, built by raw JDBC insert")
class LegacyStaffBookingShapeIT extends AbstractStaffVisitShapeIT {

    @Override
    protected boolean hasAppointmentHeader() {
        return false;
    }

    @Override
    protected Visit givenStaffVisit(int serviceCount, OffsetDateTime startsAt) {
        List<UUID> serviceIds = nServices(serviceCount);
        List<UUID> bookingIds = new ArrayList<>();
        for (int i = 0; i < serviceCount; i++) {
            UUID bookingId = UUID.randomUUID();
            OffsetDateTime itemStart = startsAt.plusMinutes((long) i * DURATION_MINUTES);
            OffsetDateTime itemEnd = itemStart.plusMinutes(DURATION_MINUTES);
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, guest_name, guest_surname, "
                            + "guest_phone, created_by_user_id, appointment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, 0, 'STAFF', ?, ?, ?, ?, "
                            + "NULL, NOW(), NOW())",
                    bookingId, salon.masterId(), serviceIds.get(i), salon.salonId(),
                    itemStart, itemEnd, PRICE, DURATION_MINUTES,
                    GUEST_FIRST_NAME, GUEST_LAST_NAME, E164_PHONE, salon.ownerId());
            bookingIds.add(bookingId);
        }
        return new Visit(null, bookingIds);
    }
}
