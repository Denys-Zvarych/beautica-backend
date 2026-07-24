-- Relax the DAY_OFF reason requirement on schedule_exceptions.
--
-- V71 introduced chk_exc_reason which hard-required a non-null reason for every DAY_OFF row:
--     (kind = 'DAY_OFF'      AND reason IS NOT NULL)
--  OR (kind = 'CUSTOM_HOURS' AND reason IS NULL)
--
-- Product decision (2026-06): marking a day off no longer needs a reason. DAY_OFF.reason is now
-- OPTIONAL — it may be NULL or non-NULL (existing rows that already carry a reason stay valid).
-- The CUSTOM_HOURS side is byte-for-byte unchanged: those rows must NOT carry a reason.
--
-- The reason column stays NULLABLE (already DROP NOT NULL in V71); we only loosen the CHECK.
ALTER TABLE schedule_exceptions DROP CONSTRAINT IF EXISTS chk_exc_reason;

ALTER TABLE schedule_exceptions
    ADD CONSTRAINT chk_exc_reason CHECK (
        kind = 'DAY_OFF' OR (kind = 'CUSTOM_HOURS' AND reason IS NULL));
