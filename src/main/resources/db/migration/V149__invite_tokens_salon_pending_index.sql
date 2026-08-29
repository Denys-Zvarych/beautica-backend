-- Phase 23.1 QA audit (Perf MEDIUM): GET /salons/{salonId}/invites/pending
-- (InviteTokenRepository#findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc) filters
-- on `salon_id = ? AND is_used = false AND expires_at > ?` and sorts by `created_at DESC`.
--
-- Neither existing invite_tokens index leads with salon_id:
--   * idx_invite_tokens_email_used (V16: email, is_used) — leads with email, useless for a
--     salon-scoped listing with no email predicate.
--   * ux_invite_tokens_active (V101: lower(email), salon_id) WHERE is_used = false — an expression
--     index on lower(email) FIRST; Postgres cannot use it to seek on salon_id alone, only as a
--     sequential-ish scan filtered post-hoc, same class of defect V142's Javadoc documents for
--     bookings.
--
-- PARTIAL on is_used = false, matching the query predicate exactly (the endpoint never lists used
-- invites) — keeps the index small and excludes rows the query can never match, per the same
-- rationale as idx_bookings_salon_client_starts_at (V142) and idx_salons_owner_primary (V56).
-- expires_at as the second column lets `salon_id = ? AND expires_at > ?` resolve as a single
-- Index Cond range scan instead of an Index Cond on salon_id plus a residual Filter on expires_at.
CREATE INDEX idx_invite_tokens_salon_pending
    ON invite_tokens (salon_id, expires_at)
    WHERE is_used = false;
