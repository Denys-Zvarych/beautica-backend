package com.beautica.booking.event;

import java.util.List;

/**
 * Carries the guest 24h reminder SMS messages that {@code BookingReminderJob} committed to sending,
 * from the job's transaction to {@link GuestReminderDispatcher} (which runs strictly after that
 * transaction commits).
 *
 * <p><b>Fully-rendered primitives only — deliberately.</b> Every field is a {@code String} that was
 * produced INSIDE the job's transaction. The event carries no {@code Booking}, no {@code Master} and
 * no {@code MasterServiceAssignment}, because the listener runs after the transaction has completed
 * and the Hibernate session is closed: dereferencing a lazy proxy there
 * ({@code booking.getMasterService().getServiceDefinition().getName()}, {@code booking.getMaster()
 * .getUser()} — both traversed by the reminder template) would throw
 * {@code LazyInitializationException} in production while passing every unit test, which mocks the
 * entities eagerly. Rendering inside the transaction removes that failure mode structurally rather
 * than relying on a fetch graph staying correct.
 *
 * <p><b>PII.</b> {@code phoneE164} and {@code text} are guest personal data (phone, appointment
 * details). They live in memory only for the hop to the dispatcher and are never logged and never
 * written to the notification outbox (Anti-Bug §I).
 *
 * <p><b>Both records override {@code toString()} to redact.</b> This is not defensive tidiness — it
 * closes a live leak path that no ordinary test surfaces, because the logging is done by FRAMEWORK code
 * we never call. Anything escaping the listener reaches {@code TaskUtils.LoggingErrorHandler} / Spring's
 * event-multicaster error handling, which logs {@code "Resolved arguments: [value=<payload>]"} — i.e. the
 * record's auto-generated {@code toString()}, which prints every component verbatim. That would put a
 * guest's raw phone and their full appointment text into an ERROR line, at whatever retention the log
 * sink has.
 *
 * @param reminders one entry per reminder to deliver — one per legacy single guest booking, and
 *                  exactly one per multi-service guest visit (BE-7 dedup happens in the job)
 */
public record GuestRemindersDueEvent(List<GuestReminderSms> reminders) {

    public GuestRemindersDueEvent {
        reminders = List.copyOf(reminders);
    }

    /** Size only — never the recipients. See the PII note on the type. */
    @Override
    public String toString() {
        return "GuestRemindersDueEvent[reminders=" + reminders.size() + " redacted]";
    }

    /**
     * One rendered reminder: the recipient and the finished message body.
     *
     * @param phoneE164 recipient phone in E.164 form — never logged
     * @param text      the fully-rendered SMS body — never logged
     */
    public record GuestReminderSms(String phoneE164, String text) {

        /**
         * Redacted: neither the phone nor the message body may ever reach a log line. Carries no length
         * and no prefix either — a body length next to a phone-shaped fragment is still linkable data.
         */
        @Override
        public String toString() {
            return "GuestReminderSms[redacted]";
        }
    }
}
