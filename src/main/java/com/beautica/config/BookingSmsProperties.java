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

    /** Phase 413 — the 2h-before guest reminder ({@code app.booking.reminder-2h.*}). */
    private final Reminder2h reminder2h = new Reminder2h();

    public Reminder2h getReminder2h() {
        return reminder2h;
    }

    /**
     * Phase 413 D3/D6 — {@code enabled} (env {@code APP_BOOKING_REMINDER_2H_ENABLED}, default off) and
     * {@code min-lead}: no 2h reminder for a booking created less than this long before its start (the
     * confirmation SMS just arrived).
     */
    public static class Reminder2h {

        private boolean enabled = false;

        private java.time.Duration minLead = java.time.Duration.ofHours(3);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.time.Duration getMinLead() {
            return minLead;
        }

        public void setMinLead(java.time.Duration minLead) {
            if (minLead == null || minLead.isNegative()) {
                throw new IllegalArgumentException("app.booking.reminder-2h.min-lead must be >= 0");
            }
            this.minLead = minLead;
        }
    }

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

    /** SMS body templates, plus the platform-wide outbound-SMS gate. */
    public static class Sms {

        /**
         * The single "are we spending SMS money" switch (Phase 22.7). <b>Default {@code false}</b>,
         * and every committed profile declares it {@code false} explicitly — including
         * {@code local}. Railway override at release: {@code APP_BOOKING_SMS_ENABLED=true}.
         *
         * <p>Governs <b>all</b> outbound booking SMS at once — walk-in confirmation, guest
         * confirmation, 24h reminder, cancellation and provider decline — because it is consumed
         * by {@code SmsConfig}, which uses it to pick the {@code SmsService} bean itself
         * ({@code NoOpSmsService} when off, {@code TurbosmsService} when on) rather than by any
         * individual sender. No call site reads this field; if you find yourself adding an
         * {@code if} on it, the gate has been misunderstood.
         *
         * <p>Sibling vendor/money gates, deliberately separate so each can be released on its own
         * schedule: {@code FIREBASE_ENABLED} (push, {@code FirebaseConfig}) and
         * {@code app.cloudflare-r2.enabled} (media, {@code S3Config}).
         *
         * <p>Distinct from {@code app.sms.turbosms.token}: a blank token means "unconfigured" and
         * throws loudly on the first send, whereas this flag off means "deliberately silent" and
         * never throws.
         */
        private boolean enabled = false;

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

        /**
         * Walk-in (STAFF-sourced) confirmation template — the SMS a master's own walk-in client
         * receives after the provider keys the appointment in (Phase 22.7). Placeholders:
         * {@code {masterName}}, {@code {serviceName}}, {@code {date}}, {@code {time}},
         * {@code {cancelUrl}}.
         *
         * <p><b>Phase 412 D3 — gains {@code {cancelUrl}}</b> (reverses 254 D's «must never gain one»).
         * The walk-in visit header now carries a one-time cancel token (V226), and
         * {@code StaffBookingService} always maps {@code cancelUrl} to that visit's
         * {@code /book/cancel/{token}} link, so the placeholder can no longer render literally. Value
         * per D3 (architect, 2026-10-31) = the LINK confirmation layout ending in «Скасувати: {cancelUrl}»;
         * the pre-412 «Скасувати — за телефоном майстра.» line is dropped. 3 UCS-2 segments (~154 chars) for a typical
         * master/service.
         *
         * <p>Kept separate from {@link #confirmation} (the LINK copy) even though the wording now matches:
         * the walk-in SMS renders the PLATFORM service name, never the provider's custom one, and its copy
         * may diverge again without touching the LINK SMS.
         *
         * <h4>Two homes, kept identical (phase 412 D3)</h4>
         * D3 restores the {@code app.booking.sms.walk-in-confirmation} key in {@code application.yml},
         * so — like every sibling template here — the yml value is the LIVE one and this literal is the
         * fallback for a context without that file. Change both together.
         */
        private String walkInConfirmation =
                "Beautica: Запис підтверджено!\n"
                        + "{masterName}, {serviceName}\n"
                        + "{date} о {time}\n\n"
                        + "Скасувати: {cancelUrl}";

        public String getConfirmation() {
            return confirmation;
        }

        public void setConfirmation(String confirmation) {
            this.confirmation = confirmation;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWalkInConfirmation() {
            return walkInConfirmation;
        }

        public void setWalkInConfirmation(String walkInConfirmation) {
            this.walkInConfirmation = walkInConfirmation;
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

        /**
         * Phase 413 D5 — the 2h-before reminder. Java default only (walk-in precedent: no yml copy). "Сьогодні"
         * is safe: the sweep sends only 09:00–21:00 Kyiv with a 1h45–2h15 lead, so the visit is the same day.
         */
        private String reminder2h =
                "Beautica: Нагадуємо!\n"
                        + "{serviceName} у {masterName}\n"
                        + "Сьогодні о {time}";

        public String getReminder2h() {
            return reminder2h;
        }

        public void setReminder2h(String reminder2h) {
            this.reminder2h = reminder2h;
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
         * Phase 431 — the SMS an account-less guest (LINK guest or STAFF walk-in) gets when the
         * PROVIDER moves their booking, or one service of their visit, to a new time. Placeholders:
         * {@code {date}} ({@code dd.MM.yyyy}) and {@code {time}} ({@code HH:mm}), both Kyiv wall clock,
         * each exactly once. Fixed copy otherwise — no provider note, no master/salon name — on the
         * phase 390 cancellation-SMS precedent ({@code GuestProviderCancellationSmsText}), whose
         * "Beautica: " prefix it follows.
         *
         * <p>53 UCS-2 chars rendered (one segment). {@code GuestRescheduleSmsText} fails boot when an
         * override drops a placeholder or renders over two UCS-2 segments (134 chars).
         *
         * <p>Like {@link #walkInConfirmation}, this Java literal is the SINGLE source of the copy:
         * {@code application.yml} carries no {@code reschedule:} key. Override per environment with
         * the relaxed-binding env var {@code APP_BOOKING_SMS_RESCHEDULE}.
         */
        private String reschedule = "Beautica: Ваш запис перенесено на {date} о {time}.";

        public String getReschedule() {
            return reschedule;
        }

        public void setReschedule(String reschedule) {
            this.reschedule = reschedule;
        }

        // Phase 390: the guest provider-cancellation SMS (decline, salon closure, master removal)
        // is fixed copy in notification/service/GuestProviderCancellationSmsText, not a template
        // here — it carries no provider text and its length is pinned by tests. The former
        // decline / declineReason / salonClosed / masterRemoved templates were removed with it.
    }
}
