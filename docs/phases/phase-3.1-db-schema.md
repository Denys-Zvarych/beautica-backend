# Phase 3.1 — DB Schema: Services & Pricing

**Status: ✅ COMPLETED**
**Branch:** `feature/phase-3.1`
**PR:** [#13](https://github.com/Denys-Zvarych/beautica-backend/pull/13)

## Scope

Flyway migrations for the Services & Pricing domain tables.

## Migrations Delivered

| File | Table | Description |
|---|---|---|
| `V6__Create_service_definitions.sql` | `service_definitions` | Polymorphic service catalog (SALON or INDEPENDENT_MASTER owned) |
| `V7__Create_master_services.sql` | `master_services` | Per-master service assignment with price/duration overrides |

## Schema Details

### service_definitions
- Polymorphic ownership via `owner_type` (`SALON` | `INDEPENDENT_MASTER`) + `owner_id`
- CHECK: `owner_type IN ('SALON','INDEPENDENT_MASTER')`, `base_duration_minutes > 0`, `buffer_minutes_after >= 0`, `base_price >= 0` (nullable)
- Indexes: `(owner_type, owner_id)`, `(category)`

### master_services
- FK → `masters(id) ON DELETE CASCADE`
- FK → `service_definitions(id) ON DELETE CASCADE`
- UNIQUE `(master_id, service_def_id)`
- CHECK: `price_override >= 0` (nullable), `duration_override_minutes > 0` (nullable)
- Indexes: `(master_id)`, `(service_def_id)`

## Acceptance Criteria

- [x] `V6__Create_service_definitions.sql` applies cleanly via Flyway
- [x] `V7__Create_master_services.sql` applies cleanly via Flyway
- [x] All 6 CHECK constraints are present and correct
- [x] Both FK cascades are `ON DELETE CASCADE`
- [x] UNIQUE `(master_id, service_def_id)` enforced
- [x] 4 indexes created as specified
- [x] 53 integration tests pass with V1–V7 migration chain (Testcontainers)
- [x] CI passes (PR #13 — Build & Test ✅, JUnit Tests ✅)

## QA Score

**66/100** — schema correct, 11 constraint-enforcement tests deferred to Phase 3.2 (JPA entities required for `@DataJpaTest` harness; see QA debt below).

## QA Debt → Phase 3.2

Write `ServiceSchemaConstraintTest` using `@DataJpaTest` + Testcontainers covering:
1. `should_throwConstraintViolation_when_ownerTypeIsInvalid`
2. `should_throwConstraintViolation_when_baseDurationIsZero`
3. `should_throwConstraintViolation_when_baseDurationIsNegative`
4. `should_throwConstraintViolation_when_bufferMinutesIsNegative`
5. `should_throwConstraintViolation_when_basePriceIsNegative`
6. `should_persist_when_basePriceIsNull`
7. `should_throwConstraintViolation_when_duplicateMasterServiceAssignment`
8. `should_throwConstraintViolation_when_priceOverrideIsNegative`
9. `should_throwConstraintViolation_when_durationOverrideIsZero`
10. `should_cascadeDeleteMasterServices_when_masterIsDeleted`
11. `should_cascadeDeleteMasterServices_when_serviceDefinitionIsDeleted`
