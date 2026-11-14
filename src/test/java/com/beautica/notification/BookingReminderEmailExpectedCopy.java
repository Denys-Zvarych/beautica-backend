package com.beautica.notification;

import com.beautica.notification.service.ReminderKind;

/**
 * The ONE shared expected-copy fixture for the phase 494 registered-client reminder e-mail (NTF-47). Pre-push gate
 * rule 4 (orchestrate.md Step 2.9): tests take the expected subject and body lines from here, never a re-typed
 * literal, so a copy change breaks in one place.
 *
 * <p>Values are written out on purpose (not read from {@code EmailNotificationService} or the template): an
 * assertion against the renderer's own constant would pass whatever the renderer says. They mirror
 * {@code EmailNotificationService#bookingReminderSubject} and {@code templates/email/booking-reminder.html}.
 */
public final class BookingReminderEmailExpectedCopy {

    /** The template name the reminder renders. */
    public static final String TEMPLATE = "email/booking-reminder";

    private BookingReminderEmailExpectedCopy() {
    }

    /** {@code «Beautica: нагадування — ваш запис завтра|сьогодні о {HH:mm}»} — {@code kyivTime} is Kyiv wall time. */
    public static String subject(ReminderKind kind, String kyivTime) {
        return "Beautica: нагадування — ваш запис " + day(kind) + " о " + kyivTime;
    }

    /** The body heading: «Ваш запис уже завтра» / «Ваш запис уже сьогодні». */
    public static String heading(ReminderKind kind) {
        return "Ваш запис уже " + day(kind);
    }

    /** The body's lead sentence: «Нагадуємо, що завтра|сьогодні у вас запис:». */
    public static String leadLine(ReminderKind kind) {
        return "Нагадуємо, що " + day(kind) + " у вас запис:";
    }

    private static String day(ReminderKind kind) {
        return kind == ReminderKind.H24 ? "завтра" : "сьогодні";
    }
}
