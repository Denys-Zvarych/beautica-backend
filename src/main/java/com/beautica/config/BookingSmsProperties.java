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
         * {@code {masterName}}, {@code {serviceName}}, {@code {date}}, {@code {time}}.
         *
         * <p><b>Carries no {@code {cancelUrl}}, and must never gain one.</b> A STAFF booking has
         * {@code cancel_token = NULL} by the V137 CHECK — self-service cancellation is a LINK-path
         * capability — so there is no token to build a URL from. The closing line therefore routes
         * the client back to the master by phone, which is how the booking was made in the first
         * place. A {@code {cancelUrl}} added here would render literally, as the placeholder text,
         * in a real client's message.
         *
         * <p>Kept separate from {@link #confirmation} rather than reusing it with an empty
         * {@code cancelUrl}: that would leave a dangling «Скасувати: » label with nothing after it,
         * the exact defect {@link #declineReason} exists to avoid on the decline path.
         *
         * <h4>This literal is the SINGLE source of the copy (QA MEDIUM, 2026-08-18)</h4>
         * It shipped duplicated: an identical block scalar at {@code app.booking.sms.
         * walk-in-confirmation} in {@code application.yml} overrode it in every profile, so
         * mutating this default changed nothing observable anywhere — two sources of truth for one
         * user-visible string, one of them dead. The yml key has been REMOVED; only a pointer
         * comment remains there. Re-adding {@code walk-in-confirmation:} to any profile silently
         * makes this field dead again, so add it only to genuinely OVERRIDE this text, never to
         * restate it.
         *
         * <p>Every sibling template in this class still carries its yml twin and is therefore
         * yml-driven — that duplication is pre-existing and outside this change's scope — so when
         * editing copy, check which of the two homes is live for the template being touched.
         */
        private String walkInConfirmation =
                "Beautica: Запис підтверджено!\n"
                        + "{masterName}, {serviceName}\n"
                        + "{date} о {time}\n\n"
                        + "Скасувати — за телефоном майстра.";

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
         * {@code {serviceName}}, {@code {masterName}}, {@code {date}}, {@code {time}}. Does NOT
         * carry the reason clause — see {@link #declineReason} — the provider's note is optional
         * (Phase 25.9) and this base template must stand alone, coherently, without it.
         */
        private String decline =
                "Beautica: На жаль, ваш запис скасовано.\n"
                        + "{serviceName} у {masterName}\n"
                        + "{date} о {time}";

        /**
         * Reason clause APPENDED to {@link #decline} only when the provider actually left a note
         * (Phase 25.9 — {@code comment} became optional on {@code /decline}/{@code /not-complete},
         * reversing Phase 25.2's "required" decision from earlier the same day). Kept as its own
         * template, not baked into {@link #decline}, so a null/blank note never leaves a dangling
         * "Причина: " label with nothing after it — {@code NotificationService} only substitutes
         * and appends this fragment when a non-blank, truncated comment exists. Placeholder:
         * {@code {comment}} — the provider's decline note, truncated to ~120 chars by
         * {@code NotificationService} before substitution (Cyrillic SMS segments are ~70 chars;
         * 120 chars keeps this to two segments).
         */
        private String declineReason = "\n\nПричина: {comment}";

        public String getDecline() {
            return decline;
        }

        public void setDecline(String decline) {
            this.decline = decline;
        }

        public String getDeclineReason() {
            return declineReason;
        }

        public void setDeclineReason(String declineReason) {
            this.declineReason = declineReason;
        }

        /**
         * Salon-closure template for a GUEST (LINK) visit (Phase 269/293). Like {@link #decline},
         * this is the ONLY channel that reaches a guest — they have no account for email/push.
         * Placeholders: {@code {subject}} (the service name for a single-service visit, or the
         * «N послуг(и)» numeral phrase for a multi-service one — resolved in Java by
         * {@code NotificationService#bookedSubject}, never here), {@code {date}}, {@code {time}}.
         * Carries no reason clause and no client name — the SALON closed, there is no provider
         * note to attach (D10: {@code BookingVisit} exposes no note accessor at all).
         */
        private String salonClosed =
                "Beautica: На жаль, салон закрився і більше не приймає записи.\n"
                        + "Ваше бронювання на {subject}\n"
                        + "{date} о {time} скасовано.";

        public String getSalonClosed() {
            return salonClosed;
        }

        public void setSalonClosed(String salonClosed) {
            this.salonClosed = salonClosed;
        }

        /**
         * Master-removal template for a GUEST (LINK) visit (Phase 298). Like {@link #salonClosed},
         * this is the ONLY channel that reaches a guest — they have no account for email/push.
         * Placeholders: {@code {subject}} (resolved in Java by {@code
         * NotificationService#bookedSubject}, never here), {@code {date}}, {@code {time}}.
         * Deliberately NOT a reuse of {@link #salonClosed}'s copy — the salon did not close, only
         * the master left it. Carries no reason clause and no client name — there is no provider
         * note to attach (D10: {@code BookingVisit} exposes no note accessor at all).
         */
        private String masterRemoved =
                "Beautica: На жаль, майстер більше не працює в цьому салоні.\n"
                        + "Ваше бронювання на {subject}\n"
                        + "{date} о {time} скасовано.";

        public String getMasterRemoved() {
            return masterRemoved;
        }

        public void setMasterRemoved(String masterRemoved) {
            this.masterRemoved = masterRemoved;
        }
    }
}
