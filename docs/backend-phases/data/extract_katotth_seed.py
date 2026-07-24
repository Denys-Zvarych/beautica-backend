#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# Phase 10.2 — KATOTTH locality seed extractor (reproducibility artefact)
#
# Purpose
#   Deterministically project the official Ukrainian KATOTTH classifier into the
#   three Phase 10.1 taxonomy tables and emit V53__seed_locality_taxonomy.sql.
#
# Authoritative source
#   Кодифікатор адміністративно-територіальних одиниць та територій
#   територіальних громад (KATOTTH), Ministry of Communities and Territories
#   Development of Ukraine. Open government data — no attribution blocker.
#   Snapshot used: suprun/katottg.json @ archive/KATOTTG-2025-05-16
#     valid_on: 2025-05-16
#     source:   Наказ Мінрегіону від 02.07.2025 № 1065
#   This GitHub repo is a byte-faithful JSON re-publication of the official
#   data.gov.ua dataset (data.gov.ua blocks automated fetch with HTTP 403,
#   hence a verified mirror is used; codes/names are unmodified).
#
# Determinism
#   Output is sorted by KATOTTH code, so re-running on the same snapshot yields
#   a byte-identical .sql. The snapshot file is pinned by name; refreshing the
#   dataset is an explicit, reviewed action (new snapshot + new V<n> migration).
#
# Usage
#   python3 extract_katotth_seed.py <katottg-snapshot.json> > V53__seed_locality_taxonomy.sql
#
# KATOTTH categories (meta.category from the source file):
#   O  — Автономна Республіка Крим, області            (oblast-level)
#   K  — міста, що мають спеціальний статус             (special-status cities)
#   P  — райони в областях                              (oblast raions)      [skip]
#   H  — території територіальних громад                (hromadas)           [skip]
#   M  — міста                                          (cities)
#   T  — селища міського типу                           (urban-type villages)[skip]
#   C  — села                                           (villages)           [skip]
#   X  — селища                                          (settlements)        [skip]
#   B  — райони в містах                                 (urban districts)
#
# Taxonomy mapping:
#   oblasts        <- category O (oblasts) + category K Kyiv (special status)
#   cities         <- category M + Kyiv (the special-status city is its own city)
#   city_districts <- category B (urban districts), FK to parent city
#
# Occupied-territory data compliance (Phase 10.2) — DEFENCE IN DEPTH:
#   ABSOLUTE, NON-NEGOTIABLE POLICY: no data relating to the temporarily-
#   occupied territories (AR Crimea, Donetsk oblast, Luhansk oblast, the city
#   of Sevastopol) may exist anywhere in this repository.
#
#   The pinned snapshot docs/backend-phases/data/katottg-2025-05-16.json is
#   ALREADY PRE-FILTERED for occupied territories by
#   docs/backend-phases/data/filter_occupied_territories.py (all 3,742 occupied
#   admin_units records removed; occupied place-names scrubbed from free-text
#   meta schema-label strings). The original raw KATOTTH dump must never be
#   re-committed.
#
#   This extractor STILL filters by EXCLUDED_OBLAST_CODES, AND additionally
#   HARD-FAILS (non-zero exit) if any occupied code is encountered in the
#   input snapshot. Because the snapshot is pre-filtered, the hard-fail must
#   never trigger in normal operation — it exists to catch an accidental
#   re-introduction of unfiltered data (regression tripwire), so the seed can
#   never silently regain occupied-territory rows.
# ---------------------------------------------------------------------------

import json
import sys

# Occupied / non-serviceable oblast-equivalents — KATOTTH codes as they appear
# in the CURRENT official classifier (KOATUU-era digits differ; do not reuse).
EXCLUDED_OBLAST_CODES = {
    "UA01000000000013043",  # Автономна Республіка Крим (O)
    "UA14000000000091971",  # Донецька область         (O)
    "UA44000000000018893",  # Луганська область        (O)
    "UA85000000000065278",  # Севастополь              (K, special-status city)
}

# First four chars of every occupied oblast-equivalent KATOTTH code. Used by
# the hard-fail tripwire (an occupied record is identifiable by code prefix
# OR by an ancestry level pointing at an occupied code).
EXCLUDED_PREFIXES = tuple(sorted({c[:4] for c in EXCLUDED_OBLAST_CODES}))
_LEVEL_KEYS = ("level_1", "level_2", "level_3", "level_4", "level_5")


def assert_no_occupied_data(units: list) -> None:
    """Defence-in-depth tripwire (Phase 10.2).

    The snapshot is pre-filtered by filter_occupied_territories.py, so this
    must NEVER find anything. If it does, the input was not scrubbed: refuse
    to emit a seed (non-zero exit) rather than silently ship occupied data.
    """
    for e in units:
        code = e.get("id", "")
        if code in EXCLUDED_OBLAST_CODES or code[:4] in EXCLUDED_PREFIXES:
            raise SystemExit(
                f"COMPLIANCE FAILURE: occupied-territory record {code!r} present "
                f"in input snapshot. The snapshot must be pre-filtered with "
                f"filter_occupied_territories.py before extraction."
            )
        for key in _LEVEL_KEYS:
            anc = e.get(key)
            if anc and (anc in EXCLUDED_OBLAST_CODES
                        or str(anc)[:4] in EXCLUDED_PREFIXES):
                raise SystemExit(
                    f"COMPLIANCE FAILURE: record {code!r} descends from "
                    f"occupied ancestor {anc!r}. Pre-filter the snapshot with "
                    f"filter_occupied_territories.py before extraction."
                )

