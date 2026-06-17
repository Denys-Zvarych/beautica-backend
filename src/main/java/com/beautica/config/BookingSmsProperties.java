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

    private final Sms sms = new Sms();

    public int getAvailabilityMaxDays() {
        return availabilityMaxDays;
    }

    public void setAvailabilityMaxDays(int availabilityMaxDays) {
        this.availabilityMaxDays = availabilityMaxDays;
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

        public String getReminder() {
            return reminder;
        }

        public void setReminder(String reminder) {
            this.reminder = reminder;
        }
    }
}
