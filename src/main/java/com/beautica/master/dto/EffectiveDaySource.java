package com.beautica.master.dto;

/**
 * Phase 15.2: provenance of an {@link EffectiveDayResponse} — which schedule layer produced the
 * effective availability for that calendar date.
 *
 * <ul>
 *   <li>{@link #TEMPLATE} — derived from the weekly template (no override for the date).</li>
 *   <li>{@link #OVERRIDE_CUSTOM} — a per-date {@code CUSTOM_HOURS} override replaced the template hours.</li>
 *   <li>{@link #OVERRIDE_DAY_OFF} — a per-date {@code DAY_OFF} override closed the date.</li>
 *   <li>{@link #NO_SCHEDULE} — no weekly template covers the date (and no override).</li>
 * </ul>
 */
public enum EffectiveDaySource {
    TEMPLATE,
    OVERRIDE_CUSTOM,
    OVERRIDE_DAY_OFF,
    NO_SCHEDULE
}
