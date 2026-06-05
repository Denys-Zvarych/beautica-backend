# Phase 17.2 — V74: Seed taxonomy into platform_categories ✅ COMPLETE

**Status: COMPLETE (applied via real Flyway run on the local DB).**

## Status

- `V74__seed_taxonomy_platform_categories.sql` ✅ implemented (21 active / 26 total platform categories; 3 renames + 5 soft-disables + 16 new)
- `ReparentServiceTypesMigrationTest` ✅ repaired (covers V74 rename/soft-disable/active-slug set + totals — all passing)
- `TaxonomySeedContractIT` ✅ implemented (14 new tests — all passing; slug surface, name_uk/name_en, category_id FK, bucket distribution, dup-name distinct-slug)
- QA score: 95/100 | Security: 99/100 | Perf: 90/100 | Completed: 2026-06-05 | Branch: `phases-16.x`

## Test Cases

Integration (Testcontainers, real Flyway boot):
- `ReparentServiceTypesMigrationTest` — 21 active / 26 total platform categories; HAIRCUT→HAIRDRESSING, MANICURE→NAIL_SERVICE, EYELASH→LASH_EXTENSIONS renamed + active; PEDICURE/HAIR/BODY/FACE/OTHER soft-disabled; BROWS/MAKEUP kept active; renamed display names.
- `PlatformCategoryRepositoryTest` — repository view of the active/approved picker set.

Migration: `V74__seed_taxonomy_platform_categories.sql`

## What it does

Brings `platform_categories` (the System-B picker behind
`GET /api/v1/service-categories/approved`) in line with the approved
21-category taxonomy. Source of truth: `phase-17.1-taxonomy-slug-artifact.md`.

1. **Rename** the three legacy slugs with a clean 1:1 successor:
   `HAIRCUT → HAIRDRESSING`, `MANICURE → NAIL_SERVICE`,
   `EYELASH → LASH_EXTENSIONS`. The V73 FK
   (`fk_service_types_platform_category`, `ON UPDATE CASCADE`) propagates the
   rename to any `service_types.platform_category_name` automatically; V75
   re-seeds `service_types` regardless.
2. **Refresh** the two kept slugs (`BROWS`, `MAKEUP`) — re-assert
   `display_name`, `APPROVED`, `active`.
3. **Soft-disable** (`active = FALSE`) every superseded legacy slug with no 1:1
   successor: `PEDICURE, HAIR, BODY, FACE, OTHER`. Soft-disable (not DELETE)
   preserves the `UNIQUE(name)` workflow guard and any legacy
   `service_definitions.category` reference.
4. **Insert** the 16 brand-new categories (`APPROVED`, `active = TRUE`,
   Ukrainian `display_name`).
5. **Re-assert** the 5 renamed/kept slugs via `INSERT … ON CONFLICT DO UPDATE`
   so a clean-DB replay (where the legacy rows never existed, so Step 1 matched
   nothing) still creates them.

## Idempotency / determinism

- Renames are guarded (`WHERE name = '<legacy>' AND NOT EXISTS (<successor>)`)
  → no-op on re-apply or when the successor already exists.
- Inserts use `ON CONFLICT (name) DO UPDATE` → re-asserts intended state on
  re-apply; clean-DB replay yields an identical 26-row set (21 active).
- No `gen_random_uuid()` in any business key (the picker key is `name`).
- Single transaction (Flyway wraps each migration; all DDL/DML here is
  transactional on PostgreSQL).

## Result (local Flyway run)

- `platform_categories`: **21 active**, 26 total (5 soft-disabled:
  PEDICURE, HAIR, BODY, FACE, OTHER).
- All 21 active rows are `status = APPROVED`.

## Constraint conformance

- Every `name` matches `chk_platform_categories_name` (`^[A-Z][A-Z0-9_]*$`).
- `status` ∈ {PENDING, APPROVED, REJECTED} — all seeds APPROVED.
- `display_name` NOT NULL — populated for every row.
