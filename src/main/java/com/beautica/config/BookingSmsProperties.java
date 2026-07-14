package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed config for guest-booking SMS templates and the availability window
 * (Phase 13.3). Binding prefix {@code app.booking}.
 *
 * <p>The SMS bodies are Ukrainian copy with {@code {placeholder}} tokens
 * substituted by {@code GuestBookingService} / {@code BookingReminderJob}. Kept
 * in config (not hardcoded) so copy can be tuned without a redeploy. No secret
 * data — the templates are not sensitive — but the rendered text (a guest's
 * appointment details) is still never logged (Anti-Bug §I).
 */
@ConfigurationProperties(prefix = "app.booking")
public class BookingSmsProperties {

    /**
     * Maximum number of days ahead the public availability endpoint will serve
     * slots for. A {@code date} beyond {@code today + this} is rejected as a 400.
     */
    private int availabilityMaxDays = 60;

    /**
     * Minimum lead time, in hours, before {@code startsAt} during which a guest may
     * still cancel via the cancel link (Phase 13.4). A cancellation requested when
     * {@code startsAt - now < this} is rejected with HTTP 422. Default 2.
     */
    private int cancelWindowHours = 2;

    private final Sms sms = new Sms();

    public int getAvailabilityMaxDays() {
        return availabilityMaxDays;
    }

    public void setAvailabilityMaxDays(int availabilityMaxDays) {
        this.availabilityMaxDays = availabilityMaxDays;
    }

    public int getCancelWindowHours() {
        return cancelWindowHours;
    }

    public void setCancelWindowHours(int cancelWindowHours) {
        this.cancelWindowHours = cancelWindowHours;
    }

    public Sms getSms() {
        return sms;
    }

    /** SMS body templates. */
    public static class Sms {

        /**
         * Confirmation template. Placeholders: {@code {masterName}},
         * {@code {serviceName}}, {@code {date}}, {@code {time}}, {@code {cancelUrl}}.
         */
        private String confirmation =
                "Beautica: Запис підтверджено!\n"
                        + "{masterName}, {serviceName}\n"
                        + "{date} о {time}\n\n"
                        + "Скасувати: {cancelUrl}";

        /**
         * 24h reminder template. Placeholders: {@code {serviceName}},
         * {@code {masterName}}, {@code {time}}.
         */
        private String reminder =
                "Beautica: Нагадуємо!\n"
                        + "{serviceName} у {masterName}\n"
                        + "Завтра о {time}";

        public String getConfirmation() {
            return confirmation;
        }

        public void setConfirmation(String confirmation) {
            this.confirmation = confirmation;
        }

        /**
         * Cancellation-confirmation template (Phase 13.4). Placeholders:
         * {@code {serviceName}}, {@code {masterName}}, {@code {date}}, {@code {time}}.
         */
        private String cancellation =
                "Beautica: Ваш запис скасовано.\n"
                        + "{serviceName} у {masterName}\n"
                        + "{date} о {time}";

        public String getReminder() {
            return reminder;
        }

        public void setReminder(String reminder) {
            this.reminder = reminder;
        }

        public String getCancellation() {
            return cancellation;
        }

        public void setCancellation(String cancellation) {
            this.cancellation = cancellation;
        }

        /**
         * Provider-decline template for a GUEST (LINK) booking (Phase 25.7). A guest has no
         * account for email/push (V89 {@code chk_bookings_guest_fields}), so this SMS is the
         * ONLY channel that tells them their booking was declined. Placeholders:
         * {@code {serviceName}}, {@code {masterName}}, {@code {date}}, {@code {time}},
         * {@code {comment}} — the provider's decline note, truncated to ~120 chars by
         * {@code NotificationService} before substitution (Cyrillic SMS segments are
         * ~70 chars; 120 chars keeps this to two segments).
         */
        private String decline =
                "Beautica: На жаль, ваш запис скасовано.\n"
                        + "{serviceName} у {masterName}\n"
                        + "{date} о {time}\n\n"
                        + "Причина: {comment}";

        public String getDecline() {
            return decline;
        }

        public void setDecline(String decline) {
            this.decline = decline;
        }
    }
}
