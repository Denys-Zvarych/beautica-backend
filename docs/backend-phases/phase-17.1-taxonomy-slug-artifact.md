# Phase 17.1 — Taxonomy Slug Artifact (source of truth for V74 / V75) ✅ COMPLETE

**Status: COMPLETE — verified by a real Flyway run (see § Verification).**

## Status

- Artifact / source-of-truth doc only — **no code artifact**. The 21-category +
  140-leaf slug/name table here is the contract pinned by V74/V75 and asserted by
  the migration + seed-contract tests.
- QA score: n/a (reference data — its contract is enforced by the tests in 17.2 / 17.3) | Completed: 2026-06-05 | Branch: `phases-16.x`
- `TaxonomySeedContractIT` (this phase's test net) directly cites this artifact as the expected-value source.

This is the reviewable source of truth for the 21-category taxonomy seeded by
`V74` (platform categories) and `V75` (service-type leaves). Every slug below is
**globally unique** and satisfies both the entity `@Pattern`
(`^[a-z0-9]+(?:-[a-z0-9]+)*$`) and the stricter DB CHECK
(`^[a-z0-9][a-z0-9\-]*[a-z0-9]$`).

## 21 platform categories (V74)

`name` = uppercase picker slug (`platform_categories.name`, regex
`^[A-Z][A-Z0-9_]*$`). `display_name` = Ukrainian label. Bucket = the coarse
`service_categories` row each category's leaves are filed under.

| # | name (slug) | display_name (UA) | name_en | coarse bucket | legacy origin |
|---|---|---|---|---|---|
| 1 | `HAIRDRESSING` | Перукарські послуги | Hairdressing | Волосся | rename ← `HAIRCUT` |
| 2 | `HAIR_COLORING` | Фарбування волосся | Hair Coloring | Волосся | new |
| 3 | `HAIR_TREATMENT` | Відновлення волосся | Hair Treatment | Волосся | new |
| 4 | `HAIR_EXTENSIONS` | Нарощування волосся | Hair Extensions | Волосся | new |
| 5 | `TRICHOLOGY` | Трихологія | Trichology | Волосся | new |
| 6 | `NAIL_SERVICE` | Нігтьовий сервіс | Nail Service | Нігті | rename ← `MANICURE` |
| 7 | `PODOLOGY` | Подологія | Podology | Нігті | new |
| 8 | `BROWS` | Оформлення брів | Brow Styling | Брови | kept |
| 9 | `LASH_LAMINATION` | Ламінування вій | Lash Lamination | Вії | new |
| 10 | `LASH_EXTENSIONS` | Нарощення вій | Lash Extensions | Вії | rename ← `EYELASH` |
| 11 | `MAKEUP` | Макіяж | Makeup | Макіяж | kept |
| 12 | `COSMETOLOGY` | Косметологія | Cosmetology | Обличчя / Шкіра | new |
| 13 | `HARDWARE_COSMETOLOGY` | Апаратна косметологія | Hardware Cosmetology | Обличчя / Шкіра | new |
| 14 | `INJECTION_COSMETOLOGY` | Ін'єкційна косметологія | Injection Cosmetology | Обличчя / Шкіра | new |
| 15 | `AESTHETIC_COSMETOLOGY` | Естетична косметологія | Aesthetic Cosmetology | Обличчя / Шкіра | new |
| 16 | `LASER_COSMETOLOGY` | Лазерна косметологія | Laser Cosmetology | Обличчя / Шкіра | new |
| 17 | `HAIR_REMOVAL` | Депіляція та епіляція | Hair Removal | Тіло | new |
| 18 | `PERMANENT_MAKEUP` | Перманентний макіяж | Permanent Makeup | Макіяж | new |
| 19 | `BARBERING` | Барберінг | Barbering | Волосся | new |
| 20 | `BEARD_CARE` | Борода та вуса | Beard & Moustache | Волосся | new |
| 21 | `SHAVING` | Гоління | Shaving | Волосся | new |

### Legacy slug disposition (V74)

| Legacy `name` | Action | Result |
|---|---|---|
| `HAIRCUT` | rename | → `HAIRDRESSING` (1:1, ON UPDATE CASCADE) |
| `MANICURE` | rename | → `NAIL_SERVICE` (1:1) |
| `EYELASH` | rename | → `LASH_EXTENSIONS` (1:1) |
| `BROWS` | keep | display_name → "Оформлення брів" |
| `MAKEUP` | keep | display_name → "Макіяж" |
| `PEDICURE` | soft-disable | `active = FALSE` (folded into NAIL_SERVICE leaves) |
| `HAIR` | soft-disable | `active = FALSE` (split across colouring/treatment/etc.) |
| `BODY` | soft-disable | `active = FALSE` (superseded by HAIR_REMOVAL) |
| `FACE` | soft-disable | `active = FALSE` (split across the 5 cosmetology categories) |
| `OTHER` | soft-disable | already `active = FALSE` (V66) — no-op |

Soft-disable, not DELETE: the row is referenced by the `UNIQUE(name)`
self-service-workflow guard and may be a recorded category on legacy
`service_definitions` rows. Disabling drops it from the picker and the
validate-category gate without breaking FKs.

## Bucket distribution (21 → 8)

| Coarse bucket (`service_categories`) | Platform categories filed under it | Leaf count |
|---|---|---|
| Нігті (`0001`) | NAIL_SERVICE, PODOLOGY | 17 |
| Вії (`0002`) | LASH_LAMINATION, LASH_EXTENSIONS | 13 |
| Брови (`0003`) | BROWS | 8 |
| Волосся (`0004`) | HAIRDRESSING, HAIR_COLORING, HAIR_TREATMENT, HAIR_EXTENSIONS, TRICHOLOGY, BARBERING, BEARD_CARE, SHAVING | 56 |
| Обличчя / Шкіра (`0005`) | COSMETOLOGY, HARDWARE_COSMETOLOGY, INJECTION_COSMETOLOGY, AESTHETIC_COSMETOLOGY, LASER_COSMETOLOGY | 26 |
| Тіло (`0006`) | HAIR_REMOVAL | 6 |
| Макіяж (`0007`) | MAKEUP, PERMANENT_MAKEUP | 14 |
| Інше (`0008`) | — (none) | 0 |
| **Total** | **21 categories** | **140** |

## 140 service-type leaves (V75)

Slugs are **category-prefixed** so cross-category duplicate display names
(`Корекція`, `Ботокс`, `Чоловіча стрижка`, `Дитяча стрижка`, `Ламінування`,
`Гоління …`) yield globally-unique slugs. Latin-name items map directly
(`hair-coloring-airtouch`, `-balayage`, `-ombre`, `-shatush`, `makeup-nude`,
`hardware-cosmetology-hydrafacial`, `hardware-cosmetology-rf-lift`).

### 1. HAIRDRESSING — Перукарські послуги (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Жіноча стрижка | Women's Haircut | `hairdressing-womens-cut` |
| Чоловіча стрижка | Men's Haircut | `hairdressing-mens-cut` |
| Дитяча стрижка | Kids' Haircut | `hairdressing-kids-cut` |
| Підрівнювання кінчиків | Ends Trim | `hairdressing-trim` |
| Стрижка чубчика | Fringe Cut | `hairdressing-fringe-cut` |
| Вечірня зачіска | Evening Updo | `hairdressing-evening-updo` |
| Весільна зачіска | Bridal Updo | `hairdressing-bridal-updo` |
| Локони | Curls | `hairdressing-curls` |
| Голлівудська хвиля | Hollywood Wave | `hairdressing-hollywood-wave` |
| Укладання феном | Blow-Dry Styling | `hairdressing-blow-dry` |
| Плетіння кіс | Braiding | `hairdressing-braiding` |

### 2. HAIR_COLORING — Фарбування волосся (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Однотонне фарбування | Single-Tone Coloring | `hair-coloring-single-tone` |
| Тонування | Toning | `hair-coloring-toning` |
| Airtouch | Airtouch | `hair-coloring-airtouch` |
| Balayage | Balayage | `hair-coloring-balayage` |
| Ombre | Ombre | `hair-coloring-ombre` |
| Shatush | Shatush | `hair-coloring-shatush` |
| Мелірування | Highlights | `hair-coloring-highlights` |
| Блонд | Blonding | `hair-coloring-blonding` |
| Вихід з чорного | Black Color Removal | `hair-coloring-black-removal` |

### 3. HAIR_TREATMENT — Відновлення волосся (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Кератин | Keratin Treatment | `hair-treatment-keratin` |
| Ботокс для волосся | Hair Botox | `hair-treatment-botox` |
| Нанопластика | Nanoplasty | `hair-treatment-nanoplasty` |
| Холодне відновлення | Cold Recovery | `hair-treatment-cold-recovery` |
| Щастя для волосся | Hair Happiness | `hair-treatment-happiness` |
| Ламінування | Hair Lamination | `hair-treatment-lamination` |
| Полірування волосся | Hair Polishing | `hair-treatment-polishing` |

### 4. HAIR_EXTENSIONS — Нарощування волосся (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Капсульне | Capsule Extensions | `hair-extensions-capsule` |
| Стрічкове | Tape-In Extensions | `hair-extensions-tape` |
| Корекція | Extensions Correction | `hair-extensions-correction` |
| Зняття | Extensions Removal | `hair-extensions-removal` |

### 5. TRICHOLOGY — Трихологія (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Консультація трихолога | Trichologist Consultation | `trichology-consultation` |
| Діагностика шкіри голови | Scalp Diagnostics | `trichology-scalp-diagnostics` |
| Пілінг шкіри голови | Scalp Peeling | `trichology-scalp-peeling` |

### 6. NAIL_SERVICE — Нігтьовий сервіс (bucket: Нігті)
| name_uk | name_en | slug |
|---|---|---|
| Манікюр | Manicure | `nail-service-manicure` |
| Манікюр+гель лак | Manicure + Gel Polish | `nail-service-manicure-gel-polish` |
| Манікюр+укріплення | Manicure + Strengthening | `nail-service-manicure-strengthening` |
| Манікюр+френч | Manicure + French | `nail-service-manicure-french` |
| Зняття без покриття | Removal Without Coating | `nail-service-removal-no-coating` |
| Зняття іншого майстра | Removal of Other Master's Work | `nail-service-removal-other-master` |
| Реставрація укріплення+покриття | Strengthening Restore + Coating | `nail-service-restore-strengthen-coat` |
| Педикюр/пальчики | Pedicure (Toes) | `nail-service-pedicure-toes` |
| Педикюр повний | Full Pedicure | `nail-service-pedicure-full` |
| Педикюр +покриття | Pedicure + Coating | `nail-service-pedicure-coating` |
| Педикюр повний +покриття | Full Pedicure + Coating | `nail-service-pedicure-full-coating` |
| Нарощення нігтів | Nail Extension | `nail-service-extension` |

### 7. PODOLOGY — Подологія (bucket: Нігті)
| name_uk | name_en | slug |
|---|---|---|
| Обробка врослого нігтя | Ingrown Nail Treatment | `podology-ingrown-nail` |
| Мозолі | Calluses | `podology-calluses` |
| Тріщини | Cracks | `podology-cracks` |
| Протезування нігтя | Nail Prosthetics | `podology-nail-prosthetics` |
| Консультація подолога | Podologist Consultation | `podology-consultation` |

### 8. BROWS — Оформлення брів (bucket: Брови)
| name_uk | name_en | slug |
|---|---|---|
| Корекція | Brow Correction | `brows-correction` |
| Фарбування фарба | Brow Tint (Dye) | `brows-tint-dye` |
| Фарбування хна | Brow Tint (Henna) | `brows-tint-henna` |
| Ламінування | Brow Lamination | `brows-lamination` |
| Архітектура брів | Brow Architecture | `brows-architecture` |
| Освітлення брів | Brow Lightening | `brows-lightening` |
| Довготривала укладка брів | Long-Lasting Brow Styling | `brows-long-lasting-style` |
| Чоловіча корекція | Men's Brow Correction | `brows-mens-correction` |

### 9. LASH_LAMINATION — Ламінування вій (bucket: Вії)
| name_uk | name_en | slug |
|---|---|---|
| Ламінування вій | Lash Lamination | `lash-lamination` |
| Ботокс вій | Lash Botox | `lash-lamination-botox` |
| Фарбування вій | Lash Tint | `lash-lamination-tint` |

### 10. LASH_EXTENSIONS — Нарощення вій (bucket: Вії)
| name_uk | name_en | slug |
|---|---|---|
| Кутики | Inner Corners | `lash-extensions-inner-corners` |
| Класичне нарощення | Classic Extensions | `lash-extensions-classic` |
| 2д обʼєм | 2D Volume | `lash-extensions-2d` |
| 3д обʼєм | 3D Volume | `lash-extensions-3d` |
| 4д обʼєм | 4D Volume | `lash-extensions-4d` |
| Мега обʼєм | Mega Volume | `lash-extensions-mega-volume` |
| Експрес нарощення | Express Extensions | `lash-extensions-express` |
| Мокрий ефект | Wet-Look Effect | `lash-extensions-wet-look` |
| Корекція нарощених вій | Lash Extensions Correction | `lash-extensions-correction` |
| Зняття нарощених вій | Lash Extensions Removal | `lash-extensions-removal` |

### 11. MAKEUP — Макіяж (bucket: Макіяж)
| name_uk | name_en | slug |
|---|---|---|
| Денний | Day Makeup | `makeup-day` |
| Вечірній | Evening Makeup | `makeup-evening` |
| Весільний | Bridal Makeup | `makeup-bridal` |
| Nude | Nude Makeup | `makeup-nude` |
| Ліфтинг-макіяж | Lifting Makeup | `makeup-lifting` |
| Фотосесія | Photoshoot Makeup | `makeup-photoshoot` |
| Макіяж для події | Event Makeup | `makeup-event` |
| Чоловічий макіяж | Men's Makeup | `makeup-mens` |

### 12. COSMETOLOGY — Косметологія (bucket: Обличчя / Шкіра)
| name_uk | name_en | slug |
|---|---|---|
| Чистка обличчя | Facial Cleanse | `cosmetology-facial-cleanse` |
| Пілінги | Peels | `cosmetology-peels` |
| Маски | Masks | `cosmetology-masks` |
| Доглядові програми | Care Programs | `cosmetology-care-programs` |
| Масаж обличчя | Face Massage | `cosmetology-face-massage` |

### 13. HARDWARE_COSMETOLOGY — Апаратна косметологія (bucket: Обличчя / Шкіра)
| name_uk | name_en | slug |
|---|---|---|
| RF-ліфтинг | RF Lifting | `hardware-cosmetology-rf-lift` |
| Мікроструми | Microcurrent | `hardware-cosmetology-microcurrent` |
| Ультразвук | Ultrasound | `hardware-cosmetology-ultrasound` |
| Карбоксітерапія | Carboxytherapy | `hardware-cosmetology-carboxy` |
| Hydrafacial | Hydrafacial | `hardware-cosmetology-hydrafacial` |

### 14. INJECTION_COSMETOLOGY — Ін'єкційна косметологія (bucket: Обличчя / Шкіра)
| name_uk | name_en | slug |
|---|---|---|
| Ботокс | Botox | `injection-botox` |
| Біоревіталізація | Biorevitalization | `injection-biorevitalization` |
| Мезотерапія | Mesotherapy | `injection-mesotherapy` |
| Контурна пластика | Contour Plastic | `injection-contouring` |
| Збільшення губ | Lip Augmentation | `injection-lip-augmentation` |
| Ліполітики | Lipolytics | `injection-lipolytics` |

### 15. AESTHETIC_COSMETOLOGY — Естетична косметологія (bucket: Обличчя / Шкіра)
| name_uk | name_en | slug |
|---|---|---|
| Лікування акне | Acne Treatment | `aesthetic-acne-treatment` |
| Пігментація | Pigmentation | `aesthetic-pigmentation` |
| Купероз | Couperose | `aesthetic-couperose` |

### 16. LASER_COSMETOLOGY — Лазерна косметологія (bucket: Обличчя / Шкіра)
| name_uk | name_en | slug |
|---|---|---|
| Лазерна епіляція | Laser Hair Removal | `laser-hair-removal` |
| Фотоепіляція | Photoepilation | `laser-photoepilation` |
| Лазерне омолодження | Laser Rejuvenation | `laser-rejuvenation` |
| Видалення судин | Vascular Removal | `laser-vascular-removal` |
| Видалення пігментації | Pigmentation Removal | `laser-pigmentation-removal` |
| Видалення тату | Tattoo Removal | `laser-tattoo-removal` |
| Видалення перманенту | Permanent Makeup Removal | `laser-permanent-removal` |

### 17. HAIR_REMOVAL — Депіляція та епіляція (bucket: Тіло)
"Воскова депіляція" zones flattened to 5 distinct types + sugaring.
| name_uk | name_en | slug |
|---|---|---|
| Воскова депіляція — ноги | Waxing — Legs | `waxing-legs` |
| Воскова депіляція — руки | Waxing — Arms | `waxing-arms` |
| Воскова депіляція — пахви | Waxing — Underarms | `waxing-underarms` |
| Воскова депіляція — бікіні | Waxing — Bikini | `waxing-bikini` |
| Воскова депіляція — обличчя | Waxing — Face | `waxing-face` |
| Шугаринг | Sugaring | `sugaring` |

### 18. PERMANENT_MAKEUP — Перманентний макіяж (bucket: Макіяж)
| name_uk | name_en | slug |
|---|---|---|
| Брови | Permanent Brows | `permanent-makeup-brows` |
| Губи | Permanent Lips | `permanent-makeup-lips` |
| Повіки | Permanent Eyelids | `permanent-makeup-eyelids` |
| Корекція | Permanent Makeup Correction | `permanent-makeup-correction` |
| Видалення лазером | Laser Removal | `permanent-makeup-laser-removal` |
| Ремувер | Remover | `permanent-makeup-remover` |

### 19. BARBERING — Барберінг (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Чоловіча стрижка | Men's Haircut | `barbering-mens-cut` |
| Барбер-стрижка | Barber Cut | `barbering-barber-cut` |
| Стрижка машинкою (одна насадка) | Clipper Cut (Single Guard) | `barbering-clipper-single` |
| Стрижка машинкою (кілька насадок) | Clipper Cut (Multiple Guards) | `barbering-clipper-multi` |
| Стрижка ножицями | Scissor Cut | `barbering-scissor-cut` |
| Креативна стрижка | Creative Cut | `barbering-creative-cut` |
| Стрижка підлітка | Teen Cut | `barbering-teen-cut` |
| Дитяча стрижка | Kids' Cut | `barbering-kids-cut` |
| Корекція стрижки | Cut Correction | `barbering-cut-correction` |
| Стрижка довгого чоловічого волосся | Long Men's Hair Cut | `barbering-long-mens-cut` |

### 20. BEARD_CARE — Борода та вуса (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Моделювання бороди | Beard Modeling | `beard-modeling` |
| Стрижка бороди | Beard Trim | `beard-trim` |
| Корекція бороди | Beard Correction | `beard-correction` |
| Оформлення вусів | Moustache Styling | `beard-moustache-style` |
| Гоління бороди | Beard Shaving | `beard-shaving` |
| Камуфлювання сивини бороди | Beard Grey Camouflage | `beard-grey-camouflage` |
| Фарбування бороди | Beard Tint | `beard-tint` |

### 21. SHAVING — Гоління (bucket: Волосся)
| name_uk | name_en | slug |
|---|---|---|
| Королівське гоління (небезпечна бритва) | Royal Shave (Straight Razor) | `shaving-royal` |
| Класичне гоління | Classic Shave | `shaving-classic` |
| Гоління голови | Head Shave | `shaving-head` |
| Контурне гоління | Contour Shave | `shaving-contour` |
| Гоління шиї | Neck Shave | `shaving-neck` |

## Verification (machine-checked against the local DB)

Applied via a real Flyway run (`bootRun --spring.profiles.active=local`):
`Successfully applied 2 migrations to schema "public", now at version v75`.

| Assertion | Result |
|---|---|
| platform_categories active | **21** (26 total incl. 5 soft-disabled) |
| service_types total | **140** |
| Duplicate slugs | **0** |
| Slugs violating DB CHECK `^[a-z0-9][a-z0-9\-]*[a-z0-9]$` | **0** |
| `name_en` NULL or empty | **0** |
| `name_uk` NULL or empty | **0** |
| `category_id` NULL | **0** |
| `platform_category_name` NULL | **0** |
| `platform_category_name` not resolvable to a `platform_categories.name` | **0** |
| `platform_category_name` pointing to an inactive platform category | **0** |
| Every active platform category has ≥1 leaf | **21 / 21** |
| Idempotency (re-apply V74+V75) | identical counts (140 / 21 / 26) |
