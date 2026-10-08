-- Phase 356 audit: DB-level guarantee that every booking of a multi-service visit has the SAME master.
-- Request-time validation (AppointmentService builds all items from one request.masterId()) stays the
-- primary guard; this is the backstop. A composite FK was rejected: appointments has no master_id, and
-- adding + backfilling one would touch the entity and the creation path. NULL appointment_id (legacy
-- single bookings) is untouched. Idempotent: CREATE OR REPLACE + DROP TRIGGER IF EXISTS.

CREATE OR REPLACE FUNCTION bookings_enforce_single_master_per_appointment() RETURNS trigger AS $$
BEGIN
    IF NEW.appointment_id IS NULL THEN
        RETURN NEW;
    END IF;
    -- Serialise concurrent writers of the same visit on the parent row, so two transactions cannot
    -- each see "no conflicting sibling" and both commit.
    PERFORM 1 FROM public.appointments WHERE id = NEW.appointment_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM public.bookings b
               WHERE b.appointment_id = NEW.appointment_id
                 AND b.id <> NEW.id
                 AND b.master_id IS DISTINCT FROM NEW.master_id) THEN
        RAISE EXCEPTION 'appointment % mixes masters', NEW.appointment_id
            USING ERRCODE = '23514', CONSTRAINT = 'trg_bookings_single_master_per_appointment';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS trg_bookings_single_master_per_appointment ON bookings;
CREATE TRIGGER trg_bookings_single_master_per_appointment
    BEFORE INSERT OR UPDATE OF appointment_id, master_id ON bookings
    FOR EACH ROW EXECUTE FUNCTION bookings_enforce_single_master_per_appointment();
