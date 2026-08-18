-- Multi-service single-visit booking (BE-3) — the deferred polymorphic guest-fields CHECK.
--
-- V124 (§O-3, deliberately deferred) created `appointments` WITHOUT the polymorphic guest-fields
-- CHECK that `bookings` carries (chk_bookings_guest_fields, V89 relaxed by V91), because the
-- appointment CREATE path did not exist yet and baking the invariant into a writer-less table would
-- have over-constrained it. BE-3 is that writer: AppointmentService now inserts CONFIRMED APP visits
-- (client_id NOT NULL, guest_* NULL, cancel_token NULL), which satisfy the APP branch below. Adding
-- the CHECK now makes the APP/LINK invariant DB-enforceable — a controller/service bug can never
-- persist a half-populated visit row, exactly as the mirror CHECK protects `bookings`.
--
-- Forward-only: V124/V125 are already committed and must not be edited (an altered checksum
-- crash-loops Flyway). This is a pure ADD CONSTRAINT with no entity change (ddl-auto=validate does
-- not check CHECK constraints), so the Appointment entity is untouched.
--
-- Mirrors chk_bookings_guest_fields byte-for-byte in shape, INCLUDING V91's relaxation of the
-- cancel_token clause: a LINK visit may carry a NULL cancel_token once it reaches a terminal /
-- non-active status (the guest-cancel UPDATE nulls the token while setting status = CANCELLED). The
-- active states (CONFIRMED) still require a non-null token, so a create-time LINK visit with a null
-- token is rejected. guest_surname stays OPTIONAL (nullable), same as bookings. BE-3 only creates
-- APP rows, which take the APP branch; the LINK branch is here so a future guest-visit create path
-- (BE-4/BE-5) is already constrained.
ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_id IS NULL
            AND guest_name IS NOT NULL
            AND guest_phone IS NOT NULL
            AND (cancel_token IS NOT NULL
                 OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
        OR
        (booking_source = 'APP'
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
    );
