-- non-destructive
-- Reason: one NOT NULL column with a constant DEFAULT 0 (metadata-only on PG 11+, no rewrite) plus a CHECK on that same new column, which every existing row (0) and every previous-app write (column omitted, default 0) satisfies.
-- Phone-first hardening (AUTH-D26 c) — the durable per-phone failed-verify counter. attempts keeps counting reserved
-- compares per code (per-code cap, B1); failed_attempts counts only the mismatches on this row. PhoneOtpService sums
-- failed_attempts over rows created in the last 24 h for one phone (any purpose) — served by the existing
-- idx_phone_otps_phone_created_at (phone, created_at) — and refuses verifies (INVALID_CODE) and sends (429) at 10.
-- PhoneOtpCleanupJob keeps rows for 25 h (by created_at) so the 24 h window survives the 03:00 sweep.
SET LOCAL lock_timeout = '3s';

ALTER TABLE phone_otps
    ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE phone_otps
    ADD CONSTRAINT ck_phone_otps_failed_attempts_non_negative
    CHECK (failed_attempts >= 0);
