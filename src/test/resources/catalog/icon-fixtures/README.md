# Category icon fixtures (B539, SVC-59)

Shared by `CategoryIconSanitizerTest` and (B540) `scripts/icons/tests/test_category_icon.py`.

- `accept/` — must pass. The 22 `category_*.svg` are byte-identical copies of `beautica-mobile/assets/icons/`.
- `reject/<REASON>__<case>.svg` — must be rejected with exactly `<REASON>` (`ICON_INVALID` `data.reason`).
- `expected.json` — `{"<folder>/<file>": "OK" | "<REASON>"}`, the one verdict table both implementations read.
- The oversize case (16 KiB + 1) is generated in the test, not stored.
