-- Phase 27.4 (REVERSES the previously deferred/out-of-scope status of master -> client reviews):
-- a provider (master) may now leave a 1-5 star rating + optional comment of the CLIENT after a
-- completed booking. This is the mirror direction of `reviews` (client rates provider) and is
-- deliberately a NEW table, not a reuse of `reviews` — that table's UNIQUE(booking_id) is already
-- claimed by the client-authored review of the same booking, so a master-authored review of the
-- same booking needs its own uniqueness scope (uq_client_reviews_booking below).
--
-- id: gen_random_uuid() default, mirroring `reviews` (V40) and every other recent table's PK
-- convention (appointments, V124).
--
-- created_at / updated_at carry DEFAULT now() (Anti-Bug Playbook §O.1) — unlike `appointments`
-- (V124), which deliberately omitted the default because that table genuinely has zero raw-SQL
-- writers. `client_reviews` makes no such guarantee (integration-test fixtures may legitimately
-- seed rows directly), so the default stays as the safety net; the JPA @CreationTimestamp /
-- @UpdateTimestamp application-side stamps remain the primary source, this is a backstop only.
CREATE TABLE client_reviews (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        UUID        NOT NULL REFERENCES bookings(id) ON DELETE RESTRICT,
    subject_client_id UUID        NOT NULL REFERENCES users(id),
    author_master_id  UUID        NOT NULL REFERENCES masters(id),
    salon_id          UUID        REFERENCES salons(id),
    rating            SMALLINT    NOT NULL,
    comment           VARCHAR(2000),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_client_reviews_booking UNIQUE (booking_id),
    CONSTRAINT chk_client_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);

-- Backs "reviews I've received" reads and the recalculateClientRating aggregate — mirrors
-- idx_reviews_client_created's role but keyed on the SUBJECT (the client being reviewed), not an
-- author FK.
CREATE INDEX idx_client_reviews_subject_client ON client_reviews (subject_client_id);

COMMENT ON TABLE client_reviews IS
    'Provider (master) rating of a CLIENT after a completed booking — reverse direction of '
    '`reviews`. One row per booking (uq_client_reviews_booking), independent of `reviews`'' own '
    'UNIQUE(booking_id) which is claimed by the client-authored review of the same booking.';

-- Client aggregate rating, mirroring masters.avg_rating/masters.review_count (V4) and
-- salons.avg_rating/salons.review_count (V103). avg_rating stays NULLABLE with NO DEFAULT — a
-- client with zero reviews has never been recalculated and legitimately has no average yet
-- (same "recalculate on write, read persisted on read" contract V103 documents for salons).
-- review_count defaults to 0 (NOT NULL) so a raw-SQL user insert/fixture that omits the column
-- never violates a NOT NULL constraint (Anti-Bug §O.1).
--
-- Lock-timeout guard on this ALTER TABLE: `users` is an even hotter table than `salons` (every
-- authenticated request reads it), and `ADD COLUMN` takes ACCESS EXCLUSIVE — mirrors the V119/V103
-- pattern of failing fast (5s) rather than queuing behind an unbounded lock wait during a Railway
-- rolling deploy. Both columns are added in the SAME statement so only one ACCESS EXCLUSIVE
-- acquisition is needed.
SET LOCAL lock_timeout = '5s';

ALTER TABLE users
    ADD COLUMN avg_rating NUMERIC(3,2),
    ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.avg_rating IS
    'Persisted rating aggregate — a CLIENT''s average rating as reviewed by providers via '
    'client_reviews. NULL means never recalculated (zero reviews). Recalculated on write via '
    'ClientReviewRepository#recalculateClientRating, read as-is (no live aggregation) on read.';

COMMENT ON COLUMN users.review_count IS
    'Count of client_reviews rows for this user (as the subject_client). Defaults to 0 so raw-SQL '
    'inserts/fixtures never violate NOT NULL.';
