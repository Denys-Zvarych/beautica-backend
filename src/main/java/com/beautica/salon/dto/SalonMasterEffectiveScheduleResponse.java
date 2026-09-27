package com.beautica.salon.dto;

import com.beautica.master.dto.EffectiveDayResponse;

import java.util.List;
import java.util.UUID;

/**
 * Phase 321 — one active roster master's effective schedule over the requested date range, as
 * returned by {@code GET /api/v1/salons/{salonId}/masters/effective-schedule}.
 *
 * <p><b>Why the salon board needs this shape.</b> The mobile «Записи» board paints one column per
 * master against a SINGLE shared timeline, and that timeline must span the <em>union</em> of every
 * master's working hours — a master starting at 08:00 and another finishing at 21:00 make the grid
 * 08:00–21:00 for everyone. Deriving that union needs every master's days in one payload; the
 * per-master {@code GET /masters/{masterId}/effective-schedule} would make it an N-request fan-out
 * that resolves at N different moments, so the timeline would visibly reflow as replies landed.
 *
 * <p><b>{@code days} reuses {@link EffectiveDayResponse} verbatim</b> — the exact projection the
 * per-master endpoint already returns, so the mobile client has one day model, not two that can
 * drift. It carries the {@code source} discriminator the board greys columns by
 * ({@code OVERRIDE_DAY_OFF} → «Вихідний», {@code NO_SCHEDULE} → «Графік не задано»), which is the
 * whole reason a bare list of intervals would not do.
 *
 * <p><b>{@code days} is NEVER empty and this record is NEVER omitted for an active master</b> — see
 * {@code SalonService#getSalonMastersEffectiveSchedule}. A master absent from the response means
 * "not loaded", a distinct third state the board must be able to tell apart from "off today"; an
 * empty {@code days} list would collapse those two.
 *
 * <p><b>Management-gated, so {@code masterId} is not an internal-id leak</b> (Anti-Bug §I-2). The
 * endpoint requires {@code canManageSalon}, and the same ids are already returned to the same
 * callers by {@code GET /salons/{salonId}/staff} — the board needs them to key each column against
 * that roster. The record deliberately carries nothing else: no name, no avatar, no contact
 * details, all of which the board already holds from the staff read.
 *
 * <p><b>No working-window bounds.</b> {@code windowStart}/{@code windowEnd} on each day are always
 * {@code null} here — the batch resolver is the window-free variant (Phase 15.12), and the board
 * renders bookable {@code intervals}, never the day-editor's break reconstruction. The per-master
 * {@code /effective-schedule} endpoint remains the one surface that projects the window.
 */
public record SalonMasterEffectiveScheduleResponse(
        UUID masterId,
        List<EffectiveDayResponse> days
) {
}
