package com.beautica.notification;

/**
 * The ONE shared expected-copy fixture for the phase 496 registered-client receipt e-mails (NTF-49). Tests take the
 * expected subjects and body lines from here, never a re-typed literal, so a copy change breaks in one place.
 *
 * <p>Values are written out on purpose (not read from {@code EmailNotificationService} or the templates). They
 * mirror {@code EmailNotificationService#sendBookingRescheduledClientEmail(String, Booking, boolean)},
 * {@code #sendClientCancelReceiptEmail}, {@code templates/email/booking-rescheduled-client.html} and
 * {@code templates/email/booking-cancelled-client.html}.
 */
public final class ClientReceiptEmailExpectedCopy {

    public static final String RESCHEDULE_TEMPLATE = "email/booking-rescheduled-client";
    public static final String CANCEL_TEMPLATE = "email/booking-cancelled-client";

    /** The provider-move subject (unchanged since phase 27.3). */
    public static final String PROVIDER_RESCHEDULE_SUBJECT = "Бронювання перенесено";
    public static final String SELF_RESCHEDULE_HEADING = "Ви перенесли запис";
    public static final String SELF_RESCHEDULE_LEAD = "Ви перенесли запис на новий час:";
    public static final String PROVIDER_RESCHEDULE_LEAD = "Ваш майстер переніс бронювання на новий час:";
    public static final String CANCEL_HEADING = "Ви скасували запис";
    public static final String CANCEL_LEAD = "Ваш запис скасовано:";
    /** The performing master's e-mail on a client cancel (unchanged). */
    public static final String MASTER_CANCEL_SUBJECT = "Клієнт скасував бронювання";
    /** The client's booking-confirmed e-mail (unchanged). */
    public static final String CONFIRMED_SUBJECT = "Бронювання підтверджено";
    /** The client's decline e-mail (unchanged). */
    public static final String DECLINED_SUBJECT = "Бронювання скасовано";

    private ClientReceiptEmailExpectedCopy() {
    }

    /** «Beautica: ви перенесли запис на {d MMMM} о {HH:mm}» — Kyiv values, e.g. {@code ("20 травня", "10:00")}. */
    public static String selfRescheduleSubject(String kyivDay, String kyivTime) {
        return "Beautica: ви перенесли запис на " + kyivDay + " о " + kyivTime;
    }

    /** «Beautica: ви скасували запис {d MMMM} о {HH:mm}» — Kyiv values. */
    public static String cancelReceiptSubject(String kyivDay, String kyivTime) {
        return "Beautica: ви скасували запис " + kyivDay + " о " + kyivTime;
    }
}
