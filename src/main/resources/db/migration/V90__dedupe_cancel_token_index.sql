-- Phase 13.3 audit (LOW / PERF) — collapse the redundant cancel_token indexes.
--
-- V89 added `cancel_token UUID UNIQUE`, which made Postgres auto-create a FULL unique
-- index `bookings_cancel_token_key` (the implicit constraint-backing index covers every
-- row, including the overwhelmingly-NULL APP bookings). V89 then ALSO created the
-- non-unique partial index `idx_bookings_cancel_token` (WHERE cancel_token IS NOT NULL).
--
-- Net result: two indexes on the same column — a full unique one (mostly-NULL bloat +
-- write amplification on every APP-booking insert) and a partial non-unique one. Only LINK
-- rows ever carry a token, and uniqueness only needs to hold over the non-NULL rows.
--
-- Fix: drop both and create a single PARTIAL UNIQUE index — uniqueness where it matters,
-- no dead entries for NULL rows. The auto-generated constraint name follows the Postgres
-- `<table>_<column>_key` convention (verified against the V89 inline `cancel_token UUID UNIQUE`).
ALTER TABLE bookings DROP CONSTRAINT bookings_cancel_token_key;
DROP INDEX idx_bookings_cancel_token;
CREATE UNIQUE INDEX idx_bookings_cancel_token ON bookings (cancel_token)
    WHERE cancel_token IS NOT NULL;
