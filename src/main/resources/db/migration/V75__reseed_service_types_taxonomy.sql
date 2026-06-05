-- V75: Re-seed service_types with the 21-category taxonomy (track 17.x).
--
-- Context
-- -------
-- service_types holds the System-A leaf services. The legacy 49-row V13 seed is
-- replaced wholesale by the approved 21-category taxonomy. Re-seed is destructive
-- (DELETE then INSERT) and safe locally/pre-launch because:
--   * service_definitions = 0 rows (no real catalog yet);
--   * service_definitions.service_type_id FK is ON DELETE SET NULL, so even a
--     referencing row would survive the DELETE (its service_type_id nulls).
-- See docs/backend-phases/phase-17.0-taxonomy-preflight-audit.md.
--
-- Each row carries:
--   * category_id            -> one of the 8 coarse service_categories buckets
--                               (V13 seed UUIDs; NOT NULL FK, ON DELETE RESTRICT).
--   * platform_category_name -> the platform_categories.name slug seeded/renamed
--                               in V74 (must run BEFORE this migration — it does:
--                               V74 < V75). The V73 FK (where applied) enforces
--                               the closed set; where the FK is absent (older V73
--                               on some envs) the chk regex + V74 still hold.
--   * slug                   -> globally-unique, category-prefixed where a bare
--                               display name would collide across categories.
--                               Satisfies BOTH the entity @Pattern
--                               (^[a-z0-9]+(?:-[a-z0-9]+)*$) and the stricter DB
--                               CHECK (^[a-z0-9][a-z0-9\-]*[a-z0-9]$, min len 2).
--   * name_uk / name_en      -> both NOT NULL and populated for every row.
--   * is_active              -> TRUE.
--
-- Idempotency / determinism
-- -------------------------
-- DELETE FROM service_types clears the table; the INSERTs are deterministic
-- (no gen_random_uuid in any business key — id is the DB DEFAULT, slug is the
-- stable business key). A clean-DB replay yields an identical row set.
-- platform_category_name is resolved by the V74 slug (business key), not by id.

-- Coarse-bucket UUIDs (service_categories, from V13):
--   Nails       11111111-0001-0000-0000-000000000000
--   Eyelashes   11111111-0002-0000-0000-000000000000
--   Brows       11111111-0003-0000-0000-000000000000
--   Hair        11111111-0004-0000-0000-000000000000
--   Face / Skin 11111111-0005-0000-0000-000000000000
--   Body        11111111-0006-0000-0000-000000000000
--   Makeup      11111111-0007-0000-0000-000000000000

DELETE FROM service_types;

-- ============================================================
-- 1. Перукарські послуги  -> HAIRDRESSING  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-womens-cut',      'Жіноча стрижка',        'Women''s Haircut',        TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-mens-cut',        'Чоловіча стрижка',      'Men''s Haircut',          TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-kids-cut',        'Дитяча стрижка',        'Kids'' Haircut',          TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-trim',            'Підрівнювання кінчиків','Ends Trim',               TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-fringe-cut',      'Стрижка чубчика',       'Fringe Cut',              TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-evening-updo',    'Вечірня зачіска',       'Evening Updo',            TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-bridal-updo',     'Весільна зачіска',      'Bridal Updo',             TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-curls',           'Локони',                'Curls',                   TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-hollywood-wave',  'Голлівудська хвиля',    'Hollywood Wave',          TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-blow-dry',        'Укладання феном',       'Blow-Dry Styling',        TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIRDRESSING', 'hairdressing-braiding',        'Плетіння кіс',          'Braiding',                TRUE);

-- ============================================================
-- 2. Фарбування волосся  -> HAIR_COLORING  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-single-tone',  'Однотонне фарбування',  'Single-Tone Coloring',    TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-toning',       'Тонування',             'Toning',                  TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-airtouch',     'Airtouch',              'Airtouch',                TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-balayage',     'Balayage',              'Balayage',                TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-ombre',        'Ombre',                 'Ombre',                   TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-shatush',      'Shatush',               'Shatush',                 TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-highlights',   'Мелірування',           'Highlights',              TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-blonding',     'Блонд',                 'Blonding',                TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_COLORING', 'hair-coloring-black-removal', 'Вихід з чорного',      'Black Color Removal',     TRUE);

