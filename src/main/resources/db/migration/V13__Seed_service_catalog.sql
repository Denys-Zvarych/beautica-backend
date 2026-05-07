-- Categories
INSERT INTO service_categories (id, name_uk, name_en, sort_order) VALUES
  ('11111111-0001-0000-0000-000000000000', 'Нігті',          'Nails',       1),
  ('11111111-0002-0000-0000-000000000000', 'Вії',            'Eyelashes',   2),
  ('11111111-0003-0000-0000-000000000000', 'Брови',          'Brows',       3),
  ('11111111-0004-0000-0000-000000000000', 'Волосся',        'Hair',        4),
  ('11111111-0005-0000-0000-000000000000', 'Обличчя / Шкіра','Face / Skin', 5),
  ('11111111-0006-0000-0000-000000000000', 'Тіло',           'Body',        6),
  ('11111111-0007-0000-0000-000000000000', 'Макіяж',         'Makeup',      7),
  ('11111111-0008-0000-0000-000000000000', 'Інше',           'Other',       8);

-- Nails
INSERT INTO service_types (id, category_id, name_uk, name_en, slug) VALUES
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Манікюр класичний',         'Classic Manicure',       'manicure-classic'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Манікюр апаратний',         'Hardware Manicure',      'manicure-hardware'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Покриття гель-лак',         'Gel Polish',             'gel-polish'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Знімання покриття',         'Polish Removal',         'polish-removal'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Педикюр класичний',         'Classic Pedicure',       'pedicure-classic'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Педикюр апаратний',         'Hardware Pedicure',      'pedicure-hardware'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Нарощування нігтів (акрил)','Acrylic Nail Extension',  'nail-extension-acrylic'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Нарощування нігтів (гель)', 'Gel Nail Extension',     'nail-extension-gel'),
  (gen_random_uuid(), '11111111-0001-0000-0000-000000000000', 'Дизайн нігтів',             'Nail Art',               'nail-art');

-- Eyelashes
INSERT INTO service_types (id, category_id, name_uk, name_en, slug) VALUES
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Нарощування вій (класика)', 'Classic Lash Extension', 'lash-classic'),
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Нарощування вій 2D',        '2D Lash Extension',      'lash-2d'),
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Нарощування вій 3D',        '3D Lash Extension',      'lash-3d'),
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Нарощування вій обʼєм (4D-6D)','Volume Lash (4D-6D)', 'lash-volume'),
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Нарощування вій мега-обʼєм (7D+)','Mega Volume Lash', 'lash-mega-volume'),
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Ламінування вій',           'Lash Lamination',        'lash-lamination'),
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Підняття вій (ліфтинг)',    'Lash Lift',              'lash-lift'),
  (gen_random_uuid(), '11111111-0002-0000-0000-000000000000', 'Видалення нарощених вій',   'Lash Removal',           'lash-removal');

-- Brows
INSERT INTO service_types (id, category_id, name_uk, name_en, slug) VALUES
  (gen_random_uuid(), '11111111-0003-0000-0000-000000000000', 'Оформлення брів',           'Brow Shaping',           'brow-shaping'),
  (gen_random_uuid(), '11111111-0003-0000-0000-000000000000', 'Фарбування брів',           'Brow Tinting',           'brow-tinting'),
  (gen_random_uuid(), '11111111-0003-0000-0000-000000000000', 'Ламінування брів',          'Brow Lamination',        'brow-lamination'),
  (gen_random_uuid(), '11111111-0003-0000-0000-000000000000', 'Мікроблейдинг',             'Microblading',           'brow-microblading'),
  (gen_random_uuid(), '11111111-0003-0000-0000-000000000000', 'Пудрові брови (перманент)', 'Powder Brows',           'brow-powder-perm'),
  (gen_random_uuid(), '11111111-0003-0000-0000-000000000000', 'Архітектура брів',          'Brow Architecture',      'brow-architecture');

