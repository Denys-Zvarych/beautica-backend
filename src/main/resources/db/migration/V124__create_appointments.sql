-- Multi-service single-visit booking (BE-1) — the `appointments` aggregate.
--
-- An appointment GROUPS N unchanged `bookings` rows into one client-facing visit: the client
-- picks ONE date+time for several services performed back-to-back by a SINGLE master, and each
-- selected service still becomes exactly one `bookings` row (chained in time), all sharing this
-- appointment's id via the nullable `bookings.appointment_id` FK added in V125. Legacy single-
-- service bookings keep `appointment_id = NULL`, so this table is purely ADDITIVE and changes no
-- existing behaviour.
--
-- This row is the visit HEADER only: it carries the denormalized visit-level status, the notes,
-- the guest identity and the cancel/idempotency tokens. It deliberately holds NO
-- starts_at/ends_at/price/duration — those stay per-service on the child `bookings` rows. That is
-- exactly why the Postgres `no_overlapping_bookings` overlap-EXCLUDE constraint (V18, narrowed by
-- V113) STAYS on `bookings.master_id` and is NOT duplicated here: an appointment has no time
-- window to exclude against. Per the locked design (single master per visit, all-or-nothing visit
-- status) this header's status/notes/source are a denormalization of the rows it aggregates.
--
-- Every column type, length and CHECK below mirrors the equivalent `bookings` column so the
-- denormalized status/notes/source stay byte-compatible with the rows they aggregate:
--   status              — VARCHAR(30), same 5-value set as chk_booking_status after V113
--   cancellation_reason — VARCHAR(50), same value set (V18) + terminal-status guard (V24)
--   client/provider notes — VARCHAR(1000), same status-coupling CHECKs as V114
--   booking_source      — VARCHAR(10) NOT NULL DEFAULT 'APP', same APP/LINK CHECK as V89
--   guest_*             — VARCHAR(100/100/20), guest_phone E.164 format guard as V89
--   idempotency_key     — VARCHAR(64); cancel_token UUID with a partial-unique index as V90
--
-- created_at / updated_at follow the AuditableEntity convention already used by `bookings` (V18):
-- NOT NULL with NO DB default — Hibernate always supplies both via @CreationTimestamp /
-- @UpdateTimestamp, and inserts only ever go through JPA (there is no raw-SQL seed for this table).
CREATE TABLE appointments (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id                UUID REFERENCES users(id),
    salon_id                 UUID REFERENCES salons(id),
    status                   VARCHAR(30) NOT NULL,
    client_comment           VARCHAR(1000),
    client_cancellation_note VARCHAR(1000),
    provider_comment         VARCHAR(1000),
    cancellation_reason      VARCHAR(50),
    idempotency_key          VARCHAR(64),
    cancel_token             UUID,
    booking_source           VARCHAR(10) NOT NULL DEFAULT 'APP',
    guest_name               VARCHAR(100),
    guest_surname            VARCHAR(100),
    guest_phone              VARCHAR(20),
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,

    -- mirrors bookings.chk_booking_status (V18, narrowed to the post-V113 5-value set)
    CONSTRAINT chk_appointment_status CHECK (
        status IN ('CONFIRMED','DECLINED','COMPLETED','NOT_COMPLETED','CANCELLED')
    ),
    -- mirrors bookings.chk_bookings_source (V89)
    CONSTRAINT chk_appointment_source CHECK (
        booking_source IN ('APP','LINK')
    ),
    -- mirrors bookings.chk_cancellation_reason (V18)
    CONSTRAINT chk_appointment_cancellation_reason CHECK (
        cancellation_reason IS NULL OR
        cancellation_reason IN ('CLIENT_NO_SHOW','CLIENT_CANCELLED','PROVIDER_UNAVAILABLE','DUPLICATE','OTHER')
    ),
    -- mirrors bookings.chk_cancellation_reason_status (V24): a reason only on a terminal visit
    CONSTRAINT chk_appointment_cancellation_reason_status CHECK (
        cancellation_reason IS NULL OR
        status IN ('DECLINED','CANCELLED','NOT_COMPLETED')
    ),
    -- mirrors bookings.chk_provider_comment_status (V114): a provider note only on a
    -- provider-terminated visit (DECLINED / NOT_COMPLETED)
    CONSTRAINT chk_appointment_provider_comment_status CHECK (
        provider_comment IS NULL OR status IN ('DECLINED','NOT_COMPLETED')
    ),
    -- mirrors bookings.chk_client_cancellation_note_status (V114): the client's own cancel note
    -- only on a CANCELLED visit. Deliberately NO CHECK on client_comment — like V114 it is the
    -- booking-CREATION note, legitimately present on CONFIRMED/COMPLETED visits, not just terminal
    -- ones; a naive mirroring CHECK would be semantically wrong.
    CONSTRAINT chk_appointment_client_cancellation_note_status CHECK (
        client_cancellation_note IS NULL OR status = 'CANCELLED'
    ),
    -- mirrors bookings.chk_bookings_guest_phone_format (V89): E.164 when present
    CONSTRAINT chk_appointment_guest_phone_format CHECK (
        guest_phone IS NULL OR guest_phone ~ '^\+[0-9]{6,18}$'
    )
);

-- FK-lookup indices: a visit belongs to a client (nullable for guest/LINK) and optionally a salon
-- (null = independent master).
CREATE INDEX idx_appointments_client_id ON appointments (client_id);
CREATE INDEX idx_appointments_salon_id ON appointments (salon_id);

-- Cancel-token lookup for the public guest-cancel page, partial-unique exactly like
-- bookings' idx_bookings_cancel_token (V90): uniqueness only over the non-NULL (guest/LINK) rows,
-- no dead entries for the overwhelmingly-NULL account-holder rows.
CREATE UNIQUE INDEX ux_appointments_cancel_token ON appointments (cancel_token)
    WHERE cancel_token IS NOT NULL;

-- Idempotency dedup, mirroring bookings' uq_client_idempotency_key_active (V18, narrowed to the
-- post-V113 single 'CONFIRMED' status): a client-retry race must never persist two appointment
-- headers for the same (client_id, idempotency_key) — each duplicate header would otherwise spawn
-- N chained bookings. Partial so it constrains only live (CONFIRMED, keyed) visits, leaving the
-- overwhelmingly-NULL / terminal rows unindexed. The BE-3 writer relies on this constraint.
CREATE UNIQUE INDEX ux_appointments_client_idempotency_key_active
    ON appointments (client_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND status = 'CONFIRMED';

-- NOTE (§O-3 deferred to BE-3): the polymorphic guest CHECK that `bookings` carries
-- (chk_bookings_guest_fields, V89/V91 — LINK ⇒ client_id NULL + guest identity + cancel_token
-- non-null; APP ⇒ the inverse) is intentionally NOT mirrored here yet. That invariant couples
-- booking_source to client_id/guest/cancel_token nullability, and the appointment CREATE path
-- does not exist until BE-3; baking it in now would over-constrain a table with no writer.
-- Add it in the BE-3 migration alongside the create logic that must satisfy it.