-- ============================================================
-- 3. Відновлення волосся  -> HAIR_TREATMENT  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'HAIR_TREATMENT', 'hair-treatment-keratin',       'Кератин',             'Keratin Treatment',     TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_TREATMENT', 'hair-treatment-botox',         'Ботокс для волосся',  'Hair Botox',            TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_TREATMENT', 'hair-treatment-nanoplasty',    'Нанопластика',        'Nanoplasty',            TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_TREATMENT', 'hair-treatment-cold-recovery', 'Холодне відновлення', 'Cold Recovery',         TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_TREATMENT', 'hair-treatment-happiness',     'Щастя для волосся',   'Hair Happiness',        TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_TREATMENT', 'hair-treatment-lamination',    'Ламінування',         'Hair Lamination',       TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_TREATMENT', 'hair-treatment-polishing',     'Полірування волосся', 'Hair Polishing',        TRUE);

-- ============================================================
-- 4. Нарощування волосся  -> HAIR_EXTENSIONS  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'HAIR_EXTENSIONS', 'hair-extensions-capsule',    'Капсульне',  'Capsule Extensions',  TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_EXTENSIONS', 'hair-extensions-tape',       'Стрічкове',  'Tape-In Extensions',  TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_EXTENSIONS', 'hair-extensions-correction', 'Корекція',   'Extensions Correction',TRUE),
  ('11111111-0004-0000-0000-000000000000', 'HAIR_EXTENSIONS', 'hair-extensions-removal',    'Зняття',     'Extensions Removal',  TRUE);

-- ============================================================
-- 5. Трихологія  -> TRICHOLOGY  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'TRICHOLOGY', 'trichology-consultation',     'Консультація трихолога',     'Trichologist Consultation', TRUE),
  ('11111111-0004-0000-0000-000000000000', 'TRICHOLOGY', 'trichology-scalp-diagnostics','Діагностика шкіри голови',   'Scalp Diagnostics',         TRUE),
  ('11111111-0004-0000-0000-000000000000', 'TRICHOLOGY', 'trichology-scalp-peeling',    'Пілінг шкіри голови',        'Scalp Peeling',             TRUE);

-- ============================================================
-- 6. Нігтьовий сервіс  -> NAIL_SERVICE  | bucket: Nails (0001)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-manicure',                'Манікюр',                       'Manicure',                          TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-manicure-gel-polish',     'Манікюр+гель лак',              'Manicure + Gel Polish',             TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-manicure-strengthening',  'Манікюр+укріплення',            'Manicure + Strengthening',          TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-manicure-french',         'Манікюр+френч',                 'Manicure + French',                 TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-removal-no-coating',      'Зняття без покриття',           'Removal Without Coating',           TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-removal-other-master',    'Зняття іншого майстра',         'Removal of Other Master''s Work',   TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-restore-strengthen-coat', 'Реставрація укріплення+покриття','Strengthening Restore + Coating',   TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-pedicure-toes',           'Педикюр/пальчики',              'Pedicure (Toes)',                   TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-pedicure-full',           'Педикюр повний',                'Full Pedicure',                     TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-pedicure-coating',        'Педикюр +покриття',             'Pedicure + Coating',                TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-pedicure-full-coating',   'Педикюр повний +покриття',      'Full Pedicure + Coating',           TRUE),
  ('11111111-0001-0000-0000-000000000000', 'NAIL_SERVICE', 'nail-service-extension',               'Нарощення нігтів',              'Nail Extension',                    TRUE);

-- ============================================================
-- 7. Подологія  -> PODOLOGY  | bucket: Nails (0001)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0001-0000-0000-000000000000', 'PODOLOGY', 'podology-ingrown-nail',     'Обробка врослого нігтя',  'Ingrown Nail Treatment',    TRUE),
  ('11111111-0001-0000-0000-000000000000', 'PODOLOGY', 'podology-calluses',         'Мозолі',                  'Calluses',                  TRUE),
  ('11111111-0001-0000-0000-000000000000', 'PODOLOGY', 'podology-cracks',           'Тріщини',                 'Cracks',                    TRUE),
  ('11111111-0001-0000-0000-000000000000', 'PODOLOGY', 'podology-nail-prosthetics', 'Протезування нігтя',      'Nail Prosthetics',          TRUE),
  ('11111111-0001-0000-0000-000000000000', 'PODOLOGY', 'podology-consultation',     'Консультація подолога',   'Podologist Consultation',   TRUE);

