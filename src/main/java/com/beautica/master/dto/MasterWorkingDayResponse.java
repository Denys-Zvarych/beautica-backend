package com.beautica.master.dto;

import java.time.LocalDate;

/**
 * Phase 15.11: CLIENT-safe calendar day-gating projection — whether a master is working on
 * {@code date}, boolean only. Backs the mobile calendar's "grey out non-working days" feature.
 *
 * <p>Deliberately carries no hours, intervals, or discrete times: {@code CLIENT} is blocked from the
 * raw schedule-read endpoints ({@code GET /{masterId}/effective-schedule}, {@code /weekly-schedules},
 * {@code /overrides} — see {@link com.beautica.common.security.AuthorizationService#canReadMasterSchedule}),
 * so this response shape is reduced to a boolean in the service layer, before it ever reaches the
 * controller, via {@link EffectiveDayResponse#isWorkingDay()}.
 */
public record MasterWorkingDayResponse(LocalDate date, boolean working) {
}
