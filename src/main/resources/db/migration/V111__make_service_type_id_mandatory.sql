-- Phase 16.x — make service_definitions.service_type_id MANDATORY at the DB level.
--
-- WHY: service_type_id is the finer taxonomy that service-type SEARCH filters on. The
-- single-create path used to leave it optional (CreateServiceDefinitionRequest.serviceTypeId
-- was @Nullable and the service only set the FK "if (serviceType != null)"), so a create with
-- the picker skipped persisted service_type_id = NULL. Untyped rows are then silently dropped
-- by service-type search. The DTO is now @NotNull and every create path always sets the FK;
-- this migration closes the gap at the storage layer so it can never be violated again.
--
-- NO BACKFILL NEEDED: there is no prod DB. Local is wiped + reseeded fresh by
-- seed-demo-fleet.sh, which sets service_type_id on every row (0 NULLs), and a clean-DB
-- replay runs this ALTER against an empty table. SET NOT NULL therefore always succeeds.
-- (No UPDATE ... SET service_type_id is written, by design.)
--
-- FK on-delete change: V15 added the FK inline as ON DELETE SET NULL. A NOT NULL column can no
-- longer be SET NULL when the parent service_type is deleted, so the behavior must change to
-- RESTRICT — deleting a service type that is still referenced by any service definition is now
-- prevented (fail fast) rather than orphaning the child row. The V15 inline FK was auto-named
-- by Postgres as service_definitions_service_type_id_fkey (same convention as
-- password_reset_tokens_user_id_fkey renamed in V107).
--
-- Clean forward migration only (Flyway crash-recovery playbook): no validate()/repair changes.
-- IF EXISTS on the DROP keeps a clean-DB replay / re-apply idempotent.

ALTER TABLE service_definitions
    ALTER COLUMN service_type_id SET NOT NULL;

ALTER TABLE service_definitions
    DROP CONSTRAINT IF EXISTS service_definitions_service_type_id_fkey;

ALTER TABLE service_definitions
    ADD CONSTRAINT service_definitions_service_type_id_fkey
        FOREIGN KEY (service_type_id) REFERENCES service_types(id) ON DELETE RESTRICT;