-- ============================================================
-- 8. Оформлення брів  -> BROWS  | bucket: Brows (0003)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-correction',        'Корекція',                  'Brow Correction',         TRUE),
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-tint-dye',          'Фарбування фарба',          'Brow Tint (Dye)',         TRUE),
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-tint-henna',        'Фарбування хна',            'Brow Tint (Henna)',       TRUE),
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-lamination',        'Ламінування',               'Brow Lamination',         TRUE),
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-architecture',      'Архітектура брів',          'Brow Architecture',       TRUE),
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-lightening',        'Освітлення брів',           'Brow Lightening',         TRUE),
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-long-lasting-style','Довготривала укладка брів', 'Long-Lasting Brow Styling',TRUE),
  ('11111111-0003-0000-0000-000000000000', 'BROWS', 'brows-mens-correction',   'Чоловіча корекція',         'Men''s Brow Correction',  TRUE);

-- ============================================================
-- 9. Ламінування вій  -> LASH_LAMINATION  | bucket: Eyelashes (0002)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0002-0000-0000-000000000000', 'LASH_LAMINATION', 'lash-lamination',       'Ламінування вій', 'Lash Lamination', TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_LAMINATION', 'lash-lamination-botox', 'Ботокс вій',      'Lash Botox',      TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_LAMINATION', 'lash-lamination-tint',  'Фарбування вій',  'Lash Tint',       TRUE);

-- ============================================================
-- 10. Нарощення вій  -> LASH_EXTENSIONS  | bucket: Eyelashes (0002)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-inner-corners', 'Кутики',                  'Inner Corners',            TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-classic',       'Класичне нарощення',      'Classic Extensions',       TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-2d',            '2д обʼєм',                '2D Volume',                TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-3d',            '3д обʼєм',                '3D Volume',                TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-4d',            '4д обʼєм',                '4D Volume',                TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-mega-volume',   'Мега обʼєм',              'Mega Volume',              TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-express',       'Експрес нарощення',       'Express Extensions',       TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-wet-look',      'Мокрий ефект',            'Wet-Look Effect',          TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-correction',    'Корекція нарощених вій',  'Lash Extensions Correction',TRUE),
  ('11111111-0002-0000-0000-000000000000', 'LASH_EXTENSIONS', 'lash-extensions-removal',       'Зняття нарощених вій',    'Lash Extensions Removal',  TRUE);

-- ============================================================
-- 11. Макіяж  -> MAKEUP  | bucket: Makeup (0007)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-day',          'Денний',            'Day Makeup',          TRUE),
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-evening',      'Вечірній',          'Evening Makeup',      TRUE),
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-bridal',       'Весільний',         'Bridal Makeup',       TRUE),
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-nude',         'Nude',              'Nude Makeup',         TRUE),
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-lifting',      'Ліфтинг-макіяж',    'Lifting Makeup',      TRUE),
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-photoshoot',   'Фотосесія',         'Photoshoot Makeup',   TRUE),
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-event',        'Макіяж для події',  'Event Makeup',        TRUE),
  ('11111111-0007-0000-0000-000000000000', 'MAKEUP', 'makeup-mens',         'Чоловічий макіяж',  'Men''s Makeup',       TRUE);

-- ============================================================
-- 12. Косметологія  -> COSMETOLOGY  | bucket: Face / Skin (0005)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0005-0000-0000-000000000000', 'COSMETOLOGY', 'cosmetology-facial-cleanse', 'Чистка обличчя',     'Facial Cleanse',     TRUE),
  ('11111111-0005-0000-0000-000000000000', 'COSMETOLOGY', 'cosmetology-peels',          'Пілінги',            'Peels',              TRUE),
  ('11111111-0005-0000-0000-000000000000', 'COSMETOLOGY', 'cosmetology-masks',          'Маски',              'Masks',              TRUE),
  ('11111111-0005-0000-0000-000000000000', 'COSMETOLOGY', 'cosmetology-care-programs',  'Доглядові програми', 'Care Programs',      TRUE),
  ('11111111-0005-0000-0000-000000000000', 'COSMETOLOGY', 'cosmetology-face-massage',   'Масаж обличчя',      'Face Massage',       TRUE);

-- ============================================================
-- 13. Апаратна косметологія  -> HARDWARE_COSMETOLOGY  | bucket: Face / Skin (0005)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0005-0000-0000-000000000000', 'HARDWARE_COSMETOLOGY', 'hardware-cosmetology-rf-lift',     'RF-ліфтинг',     'RF Lifting',        TRUE),
  ('11111111-0005-0000-0000-000000000000', 'HARDWARE_COSMETOLOGY', 'hardware-cosmetology-microcurrent','Мікроструми',    'Microcurrent',      TRUE),
  ('11111111-0005-0000-0000-000000000000', 'HARDWARE_COSMETOLOGY', 'hardware-cosmetology-ultrasound',  'Ультразвук',     'Ultrasound',        TRUE),
  ('11111111-0005-0000-0000-000000000000', 'HARDWARE_COSMETOLOGY', 'hardware-cosmetology-carboxy',     'Карбоксітерапія','Carboxytherapy',    TRUE),
  ('11111111-0005-0000-0000-000000000000', 'HARDWARE_COSMETOLOGY', 'hardware-cosmetology-hydrafacial', 'Hydrafacial',    'Hydrafacial',       TRUE);

