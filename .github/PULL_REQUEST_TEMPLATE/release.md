<!--
Release PR: dev -> main. Opened and merged by the repo owner only.
Open with: .../compare/main...dev?template=release.md
Title: release: backend vYYYY.MM.DD[.N]
Merge method: MERGE COMMIT (never squash or rebase: main and dev would diverge).
After merge: annotated tag on main: backend-vYYYY.MM.DD[.N]  (hotfix: ...-hotfix.N)
-->

## Release

- Version / tag: `backend-vYYYY.MM.DD[.N]`
- `dev` SHA being promoted: `<sha>`
- Phases / PRs in this release: <list>

## Promotion checklist

- [ ] The `dev` SHA above has been deployed to staging and that deploy is the current one (Railway staging deployment shows the same SHA).
- [ ] `scripts/staging-smoke.sh` is green on that deployment.
- [ ] Manual E2E on staging with the `staging` mobile flavor for every phase in the release (Qase run linked: <link>).
- [ ] New migrations in the range are listed below, and none is a contract step (`-- contract-of: V<n>`) whose expand step is not already live in prod.
- [ ] Backup (366) shows `status=ok` within the last 24 h (prod `db-backup` once 366 section E is enabled, staging `db-backup` rehearsal before that).
- [ ] Rollback note filled in below (previous prod deploy ID / tag).

## New migrations (V<from> to V<to>)

<!-- List each new V*.sql. For every destructive one (DROP TABLE / DROP COLUMN / RENAME / SET NOT NULL / ALTER COLUMN TYPE) name its expand migration and the prod release that already stopped using the object. -->

-

## Rollback note

- Previous prod deploy ID / tag: `<id or backend-v...>`
