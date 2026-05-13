-- Phase 8.1 doc names this V11; bumped to V40 because V11..V39 already exist.
-- One review per booking enforced by UNIQUE(booking_id); rating range enforced by CHECK.
CREATE TABLE reviews (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID        NOT NULL REFERENCES bookings(id) ON DELETE RESTRICT,
    client_id   UUID        NOT NULL REFERENCES users(id),
    master_id   UUID        NOT NULL REFERENCES masters(id),
    salon_id    UUID        NOT NULL REFERENCES salons(id),
    rating      SMALLINT    NOT NULL,
    comment     VARCHAR(2000),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT  uq_reviews_booking UNIQUE (booking_id),
    CONSTRAINT  chk_rating CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX idx_reviews_master_created ON reviews(master_id, created_at DESC);
CREATE INDEX idx_reviews_client_id      ON reviews(client_id);
CREATE INDEX idx_reviews_salon_created  ON reviews(salon_id, created_at DESC);