# Kyiv: special-status city that is simultaneously the oblast-equivalent parent
# of its own urban districts. It is inserted both as an oblast row and a city
# row (same KATOTTH code; the UNIQUE constraints are per-table, so this is
# valid and faithful — see the migration header).
KYIV_CODE = "UA80000000000093317"


# --- CMU Resolution No. 55 (2010) Ukrainian -> Latin transliteration ---------
# Official table used for Ukrainian travel documents / toponyms.
# Position-dependent rules:
#   Є, Ї, Й, Ю, Я        -> Ye/Yi/Y/Yu/Ya at the start of a word,
#                            ie/i/i/iu/ia elsewhere
#   зг                   -> zgh (to distinguish from ж = zh)
#   ь, ’ (apostrophe)    -> omitted

_SIMPLE = {
    "а": "a", "б": "b", "в": "v", "г": "h", "ґ": "g", "д": "d",
    "е": "e", "ж": "zh", "з": "z", "и": "y", "і": "i", "к": "k",
    "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r",
    "с": "s", "т": "t", "у": "u", "ф": "f", "х": "kh", "ц": "ts",
    "ч": "ch", "ш": "sh", "щ": "shch", "ь": "", "’": "", "'": "",
    "ʼ": "",
}
_POSITIONAL = {  # (word-initial, elsewhere)
    "є": ("ye", "ie"),
    "ї": ("yi", "i"),
    "й": ("y", "i"),
    "ю": ("yu", "iu"),
    "я": ("ya", "ia"),
}


def _translit_word(word: str) -> str:
    out = []
    i = 0
    while i < len(word):
        ch = word[i]
        low = ch.lower()
        is_upper = ch.isupper()

        # digraph зг -> zgh
        if low == "з" and i + 1 < len(word) and word[i + 1].lower() == "г":
            seg = "Zgh" if is_upper else "zgh"
            out.append(seg)
            i += 2
            continue

        at_word_start = (i == 0)
        if low in _POSITIONAL:
            seg = _POSITIONAL[low][0 if at_word_start else 1]
        elif low in _SIMPLE:
            seg = _SIMPLE[low]
        else:
            # spaces, hyphens, digits, etc. pass through unchanged
            out.append(ch)
            i += 1
            continue

        if is_upper and seg:
            seg = seg[0].upper() + seg[1:]
        out.append(seg)
        i += 1
    return "".join(out)


def transliterate(name: str) -> str:
    # Word-initial rules reset on every word boundary (space, hyphen, apostrophe
    # acting as a separator). Split on space and hyphen, keep separators.
    import re
    parts = re.split(r"([ \-])", name)
    return "".join(p if p in (" ", "-") else _translit_word(p) for p in parts)