-- ============================================================
-- 14. Ін'єкційна косметологія  -> INJECTION_COSMETOLOGY  | bucket: Face / Skin (0005)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0005-0000-0000-000000000000', 'INJECTION_COSMETOLOGY', 'injection-botox',           'Ботокс',            'Botox',                 TRUE),
  ('11111111-0005-0000-0000-000000000000', 'INJECTION_COSMETOLOGY', 'injection-biorevitalization','Біоревіталізація', 'Biorevitalization',     TRUE),
  ('11111111-0005-0000-0000-000000000000', 'INJECTION_COSMETOLOGY', 'injection-mesotherapy',     'Мезотерапія',       'Mesotherapy',           TRUE),
  ('11111111-0005-0000-0000-000000000000', 'INJECTION_COSMETOLOGY', 'injection-contouring',      'Контурна пластика', 'Contour Plastic',       TRUE),
  ('11111111-0005-0000-0000-000000000000', 'INJECTION_COSMETOLOGY', 'injection-lip-augmentation','Збільшення губ',    'Lip Augmentation',      TRUE),
  ('11111111-0005-0000-0000-000000000000', 'INJECTION_COSMETOLOGY', 'injection-lipolytics',      'Ліполітики',        'Lipolytics',            TRUE);

-- ============================================================
-- 15. Естетична косметологія  -> AESTHETIC_COSMETOLOGY  | bucket: Face / Skin (0005)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0005-0000-0000-000000000000', 'AESTHETIC_COSMETOLOGY', 'aesthetic-acne-treatment', 'Лікування акне', 'Acne Treatment',     TRUE),
  ('11111111-0005-0000-0000-000000000000', 'AESTHETIC_COSMETOLOGY', 'aesthetic-pigmentation',   'Пігментація',    'Pigmentation',       TRUE),
  ('11111111-0005-0000-0000-000000000000', 'AESTHETIC_COSMETOLOGY', 'aesthetic-couperose',      'Купероз',        'Couperose',          TRUE);

-- ============================================================
-- 16. Лазерна косметологія  -> LASER_COSMETOLOGY  | bucket: Face / Skin (0005)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0005-0000-0000-000000000000', 'LASER_COSMETOLOGY', 'laser-hair-removal',          'Лазерна епіляція',     'Laser Hair Removal',      TRUE),
  ('11111111-0005-0000-0000-000000000000', 'LASER_COSMETOLOGY', 'laser-photoepilation',        'Фотоепіляція',         'Photoepilation',          TRUE),
  ('11111111-0005-0000-0000-000000000000', 'LASER_COSMETOLOGY', 'laser-rejuvenation',          'Лазерне омолодження',  'Laser Rejuvenation',      TRUE),
  ('11111111-0005-0000-0000-000000000000', 'LASER_COSMETOLOGY', 'laser-vascular-removal',      'Видалення судин',      'Vascular Removal',        TRUE),
  ('11111111-0005-0000-0000-000000000000', 'LASER_COSMETOLOGY', 'laser-pigmentation-removal',  'Видалення пігментації','Pigmentation Removal',    TRUE),
  ('11111111-0005-0000-0000-000000000000', 'LASER_COSMETOLOGY', 'laser-tattoo-removal',        'Видалення тату',       'Tattoo Removal',          TRUE),
  ('11111111-0005-0000-0000-000000000000', 'LASER_COSMETOLOGY', 'laser-permanent-removal',     'Видалення перманенту', 'Permanent Makeup Removal',TRUE);

