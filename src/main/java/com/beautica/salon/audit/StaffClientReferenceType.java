package com.beautica.salon.audit;

/**
 * Which client-side reference site a {@link StaffClientReferenceViolation} was found at. One value
 * per column the salon-deletion safety audit (Phase 289) checks — see
 * {@code StaffClientReferenceAuditRepository} for the query backing each.
 */
public enum StaffClientReferenceType {

    /** {@code bookings.client_id} — {@code V18__create_bookings.sql:5}. */
    BOOKING_CLIENT,

    /** {@code reviews.client_id} — {@code V40__create_reviews.sql:6}. */
    REVIEW_CLIENT,

    /** {@code client_reviews.subject_client_id} — {@code V128__...:19}. */
    CLIENT_REVIEW_SUBJECT,

    /**
     * {@code appointments.client_id} — {@code V124__create_appointments.sql}. Added by the phase
     * 295 audit (LOW-8); checked by the SALON-SCOPED audit only
     * ({@code findAppointmentClientViolationsForSalon}), which is the fail-closed path in front of
     * the hard delete. The platform-wide offline sweep has no matching arm — see that finder's
     * javadoc.
     */
    APPOINTMENT_CLIENT
}
