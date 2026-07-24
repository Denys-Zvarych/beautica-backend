-- Enforce at most one SALON_ADMIN per salon.
-- Partial unique index: only rows where role = 'SALON_ADMIN' are covered,
-- so multiple SALON_MASTER rows with the same salon_id remain valid.
CREATE UNIQUE INDEX uq_users_salon_admin
    ON users (salon_id)
    WHERE role = 'SALON_ADMIN';