def sql_str(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write("usage: extract_katotth_seed.py <snapshot.json>\n")
        return 2

    with open(sys.argv[1], encoding="utf-8") as fh:
        data = json.load(fh)

    units = data["admin_units"]
    valid_on = data.get("valid_on", "unknown")
    source = data.get("source", "unknown")

    # Defence-in-depth: refuse to emit a seed from an unfiltered snapshot.
    assert_no_occupied_data(units)

    # --- project the three taxonomy levels --------------------------------
    oblasts = []   # (code, name_uk)
    cities = []    # (oblast_code, city_code, name_uk)
    districts = []  # (city_code, district_code, name_uk)

    for e in units:
        code = e["id"]
        cat = e["category"]
        name = e["name"]
        l1 = e.get("level_1", code)

        if cat == "O":
            if code in EXCLUDED_OBLAST_CODES:
                continue
            oblasts.append((code, name))
        elif cat == "K":
            if code in EXCLUDED_OBLAST_CODES:
                continue
            # Kyiv (only serviced K): oblast-equivalent AND its own city.
            oblasts.append((code, name))
            cities.append((code, code, name))
        elif cat == "M":
            if l1 in EXCLUDED_OBLAST_CODES:
                continue
            cities.append((l1, code, name))
        elif cat == "B":
            if l1 in EXCLUDED_OBLAST_CODES:
                continue
            # Parent city code: level_4 for normal cities; for Kyiv every
            # ancestor level == the Kyiv K code.
            parent_city = e.get("level_4", l1)
            districts.append((parent_city, code, name))

    oblasts.sort(key=lambda r: r[0])
    cities.sort(key=lambda r: r[1])
    districts.sort(key=lambda r: r[1])

    # --- emit the migration ------------------------------------------------
    w = sys.stdout.write
    n_obl, n_city, n_dist = len(oblasts), len(cities), len(districts)
    dist_cities = len({d[0] for d in districts})

    w(f"""-- V53: KATOTTH locality seed (Phase 10.2)
--
-- Permanent, environment-agnostic reference-data seed for the Phase 10.1
-- taxonomy (oblasts / cities / city_districts, migration V52). Replays
-- byte-identically on every environment (single Neon dev DB, future dev
-- instance, future prod). Contains ONLY controlled KATOTTH reference data:
-- zero environment-specific fixes, zero references to legacy free-text
-- users/salons location columns (legacy cleanup is Phase 10.3, dev-only and
-- explicitly out of the permanent migration chain).
--
-- Source: official Ukrainian KATOTTH classifier
--   {source}
--   valid_on: {valid_on}
-- Generated by docs/backend-phases/data/extract_katotth_seed.py from the
-- pinned snapshot docs/backend-phases/data/katottg-2025-05-16.json. The
-- authoritative artefact is THIS .sql file; the script + snapshot exist for
-- audit/reproducibility (Phase 10.2 Step 0).
--
-- Occupied / non-serviceable territory filter (Phase 10.2 Step 1) — applied
-- as a DATA filter by oblast KATOTTH code, never a schema change. Excluded
-- (and all subordinate cities/districts):
--   AR Crimea     UA01000000000013043
--   Donetsk obl.  UA14000000000091971
--   Luhansk obl.  UA44000000000018893
--   Sevastopol    UA85000000000065278
-- Re-including a liberated territory later is a single forward-only seed
-- migration adding the oblast row(s) + its cities/districts. The per-table
-- UNIQUE katotth_code constraints from V52 keep that conflict-free; this
-- seed therefore uses NO "ON CONFLICT" (the seed must be clean, not masked).
--
-- Kyiv is a special-status city (KATOTTH category K): it is BOTH an
-- oblast-equivalent and a city. It is inserted as one oblasts row and one
-- cities row sharing KATOTTH code {KYIV_CODE}. The UNIQUE constraint on
-- katotth_code is per-table (uq_oblasts_katotth_code / uq_cities_katotth_code),
-- so the same code in both tables is valid and faithful to the classifier.
--
-- Row-count sanity bounds (authoritative, from the pinned snapshot):
--   oblasts        = {n_obl}   (22 category-O oblasts + Kyiv special-status city;
--                          Crimea/Donetsk/Luhansk oblasts + Sevastopol excluded)
--   cities         = {n_city}  (355 category-M cities + Kyiv)
--   city_districts = {n_dist}   category-B urban districts across {dist_cities} cities
--
-- Category-B urban districts per city (authoritative — from official KATOTTH;
-- supersedes the earlier hand-estimated 58/9 figure in
-- docs/backend-phases/data/katotth-seed-sourcing.md, which has been corrected):
--   Kyiv 10, Kharkiv 9, Dnipro 8, Zaporizhzhia 7, Kryvyi Rih 7, Lviv 6,
--   Mykolaiv 4, Odesa 4, Kamianske 3, Poltava 3, Kherson 3, Zhytomyr 2,
--   Kropyvnytskyi 2, Kremenchuk 2, Sumy 2, Cherkasy 2, Chernihiv 2.
--   (Vinnytsia has NO category-B districts in the official classifier.)
--
-- Insert order: oblasts -> cities (oblast_id resolved via subquery on
-- oblasts.katotth_code) -> city_districts (city_id resolved via subquery on
-- cities.katotth_code). No hardcoded UUIDs — gen_random_uuid() fills id.

-- ============================================================================
-- OBLASTS ({n_obl})
-- ============================================================================
""")

    for code, name in oblasts:
        w(
            "INSERT INTO oblasts (katotth_code, name_uk, name_en) VALUES "
            f"({sql_str(code)}, {sql_str(name)}, {sql_str(transliterate(name))});\n"
        )

    w(f"""
-- ============================================================================
-- CITIES ({n_city}) — oblast_id resolved by oblast KATOTTH code
-- ============================================================================
""")
    for oblast_code, city_code, name in cities:
        w(
            "INSERT INTO cities (oblast_id, katotth_code, name_uk, name_en)\n"
            f"SELECT o.id, {sql_str(city_code)}, {sql_str(name)}, {sql_str(transliterate(name))}\n"
            f"FROM oblasts o WHERE o.katotth_code = {sql_str(oblast_code)};\n"
        )

    w(f"""
-- ============================================================================
-- CITY_DISTRICTS ({n_dist}) — category B; city_id resolved by city KATOTTH code
-- ============================================================================
""")
    for city_code, district_code, name in districts:
        w(
            "INSERT INTO city_districts (city_id, katotth_code, name_uk, name_en)\n"
            f"SELECT c.id, {sql_str(district_code)}, {sql_str(name)}, {sql_str(transliterate(name))}\n"
            f"FROM cities c WHERE c.katotth_code = {sql_str(city_code)};\n"
        )

    sys.stderr.write(
        f"oblasts={n_obl} cities={n_city} city_districts={n_dist} "
        f"(district-bearing cities={dist_cities})\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
