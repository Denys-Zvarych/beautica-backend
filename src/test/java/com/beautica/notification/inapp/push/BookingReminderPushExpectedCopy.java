package com.beautica.notification.inapp.push;

/**
 * The ONE shared expected-copy fixture for the phase 495 {@code BOOKING_REMINDER} push (NTF-48). Pre-push gate rule 4
 * (orchestrate.md Step 2.9): tests take the expected title and body from here, never a re-typed literal, so a copy
 * change breaks in one place.
 *
 * <p>Values are written out on purpose (not read from {@link PushCopy}): an assertion against the renderer's own
 * constant would pass whatever the renderer says. The title mirrors the mobile ARB
 * {@code notificationTitleBookingReminder} (uk, M497); the body mirrors the phase 495 contract
 * «{serviceName} · {day} о {HH:mm} — {counterpart}» (NTF-D01), where {day} is «сьогодні» / «завтра» relative to the
 * Kyiv date at push time, otherwise the short date «вт, 17 листопада». The caller supplies the day word and the Kyiv
 * wall time, so the zone and calendar arithmetic stay under test rather than being re-derived here.
 */
public final class BookingReminderPushExpectedCopy {

    /** = mobile {@code app_uk.arb} {@code notificationTitleBookingReminder}. */
    public static final String TITLE = "Нагадування про візит";

    private BookingReminderPushExpectedCopy() {
    }

    /** Day word when the visit's Kyiv date is the Kyiv date at push time. */
    public static final String TODAY = "сьогодні";

    /** Day word when the visit's Kyiv date is the day after the Kyiv date at push time. */
    public static final String TOMORROW = "завтра";

    /**
     * «{service} · {day} о {kyivTime} — {counterpart}»; {@code day} is {@link #TODAY}, {@link #TOMORROW} or a short
     * date such as «вт, 17 листопада»; {@code kyivTime} is Europe/Kyiv {@code HH:mm}.
     */
    public static String body(String service, String day, String kyivTime, String counterpart) {
        return service + " · " + day + " о " + kyivTime + " — " + counterpart;
    }
}
