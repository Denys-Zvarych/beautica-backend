-- dev-cleanup-null-city-salons.sql
--
-- *** DESTRUCTIVE. LOCAL DEV DATABASES ONLY. NEVER RUN AGAINST A SHARED OR PRODUCTION DB. ***
--
-- Purpose
-- -------
-- V150__salons_city_id_not_null.sql (src/main/resources/db/migration/) promotes
-- salons.city_id to NOT NULL and REFUSES to apply (RAISE EXCEPTION) if any pre-existing salon
-- row still has city_id IS NULL. It does this deliberately: it will never auto-delete rows on
-- your behalf, because on a real (Railway/Neon) database those rows could be genuine bookings
-- and reviews.
--
-- If you are a developer with a disposable LOCAL database (docker/local/docker-compose.yml)
-- that predates the Phase 10.6 write-path guard and has legacy salons with city_id IS NULL,
-- V150 will fail to apply until those rows are gone or backfilled. This script is the by-hand
-- equivalent of the DELETE cascade that migration used to contain, for you to run yourself,
-- knowingly, against your own local DB — it is NOT wired into Flyway or any Gradle task, and
-- must never be.
--
-- What it does
-- ------------
-- Hard-deletes every salon with city_id IS NULL, and everything that transitively depends on
-- it (appointments, bookings, booking_closure_reminders, reviews, client_reviews, and clears
-- invite_tokens.salon_id), in the same FK order AbstractIntegrationTest#cleanDb() uses so no
-- RESTRICT/NO ACTION constraint is tripped. This is real, irreversible data loss for whatever
-- it touches — that is fine for a disposable local dev DB you can rebuild from
-- docker compose, and never fine anywhere else.
--
-- How to run it
-- --------------
--   psql "$LOCAL_DATABASE_URL" -f scripts/dev-cleanup-null-city-salons.sql
-- or via the local docker-compose Postgres container:
--   docker exec -i beautica-postgres psql -U beautica -d beautica < scripts/dev-cleanup-null-city-salons.sql
--
-- After running this, re-run ./gradlew bootRun (or your Flyway migrate step) and V150 will
-- find zero dirty rows and apply cleanly.

BEGIN;

CREATE TEMP TABLE _cleanup_dirty_salons AS
SELECT id FROM salons WHERE city_id IS NULL;

CREATE TEMP TABLE _cleanup_dirty_appointments AS
SELECT id FROM appointments WHERE salon_id IN (SELECT id FROM _cleanup_dirty_salons);

CREATE TEMP TABLE _cleanup_dirty_bookings AS
SELECT id FROM bookings
WHERE salon_id IN (SELECT id FROM _cleanup_dirty_salons)
   OR appointment_id IN (SELECT id FROM _cleanup_dirty_appointments);

-- client_reviews / reviews: both salon_id and booking_id carry ON DELETE NO ACTION/RESTRICT
-- back to salons/bookings — must be removed before bookings and before salons.
DELETE FROM client_reviews
WHERE salon_id IN (SELECT id FROM _cleanup_dirty_salons)
   OR booking_id IN (SELECT id FROM _cleanup_dirty_bookings);

DELETE FROM reviews
WHERE salon_id IN (SELECT id FROM _cleanup_dirty_salons)
   OR booking_id IN (SELECT id FROM _cleanup_dirty_bookings);

-- booking_closure_reminders.booking_id is ON DELETE CASCADE from bookings, but this project's
-- convention (Anti-Bug Playbook §O.7) is never to rely on CASCADE — delete explicitly, before
-- bookings, mirroring cleanDb().
DELETE FROM booking_closure_reminders
WHERE booking_id IN (SELECT id FROM _cleanup_dirty_bookings);

-- bookings.appointment_id -> appointments(id) is ON DELETE NO ACTION — bookings must be removed
-- before their appointment header.
DELETE FROM bookings WHERE id IN (SELECT id FROM _cleanup_dirty_bookings);

DELETE FROM appointments WHERE id IN (SELECT id FROM _cleanup_dirty_appointments);

-- invite_tokens.salon_id is ON DELETE SET NULL (V5__Fix_invite_tokens_cascade.sql superseded
-- V4's CASCADE) so it would not block the salons DELETE below on its own, but it is cleared
-- explicitly anyway per §O.7 (never rely on FK-driven side effects, even benign ones).
DELETE FROM invite_tokens WHERE salon_id IN (SELECT id FROM _cleanup_dirty_salons);

DELETE FROM salons WHERE id IN (SELECT id FROM _cleanup_dirty_salons);

DROP TABLE _cleanup_dirty_bookings;
DROP TABLE _cleanup_dirty_appointments;
DROP TABLE _cleanup_dirty_salons;

COMMIT;
