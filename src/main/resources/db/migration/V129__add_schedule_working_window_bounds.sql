-- Phase 15.12 — optional working-window bounds for the schedule editor (DISPLAY-ONLY metadata).
--
-- WHY. The mobile schedule editor presents a day as ONE working window (від–до) with optional breaks
-- carved out of it. Only the resulting working INTERVALS are stored, and the editor reconstructs breaks
-- from the GAPS BETWEEN consecutive intervals. That reconstruction cannot represent a break flush against
-- an edge of the window: window 09:00–18:00 with a break 09:00–10:00 stores as the single interval
-- [10:00–18:00] — there is no gap, so on reload it reads back as "window 10:00–18:00, no breaks".
-- Storing the window (and deriving breaks client-side as `window MINUS intervals`) recovers every break,
-- flush or not, with no second source of truth to keep consistent.
--
-- CONTRACT. window_start/window_end are NULLABLE and carry NO availability meaning. The interval list
-- remains the single canonical source of bookable time: the slot/booking engine
-- (com.beautica.booking.service.SlotCalculationService) resolves availability through
-- MasterScheduleService#resolveEffectiveDay, which reads intervals/discrete times ONLY and never these
-- columns. A stored window can therefore never widen nor narrow a bookable slot.
--
-- NO BACKFILL — deliberately. Legacy rows stay NULL. Synthesising min(start_time)..max(end_time) would be
-- indistinguishable from a window the user actually chose, and would silently assert "this day has no
-- edge-flush break" for every historical row. NULL means "unknown" and the client falls back to the old
-- gap reconstruction, i.e. exactly today's behaviour.
--
-- NO CROSS-MIDNIGHT. The window inherits the Phase 15.x locked contract: window_end > window_start
-- strictly, no wraparound (a night shift is two ISO-weekday rows, never one wrapping row).

-- ── Surface 1: the weekly template ────────────────────────────────────────────────────────────────
--
-- The weekly template has no "one day of a schedule" row to hang the columns on: weekly_schedules is the
-- validity WINDOW (one row per master per date range) and working_intervals / working_interval_times are
-- keyed by (schedule_id, day_of_week) with N rows per day. So the per-day window gets its own child table,
-- exactly mirroring working_interval_times (V84): same parent, same ISO day_of_week key, ON DELETE CASCADE.
-- The partial-UNIQUE-style "1:1 per entity" rule (Anti-Bug §O.4) is a plain UNIQUE here — a day has at most
-- one window, always.
CREATE TABLE weekly_schedule_day_windows (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id   UUID NOT NULL REFERENCES weekly_schedules(id) ON DELETE CASCADE,
    day_of_week   INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO 1=Mon..7=Sun
    window_start  TIME NOT NULL,
    window_end    TIME NOT NULL,
    CONSTRAINT chk_day_window_order CHECK (window_end > window_start),    -- no zero-length, no midnight-cross
    CONSTRAINT uq_day_window_per_day UNIQUE (schedule_id, day_of_week)
);

-- NOTE: no separate CREATE INDEX here, unlike the sibling working_intervals (V70) / working_interval_times
-- (V84) tables. Postgres backs uq_day_window_per_day with a btree on exactly (schedule_id, day_of_week) in
-- exactly that order, which already serves both access shapes this table has: the mapper's per-schedule
-- load and the service's per-day replace. A hand-rolled index on the same column list would be a pure
-- duplicate — never chosen by the planner, but still maintained on every write. That matters here because
-- replaceDayCollections is delete-all-then-reinsert, so a 7-day template save would pay two btree passes
-- and double index WAL for ≤14 row operations with zero read benefit.

COMMENT ON TABLE weekly_schedule_day_windows IS
    'Phase 15.12: optional DISPLAY-ONLY working-window bounds for one INTERVAL weekday of a '
    'weekly_schedule. Breaks are derived client-side as (window MINUS working_intervals). Absent row = '
    'no window recorded (legacy / client omitted it) -> the client falls back to gap reconstruction. '
    'Availability is computed from working_intervals ALONE; the slot engine never reads this table. '
    'Containment (window_start <= min(interval.start_time) AND window_end >= max(interval.end_time)) is a '
    'service-layer invariant — Postgres CHECK cannot reference the sibling working_intervals table.';

-- ── Surface 2: the per-date override ──────────────────────────────────────────────────────────────
--
-- Here the "one day of a schedule" row already exists: schedule_exceptions is one row per (master, date).
-- So the two columns go directly on it — no child table needed.
ALTER TABLE schedule_exceptions
    ADD COLUMN window_start TIME,
    ADD COLUMN window_end   TIME;

-- Both null, or both set — a half-specified window is malformed input, never a stored state.
ALTER TABLE schedule_exceptions
    ADD CONSTRAINT chk_exc_window_pair CHECK ((window_start IS NULL) = (window_end IS NULL));

-- Same strict ordering the interval rows get (chk_exc_interval_order): no zero-length, no midnight-cross.
ALTER TABLE schedule_exceptions
    ADD CONSTRAINT chk_exc_window_order CHECK (window_end IS NULL OR window_end > window_start);

-- Polymorphic CHECK (Anti-Bug §O.3): a window only means something for a CUSTOM_HOURS override that
-- actually carries intervals. A DAY_OFF closes the date, so "the working window of that day" is not a
-- thing — the service persists NULL and ignores any supplied value rather than inventing one.
ALTER TABLE schedule_exceptions
    ADD CONSTRAINT chk_exc_window_kind CHECK (window_start IS NULL OR kind = 'CUSTOM_HOURS');

COMMENT ON COLUMN schedule_exceptions.window_start IS
    'Phase 15.12: optional DISPLAY-ONLY working-window start for a CUSTOM_HOURS override. NULL = not '
    'recorded. Never read by the slot/booking engine; availability comes from '
    'schedule_exception_intervals alone.';
COMMENT ON COLUMN schedule_exceptions.window_end IS
    'Phase 15.12: optional DISPLAY-ONLY working-window end for a CUSTOM_HOURS override. NULL = not '
    'recorded. Never read by the slot/booking engine; availability comes from '
    'schedule_exception_intervals alone.';