-- Hair
INSERT INTO service_types (id, category_id, name_uk, name_en, slug) VALUES
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Жіноча стрижка',            'Womens Haircut',         'haircut-women'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Чоловіча стрижка',          'Mens Haircut',           'haircut-men'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Дитяча стрижка',            'Kids Haircut',           'haircut-kids'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Фарбування волосся',        'Hair Coloring',          'hair-coloring'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Мелірування',               'Highlights',             'hair-highlights'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Балаяж',                    'Balayage',               'hair-balayage'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Кератинове випрямлення',    'Keratin Treatment',      'hair-keratin'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Ботекс волосся',            'Hair Botox',             'hair-botox'),
  (gen_random_uuid(), '11111111-0004-0000-0000-000000000000', 'Укладання',                 'Blowout / Styling',      'hair-styling');

-- Face / Skin
INSERT INTO service_types (id, category_id, name_uk, name_en, slug) VALUES
  (gen_random_uuid(), '11111111-0005-0000-0000-000000000000', 'Класичне чищення обличчя',  'Classic Facial Cleanse', 'facial-classic'),
  (gen_random_uuid(), '11111111-0005-0000-0000-000000000000', 'Апаратне чищення обличчя',  'Hardware Facial',        'facial-hardware'),
  (gen_random_uuid(), '11111111-0005-0000-0000-000000000000', 'Хімічний пілінг',           'Chemical Peel',          'facial-peel'),
  (gen_random_uuid(), '11111111-0005-0000-0000-000000000000', 'Мезотерапія',               'Mesotherapy',            'facial-meso'),
  (gen_random_uuid(), '11111111-0005-0000-0000-000000000000', 'Масаж обличчя',             'Face Massage',           'facial-massage'),
  (gen_random_uuid(), '11111111-0005-0000-0000-000000000000', 'RF-ліфтинг',                'RF Lifting',             'facial-rf-lift');

-- Body
INSERT INTO service_types (id, category_id, name_uk, name_en, slug) VALUES
  (gen_random_uuid(), '11111111-0006-0000-0000-000000000000', 'Депіляція воском (ноги)',   'Leg Waxing',             'wax-legs'),
  (gen_random_uuid(), '11111111-0006-0000-0000-000000000000', 'Депіляція воском (бікіні)', 'Bikini Waxing',          'wax-bikini'),
  (gen_random_uuid(), '11111111-0006-0000-0000-000000000000', 'Шугарінг',                  'Sugaring',               'sugaring'),
  (gen_random_uuid(), '11111111-0006-0000-0000-000000000000', 'Масаж загальний',           'Full Body Massage',      'massage-general'),
  (gen_random_uuid(), '11111111-0006-0000-0000-000000000000', 'Антицелюлітний масаж',      'Anti-Cellulite Massage', 'massage-anticellulite'),
  (gen_random_uuid(), '11111111-0006-0000-0000-000000000000', 'Обгортання',                'Body Wrap',              'body-wrap');

-- Makeup
INSERT INTO service_types (id, category_id, name_uk, name_en, slug) VALUES
  (gen_random_uuid(), '11111111-0007-0000-0000-000000000000', 'Денний макіяж',             'Day Makeup',             'makeup-day'),
  (gen_random_uuid(), '11111111-0007-0000-0000-000000000000', 'Вечірній макіяж',           'Evening Makeup',         'makeup-evening'),
  (gen_random_uuid(), '11111111-0007-0000-0000-000000000000', 'Весільний макіяж',          'Bridal Makeup',          'makeup-bridal'),
  (gen_random_uuid(), '11111111-0007-0000-0000-000000000000', 'Перманентний макіяж (губи)','Lip Permanent Makeup',   'perm-makeup-lips'),
  (gen_random_uuid(), '11111111-0007-0000-0000-000000000000', 'Перманентний макіяж (стрілки)','Eyeliner Perm Makeup','perm-makeup-liner');
