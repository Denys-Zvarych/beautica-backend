#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# Phase 10.2 — Occupied-territory snapshot scrubber (one-off, reusable)
#
# WHY THIS EXISTS
#   Absolute, non-negotiable data-compliance policy: the repository must not
#   carry ANY data relating to the temporarily-occupied territories — the
#   Autonomous Republic of Crimea, Donetsk oblast, Luhansk oblast, and the
#   city of Sevastopol. The originally committed raw KATOTTH snapshot shipped
#   3,742 occupied admin_units records verbatim plus occupied place-names in
#   free-text `meta` schema-label strings. This script rewrites the snapshot
#   in place so the on-disk artefact is itself clean (defence in depth: the
#   extractor ALSO filters, but the source-of-truth file must not contain the
#   data at all).
#
# WHAT IT DOES (idempotent)
#   1. Drops every `admin_units` record that belongs to any of the four
#      occupied oblast-equivalents, by BOTH:
#        - its own KATOTTH code prefix (UA01 / UA14 / UA44 / UA85), AND
#        - ancestry: any of level_1..level_5 referencing an occupied code
#          (or carrying an occupied prefix).
#   2. Scrubs occupied Ukrainian place-names out of free-text `meta`
#      schema-label strings (the `level` / `category` descriptive labels),
#      replacing them with neutral wording. `valid_on`, `source`, the schema
#      URL, and the `keys` map are preserved byte-for-byte.
#   3. Leaves the JSON valid and consumable by extract_katotth_seed.py.
#
# USAGE
#   python3 filter_occupied_territories.py <snapshot.json>     # rewrite in place
#   python3 filter_occupied_territories.py <snapshot.json> --check   # report only
#
# Re-running on an already-clean file is a no-op (0 dropped, 0 scrubbed) and
# produces a byte-identical output, so it is safe in CI as a compliance gate.
# ---------------------------------------------------------------------------

import json
import sys

# Occupied / non-serviceable oblast-equivalents — KATOTTH codes as they appear
# in the CURRENT official classifier. Keep in lock-step with
# extract_katotth_seed.py:EXCLUDED_OBLAST_CODES.
EXCLUDED_OBLAST_CODES = {
    "UA01000000000013043",  # Автономна Республіка Крим (O)
    "UA14000000000091971",  # Донецька область         (O)
    "UA44000000000018893",  # Луганська область        (O)
    "UA85000000000065278",  # Севастополь              (K, special-status city)
}

# First four characters of every occupied oblast-equivalent KATOTTH code.
EXCLUDED_PREFIXES = tuple(sorted({c[:4] for c in EXCLUDED_OBLAST_CODES}))

# Ancestry keys that may point at an occupied parent.
_LEVEL_KEYS = ("level_1", "level_2", "level_3", "level_4", "level_5")

# Free-text meta schema-label substitutions. The official labels embed the
# occupied place-names as descriptive prose; the policy covers descriptive
# strings too. Replacements keep the schema meaning intact without naming the
# occupied territories. Order matters: longer phrases first.
#
# NOTE: only the four occupied-territory phrases below are touched. Innocent
# Ukrainian place-names that merely *contain* the substrings "Крим" / "Луганськ"
# / "Донецьк" (e.g. the villages "Кримне", "Луганське", "Донецьке" in serviced
# oblasts) are NOT occupied-territory data and are deliberately left untouched —
# matching is on whole occupied-territory clauses, never bare substrings.
_META_REPLACEMENTS = (
    ("райони в областях та Автономній Республіці Крим", "райони в областях"),
    ("район в Автономній Республіці Крим, області", "район в області"),
    ("Автономній Республіці Крим, області", "області"),
    ("Автономна Республіка Крим, області", "області"),
    ("Автономна Республіка Крим, область, місто, що має спеціальний статус",
     "область, місто, що має спеціальний статус"),
    ("Автономної Республіки Крим", ""),
    ("Автономна Республіка Крим", ""),
)


def _is_occupied(record: dict) -> bool:
    """True if the record is, or descends from, an occupied oblast-equivalent."""
    code = record.get("id", "")
    if code in EXCLUDED_OBLAST_CODES or code[:4] in EXCLUDED_PREFIXES:
        return True
    for key in _LEVEL_KEYS:
        anc = record.get(key)
        if anc and (anc in EXCLUDED_OBLAST_CODES or str(anc)[:4] in EXCLUDED_PREFIXES):
            return True
    return False


def _scrub_meta(meta: dict) -> int:
    """Replace occupied place-names in free-text label strings. Returns hits."""
    hits = 0

    def walk(node):
        nonlocal hits
        if isinstance(node, dict):
            for k, v in node.items():
                if isinstance(v, str):
                    new = v
                    for old, repl in _META_REPLACEMENTS:
                        new = new.replace(old, repl)
                    # Tidy artefacts left by removing a leading clause:
                    # "Перший рівень –  область" -> "Перший рівень – область"
                    new = new.replace("–  ", "– ").replace(",  ", ", ")
                    new = new.replace("– , ", "– ").rstrip(", ").strip()
                    if new != v:
                        node[k] = new
                        hits += 1
                else:
                    walk(v)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(meta)
    return hits


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check_only = "--check" in sys.argv
    if len(args) != 1:
        sys.stderr.write(
            "usage: filter_occupied_territories.py <snapshot.json> [--check]\n"
        )
        return 2

    path = args[0]
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)

    units = data.get("admin_units", [])
    before = len(units)
    kept = [u for u in units if not _is_occupied(u)]
    dropped = before - len(kept)

    meta = data.get("meta", {})
    # Count meta hits non-destructively first for the report.
    probe = json.loads(json.dumps(meta, ensure_ascii=False))
    meta_hits = _scrub_meta(probe)

    sys.stderr.write(
        f"admin_units before={before} after={len(kept)} dropped={dropped}\n"
        f"meta schema-label strings scrubbed={meta_hits}\n"
        f"excluded prefixes={EXCLUDED_PREFIXES}\n"
    )

    if check_only:
        return 0 if (dropped == 0 and meta_hits == 0) else 1

    data["admin_units"] = kept
    _scrub_meta(meta)
    data["meta"] = meta

    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
