# Phase 17.3 — V75: Re-seed service_types with the taxonomy ✅ COMPLETE

**Status: COMPLETE (applied via real Flyway run on the local DB).**

## Status

- `V75__reseed_service_types_taxonomy.sql` ✅ implemented (DELETE + 140 leaves; NOT-NULL category_id + FK-valid platform_category_name + unique regex-valid slug + name_uk/name_en)
- `TaxonomySeedContractIT` ✅ implemented (14 new tests — all passing)
- `ReparentServiceTypesMigrationTest`, `ServiceTypePlatformCategoryRepositoryTest`, `CatalogRepositoryTest` ✅ repaired — all passing
- Targeted run: 74 tests GREEN (new IT + 4 repaired catalog classes), 0 failures
- QA score: 95/100 | Security: 99/100 | Perf: 90/100 | Completed: 2026-06-05 | Branch: `phases-16.x`

## Test Cases

Integration (Testcontainers, real Flyway boot):
- `TaxonomySeedContractIT` (14 tests) —
  - Slug surface: all 140 globally unique; every slug matches `^[a-z0-9][a-z0-9\-]*[a-z0-9]$` (per-row).
  - Display names: `name_uk` and `name_en` non-null + non-blank on all 140.
  - `category_id` FK integrity: every value resolves to a real `service_categories` bucket (zero orphans).
  - Coarse-bucket distribution (21→8): Nails 17, Eyelashes 13, Brows 8, Hair 56, Face/Skin 26, Body 6, Makeup 14, Other 0.
  - Cross-category dup name → distinct slugs: `Корекція` under HAIR_EXTENSIONS/BROWS/PERMANENT_MAKEUP with 3 distinct prefixed slugs.
- `ReparentServiceTypesMigrationTest` — 140 total / all active; per-platform-category active leaf counts; zero-orphan platform_category_name; NOT-NULL columns; closed-set DB guard rejects unknown + lowercase slugs; Flyway V73/V74/V75 success.
- `ServiceTypePlatformCategoryRepositoryTest`, `CatalogRepositoryTest` — repository-layer reads over the reseeded catalog.

## Acceptance Criteria

- [x] `service_types` holds exactly 140 active leaves after V75.
- [x] Every leaf has non-null `category_id` resolving to a real `service_categories` row.
- [x] Every leaf has FK-valid `platform_category_name` resolving to an active `platform_categories` row.
- [x] All 140 slugs globally unique and regex-valid.
- [x] `name_uk` / `name_en` non-null + non-blank on all 140.
- [x] Cross-category duplicate display names carry distinct, category-prefixed slugs.
- [x] Re-seed is deterministic / idempotent (clean-DB replay → identical 140-row set).
- [x] Dedicated seed-contract test authored and GREEN (Step 2.7 Rule 3).

Migration: `V75__reseed_service_types_taxonomy.sql` (ordered after V74).

## What it does

Replaces the legacy 49-row V13 `service_types` seed with the 140 leaf services
of the 21-category taxonomy. Source of truth:
`phase-17.1-taxonomy-slug-artifact.md`.

1. `DELETE FROM service_types;` — destructive re-seed (safe; see
   `phase-17.0-taxonomy-preflight-audit.md`: 0 `service_definitions`, the
   `service_type_id` FK is `ON DELETE SET NULL`).
2. Insert all 140 leaves, one `INSERT` block per category for reviewability.

Each row carries:
- `category_id` → one of the 8 coarse `service_categories` buckets (V13 UUIDs;
  NOT NULL, FK `ON DELETE RESTRICT`).
- `platform_category_name` → the `platform_categories.name` slug seeded/renamed
  in V74 (resolved by business key, not id).
- `slug` → globally unique, category-prefixed; satisfies the entity `@Pattern`
  and the stricter DB CHECK `^[a-z0-9][a-z0-9\-]*[a-z0-9]$`.
- `name_uk` / `name_en` → both populated (both NOT NULL).
- `is_active = TRUE`.

## Ordering dependency

**V75 must run after V74** (Flyway version order guarantees this). Every
`platform_category_name` value in V75 must already exist as a
`platform_categories.name` row (created/renamed in V74). Where the V73 FK
`fk_service_types_platform_category` is present (canonical/prod schema) it
enforces this closed set at insert time; where it is absent (drifted local
schema — see 17.0) the regex CHECK + V74's guaranteed rows still hold, and the
artifact's machine-check confirms 0 unresolvable references.

## Idempotency / determinism

- `DELETE` then deterministic `INSERT`s. No `gen_random_uuid()` in any business
  key — `id` is the DB DEFAULT; `slug` is the stable business key. A clean-DB
  replay yields an identical 140-row set.

## Result (local Flyway run)

- `service_types`: **140** rows.
- Per platform category: HAIRDRESSING 11, HAIR_COLORING 9, HAIR_TREATMENT 7,
  HAIR_EXTENSIONS 4, TRICHOLOGY 3, NAIL_SERVICE 12, PODOLOGY 5, BROWS 8,
  LASH_LAMINATION 3, LASH_EXTENSIONS 10, MAKEUP 8, COSMETOLOGY 5,
  HARDWARE_COSMETOLOGY 5, INJECTION_COSMETOLOGY 6, AESTHETIC_COSMETOLOGY 3,
  LASER_COSMETOLOGY 7, HAIR_REMOVAL 6, PERMANENT_MAKEUP 6, BARBERING 10,
  BEARD_CARE 7, SHAVING 5.
- Per coarse bucket: Нігті 17, Вії 13, Брови 8, Волосся 56, Обличчя/Шкіра 26,
  Тіло 6, Макіяж 14.
- 0 duplicate slugs, 0 slug-CHECK violations, 0 null `name_en` / `name_uk` /
  `category_id` / `platform_category_name`, 0 unresolvable FK references.

## Follow-up (not in this track)

- backend-qa owns the JUnit/Testcontainers migration integration test
  (assert counts, slug uniqueness, FK validity) and must add
  `DELETE FROM service_types` (already present) to the shared
  `AbstractIntegrationTest.cleanDb()` FK-ordered teardown if any new dependent
  table is introduced.
- Before prod apply, confirm prod `service_definitions` has no
  `service_type_id` references (17.0 TODO).
