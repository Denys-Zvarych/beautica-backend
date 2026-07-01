-- Phase 20.x audit (LOW / best-practice) — salon-scope the active-invite idempotency guard.
--
-- InviteService.sendInvite previously looked up an existing active invite EMAIL-GLOBALLY, so
-- salon A's pending invite for an email silently short-circuited salon B's dispatch (cross-salon
-- silent drop). The service now scopes the lookup by salon_id. That salon-scoped lookup
-- (email = ? AND salon_id = ? AND is_used = false) is served by idx_invite_tokens_email_used
-- (email, is_used) from V16 — Postgres CANNOT use this lower(email) expression index for a bare
-- email = ? predicate, so this index does NOT back the lookup. Its role is the INSERT-time
-- uniqueness/concurrency backstop: it permits at most one active (unused) invite per
-- (salon, lower(email)), so a concurrent same-salon double-submit that races past the service
-- pre-check is rejected by the DB and resolved idempotently in the service. It also makes the
-- single-row lookup safe — two unused rows for one (salon, lower(email)) can no longer exist, so
-- the finder can never throw IncorrectResultSizeDataAccessException (the residual dual-row 500).
--
-- lower(email): InviteService now normalises e-mail to lower-case on dispatch (matching
-- AuthService), so the stored value already aligns with this index; case-folding here is the
-- defence-in-depth that also prevents any legacy "A@x"/"a@x" pair from both holding active
-- invites for the same salon. Partial (WHERE is_used = false): used invites are historical and must not
-- block a re-invite after acceptance. salon_id NULLs (salon deleted via ON DELETE SET NULL) are
-- distinct under a UNIQUE index, so orphaned invites never collide. Plain CREATE (no CONCURRENTLY)
-- — pre-launch table is tiny and Flyway runs each migration in its own transaction.
CREATE UNIQUE INDEX ux_invite_tokens_active
    ON invite_tokens (lower(email), salon_id)
    WHERE is_used = false;
