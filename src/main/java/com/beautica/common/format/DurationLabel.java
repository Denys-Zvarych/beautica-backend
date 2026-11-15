package com.beautica.common.format;

/**
 * The one Ukrainian short duration label — «45 хв», «1 год», «1 год 30 хв». Mirrors the app's
 * {@code beautica-mobile/lib/shared/formatters/duration_minutes.dart}; every backend caller (e-mail
 * service lines, .ics) goes through here so the copy cannot drift.
 */
public final class DurationLabel {

    private static final int MINUTES_PER_HOUR = 60;

    private DurationLabel() {
    }

    public static String format(int totalMinutes) {
        int hours = totalMinutes / MINUTES_PER_HOUR;
        int minutes = totalMinutes % MINUTES_PER_HOUR;
        if (hours == 0) {
            return minutes + " хв";
        }
        return minutes == 0 ? hours + " год" : hours + " год " + minutes + " хв";
    }
}
