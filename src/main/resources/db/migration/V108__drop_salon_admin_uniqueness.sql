-- Phase 21.1 — Multi-Admin Relaxation: a salon may now have more than one SALON_ADMIN.
-- This index (created by V8__Add_salon_admin_role.sql) enforced at most one SALON_ADMIN
-- per salon. Dropping a unique index never violates existing rows, so no data migration
-- is required.
DROP INDEX IF EXISTS uq_users_salon_admin;
