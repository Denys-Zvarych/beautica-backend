# Phase 17.0 — Taxonomy Re-seed Preflight Audit ✅ COMPLETE

**Status: COMPLETE**

## Status

- Planning / audit doc only — **no code artifact**. Records that the destructive
  `service_types` re-seed path is safe (0 `service_definitions`, FK `ON DELETE SET NULL`)
  and pins the three-level catalog schema the V74/V75 migrations rely on.
- QA score: n/a (no test surface of its own) | Completed: 2026-06-05 | Branch: `phases-16.x`
- Verified downstream by V74/V75 migration + seed-contract tests (see 17.2 / 17.3).

Track 17.x replaces the legacy 9-category service catalog with the approved
**21-category beauty-service taxonomy**. Because the re-seed of `service_types`
is destructive (`DELETE` + re-`INSERT`), this preflight records the audit that
proves the destructive path is safe and documents the exact schema shape the
migrations rely on.

## Catalog is THREE levels (verified against the live local DB)

1. **`service_categories`** — 8 coarse buckets (V13 seed). Columns: `id uuid`,
   `name_uk NOT NULL`, `name_en NOT NULL`, `sort_order NOT NULL`. **Not touched**
   by track 17.x — the 21 categories are mapped onto these 8 existing buckets.

   | UUID prefix | name_uk | name_en |
   |---|---|---|
   | `11111111-0001-…` | Нігті | Nails |
   | `11111111-0002-…` | Вії | Eyelashes |
   | `11111111-0003-…` | Брови | Brows |
   | `11111111-0004-…` | Волосся | Hair |
   | `11111111-0005-…` | Обличчя / Шкіра | Face / Skin |
   | `11111111-0006-…` | Тіло | Body |
   | `11111111-0007-…` | Макіяж | Makeup |
   | `11111111-0008-…` | Інше | Other |

2. **`platform_categories`** — System-B picker read by
   `GET /api/v1/service-categories/approved`. Columns of interest:
   `name VARCHAR(100) UNIQUE` (regex `^[A-Z][A-Z0-9_]*$`),
   `display_name NOT NULL` (Ukrainian), `status` (CHECK ∈ {PENDING, APPROVED,
   REJECTED}, default APPROVED), `active BOOLEAN`. `created_at DEFAULT NOW()`.
   Pre-17.x rows: `BODY, BROWS, EYELASH, FACE, HAIR, HAIRCUT, MAKEUP, MANICURE,
   PEDICURE` (active) + `OTHER` (active = FALSE).

3. **`service_types`** — System-A leaf services. Columns: `id uuid`,
   `category_id uuid NOT NULL` → `service_categories.id` (`ON DELETE RESTRICT`),
   `platform_category_name VARCHAR(100)` → `platform_categories.name`
   (V73 FK `ON UPDATE CASCADE ON DELETE RESTRICT`),
   `slug VARCHAR(255) NOT NULL` UNIQUE (DB CHECK `^[a-z0-9][a-z0-9\-]*[a-z0-9]$`),
   `name_uk NOT NULL`, `name_en NOT NULL` (DB default `''`), `is_active`,
   `created_at`/`updated_at` (DEFAULT NOW()). Pre-17.x: 49 rows / 9 platform
   categories.

## Re-seed safety (audited on the local DB)

| Check | Result | Implication |
|---|---|---|
| `service_definitions` row count | **0** | No real catalog data to lose. |
| `service_definitions.service_type_id IS NOT NULL` | **0** | No live FK references into `service_types`. |
| `service_definitions.service_type_id` FK rule | `ON DELETE SET NULL` | Even a future referencing row survives a `DELETE FROM service_types`. |
| `service_definitions.category` (free text) | no FK, empty | `platform_categories` renames do **not** cascade to it; nothing to backfill. |

**Verdict: GO for a destructive `service_types` re-seed locally.**

## Local-DB checksum caveat (discovered during smoke)

The on-disk `V73__reparent_service_types_to_platform_categories.sql` was edited
**after** this local DB migrated it (Steps 4–6 — the
`fk_service_types_platform_category` FK, the `NOT NULL` on
`platform_category_name`, and the `idx_service_types_platform_category` partial
index — are present in the file but absent in this DB). The recorded V73
checksum therefore drifted, and Flyway `validate()` blocks startup under the
`local` profile (the self-healing `FlywayRepairConfig` is `@Profile("prod")`
only — see memory `project_flyway_checksum_recovery.md`).

This is a **pre-existing local condition, unrelated to V74/V75**. It was
resolved for the local smoke by the documented mode-1 recovery (realign the V73
history checksum), then the original recorded checksum was restored so the
local DB is left untouched. **V74/V75 do not depend on the V73 FK/NOT-NULL
being present** — they rely only on `platform_categories.name` (UNIQUE, present
everywhere) and the `service_types` column set, so they apply correctly on both
the drifted-local and the canonical-prod schema.

## TODO before prod apply (out of scope for this track)

- **Confirm prod Neon `service_definitions` is empty** (or has no
  `service_type_id` references) before shipping V75; the destructive re-seed
  assumption is verified only locally here.
- Confirm prod V73 checksum is intact (prod ran the canonical V73; the
  `FlywayRepairConfig` prod strategy would auto-realign if not).
