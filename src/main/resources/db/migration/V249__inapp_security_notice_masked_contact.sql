-- non-destructive
-- Reason: adds one NULLABLE column with no default (catalog-only change, no rewrite) and a NOT VALID CHECK that every existing row satisfies (the column is NULL on all of them); V250 validates it.
-- Phase B516 audit-fix cycle 2 (AUTH-D19) — a security notice must show the contact value AS IT WAS when the change
-- happened, not the account's current one: the masked value is stored on the row at write time. Only the two
-- SECURITY_* types may carry it, and it must be masked (contains '***'; ContactMask never returns an unmasked value).
-- Security rows written before this migration keep NULL and are shown without a value.
-- Length: users.email is VARCHAR(255); its mask is 1 + 3 + up to 254 characters, so 320 has headroom.
-- Applied migrations are immutable (V246/V247 untouched); same NOT VALID / VALIDATE split as V246 + V247.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification
    ADD COLUMN masked_contact VARCHAR(320),
    ADD CONSTRAINT in_app_notification_masked_contact_chk CHECK (
        masked_contact IS NULL
        OR (type IN ('SECURITY_PHONE_CHANGED', 'SECURITY_EMAIL_CHANGED')
            AND position('***' IN masked_contact) > 0)
    ) NOT VALID;
