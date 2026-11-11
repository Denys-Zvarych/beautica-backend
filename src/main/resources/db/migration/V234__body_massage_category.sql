-- V234: Phase 453 — category «Масаж і догляд за тілом» (BODY_MASSAGE) + 11 service types.
--
-- Seeds NAMES only: no durations, no prices (several lengths of one massage are copies of
-- one type, track D1 / phase 464). Version note: V233 belongs to phase 464; Flyway applies
-- V232 -> V234 cleanly on a branch without V233 (default outOfOrder=false only rejects a
-- LOWER version arriving after a higher one; merge in number order).
--
-- Rows
--   1. platform_categories — BODY_MASSAGE, APPROVED + active, initial_service_name 'Класичний масаж',
--                            mapped to the EXISTING coarse bucket «Тіло» (V13, 11111111-0006-..., sort_order 6)
--   2. service_types       — 11 leaves under BODY_MASSAGE / bucket «Тіло»
-- No new service_categories bucket (user decision 2026-11-10): GET /service-categories keeps its 8 buckets.
--
-- Idempotency / determinism
--   * platform category resolved by business key: ON CONFLICT (name) DO NOTHING
--   * service types: ON CONFLICT (slug) DO NOTHING (all 11 slugs verified unused by V1–V232)
--   Re-running the body inserts nothing; a clean-DB replay yields the identical row set.
--
-- Cache note: pure SQL — the running app's 'approved-categories', 'platform-category-order',
-- 'service-types' caches are refreshed by the deploy restart (same footgun as V74/V75/V81).

INSERT INTO platform_categories (name, display_name, status, active, initial_service_name, service_category_id) VALUES
    ('BODY_MASSAGE', 'Масаж і догляд за тілом', 'APPROVED', TRUE, 'Класичний масаж',
     '11111111-0006-0000-0000-000000000000')
ON CONFLICT (name) DO NOTHING;

INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'classic-massage',            'Класичний масаж',          'Classic Massage',            TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'relax-massage',              'Релакс масаж',             'Relax Massage',              TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'classic-back-massage',       'Класичний масаж спини',    'Classic Back Massage',       TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'sports-massage',             'Спортивний масаж',         'Sports Massage',             TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'signature-massage',          'Авторський масаж',         'Signature Massage',          TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'anti-cellulite-massage',     'Антицелюлітний масаж',     'Anti-Cellulite Massage',     TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'lymphatic-drainage-massage', 'Лімфодренажний масаж',     'Lymphatic Drainage Massage', TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'whisky-wrap',                'Віскі-пеленання',          'Whisky Wrap',                TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'signature-massage-mask',     'Авторський масаж + маска', 'Signature Massage + Mask',   TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'spa-procedure',              'SPA-процедура',            'SPA Procedure',              TRUE),
    ('11111111-0006-0000-0000-000000000000', 'BODY_MASSAGE', 'cedar-phyto-barrel',         'Кедрова фітобочка',        'Cedar Phyto Barrel',         TRUE)
ON CONFLICT (slug) DO NOTHING;
