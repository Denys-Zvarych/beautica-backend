CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE bookings (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id                   UUID NOT NULL REFERENCES users(id),
    master_id                   UUID NOT NULL REFERENCES masters(id),
    master_service_id           UUID NOT NULL REFERENCES master_services(id),
    salon_id                    UUID REFERENCES salons(id),
    status                      VARCHAR(30) NOT NULL,
    starts_at                   TIMESTAMPTZ NOT NULL,
    ends_at                     TIMESTAMPTZ NOT NULL,
    price_at_booking            NUMERIC(10,2) NOT NULL,
    duration_minutes_at_booking INTEGER NOT NULL,
    buffer_minutes_at_booking   INTEGER NOT NULL DEFAULT 0,
    idempotency_key             VARCHAR(64),
    cancellation_reason         VARCHAR(50),
    client_comment              VARCHAR(1000),
    provider_comment            VARCHAR(1000),
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_booking_status CHECK (
        status IN ('PENDING','CONFIRMED','DECLINED','COMPLETED','NOT_COMPLETED','CANCELLED')
    ),
    CONSTRAINT chk_booking_interval CHECK (ends_at > starts_at),
    CONSTRAINT chk_booking_duration_positive CHECK (duration_minutes_at_booking > 0),
    CONSTRAINT chk_booking_price_non_negative CHECK (price_at_booking >= 0),
    CONSTRAINT chk_booking_buffer_non_negative CHECK (buffer_minutes_at_booking >= 0),
    CONSTRAINT chk_cancellation_reason CHECK (
        cancellation_reason IS NULL OR
        cancellation_reason IN ('CLIENT_NO_SHOW','CLIENT_CANCELLED','PROVIDER_UNAVAILABLE','DUPLICATE','OTHER')
    ),
    CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
        master_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('PENDING','CONFIRMED'))
);
CREATE INDEX idx_bookings_master_starts_at ON bookings(master_id, starts_at);
CREATE INDEX idx_bookings_client_id ON bookings(client_id);
CREATE INDEX idx_bookings_salon_id ON bookings(salon_id);
CREATE INDEX idx_bookings_master_service_id ON bookings(master_service_id);
CREATE INDEX idx_bookings_client_status_starts_at ON bookings(client_id, status, starts_at);
CREATE INDEX idx_bookings_master_active_starts_at ON bookings(master_id, starts_at) WHERE status IN ('PENDING', 'CONFIRMED');
CREATE UNIQUE INDEX uq_client_idempotency_key_active ON bookings (client_id, idempotency_key) WHERE idempotency_key IS NOT NULL AND status IN ('PENDING', 'CONFIRMED');
