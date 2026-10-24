-- Phase 390 D11 (QA INFO-3) — SMS-backpressure deferral must not starve newer outbox rows.
--
-- A row deferred because the SMS executor is saturated keeps its created_at, so under the oldest-first
-- claim (NotificationOutboxRepository#claimPendingBatch) >= 50 deferred SMS rows at the head were
-- re-claimed every 5 s tick and newer e-mail rows waited behind them. The worker now stamps
-- not_before = now() + a few seconds on exactly that deferral (no attempt counted), and the non-push
-- claim branch skips rows whose not_before is still in the future. NULL = eligible immediately, which
-- is every existing row and every row that was never SMS-deferred: their claim behaviour is unchanged.
--
-- No new index: the claim still walks idx_outbox_pending (created_at) WHERE status = 'PENDING' (V32)
-- in created_at order and evaluates not_before as a row filter on the heap tuple it must visit anyway
-- for FOR UPDATE SKIP LOCKED. The rows skipped that way are only the ones deferred in the last few
-- seconds (at most a few batches), so an index on not_before would be pure write cost.
--
-- Locking (as V199/V202): metadata-only ADD COLUMN; lock_timeout bounds the ACCESS EXCLUSIVE wait.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE notification_outbox
    ADD COLUMN not_before TIMESTAMPTZ;

COMMENT ON COLUMN notification_outbox.not_before IS
    'Earliest instant the drain worker may claim this PENDING row again; NULL = immediately. '
    'Set only on SMS-backpressure deferral (phase 390 D11).';
