-- Phase 29.6 — the dedupe ledger for the closure-reminder job.
--
-- Backed by a real table, not a scan over notification_outbox (whose rows are prunable — see
-- NotificationOutboxDrainWorker#purgeStaleOutboxRows, a 30-day TTL purge). The PRIMARY KEY
-- (booking_id) IS the uniqueness guarantee: one row per booking, ever. It also doubles as the
-- atomic-claim contention point for ClosureReminderRepository#claimDueReminders's
-- INSERT ... ON CONFLICT (booking_id) DO UPDATE ... RETURNING statement — two concurrent
-- application instances (Railway performs rolling deploys; there is no ShedLock in this repo)
-- contend on this primary key, and Postgres serialises the ON CONFLICT DO UPDATE under a row
-- lock, so at most one instance's claim of a given booking ever returns a row in one job run.
--
-- Rejected alternative (see phase-224 doc): a partial unique index on
-- notification_outbox (aggregate_id) WHERE event_type = 'CLOSURE_REMINDER'. That caps reminders
-- at exactly 1 and couples dedupe to outbox retention; this counter table supports N (default 2,
-- hard-clamped to 5 — see chk_closure_reminder_count) and survives outbox pruning.
CREATE TABLE booking_closure_reminders (
    booking_id     UUID        PRIMARY KEY REFERENCES bookings(id) ON DELETE CASCADE,
    reminder_count SMALLINT    NOT NULL DEFAULT 0,
    last_sent_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_closure_reminder_count CHECK (reminder_count BETWEEN 0 AND 5)
);

-- No index on last_sent_at. An earlier draft of this migration added
-- idx_closure_reminders_last_sent, reasoned by analogy to notification_outbox's
-- idx_outbox_pending / idx_outbox_terminal_updated — but, unlike those two, nothing here actually
-- queries last_sent_at: the claim's dedupe guard (ClosureReminderRepository#claimDueReminders) is
-- expressed as a WHERE clause on the ON CONFLICT DO UPDATE action itself, resolved via the PK
-- arbiter (EXPLAIN confirms "Conflict Arbiter Indexes: booking_closure_reminders_pkey" — the
-- PRIMARY KEY on booking_id, not this index), and no other query anywhere in the codebase filters
-- or sorts on last_sent_at. This repo's locked rule is measure-before-indexing (see V130's
-- "Seq Scan is fine, don't index it" verdict for the same discipline in the other direction); an
-- index this table's own write path (an INSERT/UPDATE on EVERY claim) pays for on every write but
-- no reader ever benefits from does not meet that bar. Dropped before this migration ever shipped
-- to a real environment (verified against flyway_schema_history — see phase-224 doc's Status
-- block). If a future monitoring/admin query needs "reminders sent recently", add the index THEN,
-- with that query as the measured justification.