-- ============================================================
-- 17. Депіляція та епіляція  -> HAIR_REMOVAL  | bucket: Body (0006)
--     "Воскова депіляція" zones flattened to 5 distinct service_types + sugaring.
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0006-0000-0000-000000000000', 'HAIR_REMOVAL', 'waxing-legs',      'Воскова депіляція — ноги',   'Waxing — Legs',      TRUE),
  ('11111111-0006-0000-0000-000000000000', 'HAIR_REMOVAL', 'waxing-arms',      'Воскова депіляція — руки',   'Waxing — Arms',      TRUE),
  ('11111111-0006-0000-0000-000000000000', 'HAIR_REMOVAL', 'waxing-underarms', 'Воскова депіляція — пахви',  'Waxing — Underarms', TRUE),
  ('11111111-0006-0000-0000-000000000000', 'HAIR_REMOVAL', 'waxing-bikini',    'Воскова депіляція — бікіні', 'Waxing — Bikini',    TRUE),
  ('11111111-0006-0000-0000-000000000000', 'HAIR_REMOVAL', 'waxing-face',      'Воскова депіляція — обличчя','Waxing — Face',      TRUE),
  ('11111111-0006-0000-0000-000000000000', 'HAIR_REMOVAL', 'sugaring',         'Шугаринг',                   'Sugaring',           TRUE);

-- ============================================================
-- 18. Перманентний макіяж  -> PERMANENT_MAKEUP  | bucket: Makeup (0007)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0007-0000-0000-000000000000', 'PERMANENT_MAKEUP', 'permanent-makeup-brows',         'Брови',             'Permanent Brows',          TRUE),
  ('11111111-0007-0000-0000-000000000000', 'PERMANENT_MAKEUP', 'permanent-makeup-lips',          'Губи',              'Permanent Lips',           TRUE),
  ('11111111-0007-0000-0000-000000000000', 'PERMANENT_MAKEUP', 'permanent-makeup-eyelids',       'Повіки',            'Permanent Eyelids',        TRUE),
  ('11111111-0007-0000-0000-000000000000', 'PERMANENT_MAKEUP', 'permanent-makeup-correction',    'Корекція',          'Permanent Makeup Correction',TRUE),
  ('11111111-0007-0000-0000-000000000000', 'PERMANENT_MAKEUP', 'permanent-makeup-laser-removal', 'Видалення лазером', 'Laser Removal',            TRUE),
  ('11111111-0007-0000-0000-000000000000', 'PERMANENT_MAKEUP', 'permanent-makeup-remover',       'Ремувер',           'Remover',                  TRUE);

-- ============================================================
-- 19. Барберінг  -> BARBERING  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-mens-cut',           'Чоловіча стрижка',                    'Men''s Haircut',                  TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-barber-cut',         'Барбер-стрижка',                      'Barber Cut',                      TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-clipper-single',     'Стрижка машинкою (одна насадка)',     'Clipper Cut (Single Guard)',      TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-clipper-multi',      'Стрижка машинкою (кілька насадок)',   'Clipper Cut (Multiple Guards)',   TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-scissor-cut',        'Стрижка ножицями',                    'Scissor Cut',                     TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-creative-cut',       'Креативна стрижка',                   'Creative Cut',                    TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-teen-cut',           'Стрижка підлітка',                    'Teen Cut',                        TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-kids-cut',           'Дитяча стрижка',                      'Kids'' Cut',                      TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-cut-correction',     'Корекція стрижки',                    'Cut Correction',                  TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BARBERING', 'barbering-long-mens-cut',      'Стрижка довгого чоловічого волосся',  'Long Men''s Hair Cut',            TRUE);

-- ============================================================
-- 20. Борода та вуса  -> BEARD_CARE  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-modeling',       'Моделювання бороди',         'Beard Modeling',        TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-trim',           'Стрижка бороди',             'Beard Trim',            TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-correction',     'Корекція бороди',            'Beard Correction',      TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-moustache-style','Оформлення вусів',           'Moustache Styling',     TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-shaving',        'Гоління бороди',             'Beard Shaving',         TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-grey-camouflage','Камуфлювання сивини бороди', 'Beard Grey Camouflage', TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-tint',           'Фарбування бороди',          'Beard Tint',            TRUE);

-- ============================================================
-- 21. Гоління  -> SHAVING  | bucket: Hair (0004)
-- ============================================================
INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'SHAVING', 'shaving-royal',   'Королівське гоління (небезпечна бритва)', 'Royal Shave (Straight Razor)', TRUE),
  ('11111111-0004-0000-0000-000000000000', 'SHAVING', 'shaving-classic', 'Класичне гоління',                        'Classic Shave',                TRUE),
  ('11111111-0004-0000-0000-000000000000', 'SHAVING', 'shaving-head',    'Гоління голови',                          'Head Shave',                   TRUE),
  ('11111111-0004-0000-0000-000000000000', 'SHAVING', 'shaving-contour', 'Контурне гоління',                        'Contour Shave',                TRUE),
  ('11111111-0004-0000-0000-000000000000', 'SHAVING', 'shaving-neck',    'Гоління шиї',                             'Neck Shave',                   TRUE);
