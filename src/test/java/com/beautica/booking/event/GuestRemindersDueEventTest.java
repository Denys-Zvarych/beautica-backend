package com.beautica.booking.event;

import com.beautica.booking.event.GuestRemindersDueEvent.GuestReminderSms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that neither event record can print guest PII, and that the payload is immutable in transit.
 *
 * <p><b>Why {@code toString()} needs its own test.</b> The leak path is a log line this codebase never
 * writes. If anything escapes {@code GuestReminderDispatcher} — the listener, or the executor's own
 * error handling — the throwable is handled by framework code
 * ({@code TaskUtils.LoggingErrorHandler}, Spring's event multicaster) that logs the invocation's
 * resolved arguments: {@code "Resolved arguments: [value=<payload>]"}. That string is the record's
 * {@code toString()}. Auto-generated record {@code toString()} prints every component verbatim, so the
 * default would have put a guest's raw phone number and their full appointment text into an ERROR line
 * at whatever retention the sink has (Anti-Bug §I). No test that drives the dispatcher normally can
 * surface this, because on the happy path that framework code never runs — so it is pinned directly.
 */
@DisplayName("GuestRemindersDueEvent — PII redaction and payload immutability")
class GuestRemindersDueEventTest {

    private static final String PHONE = "+380501234567";
    private static final String BODY = "Нагадуємо: Манікюр у Марія Левченко завтра о 10:00";

    @Test
    @DisplayName("GuestReminderSms.toString() must reveal neither the phone nor the message body")
    void should_redactPhoneAndBody_when_aReminderIsStringified() {
        String rendered = new GuestReminderSms(PHONE, BODY).toString();

        assertThat(rendered)
                .as("record toString() prints every component verbatim by default, and framework error "
                        + "handling logs exactly this string, actual=%s", rendered)
                .doesNotContain(PHONE)
                .doesNotContain("380501234567")
                .doesNotContain(BODY)
                .doesNotContain("Манікюр");
    }

    @Test
    @DisplayName("GuestRemindersDueEvent.toString() must reveal no recipient, only how many there were")
    void should_redactEveryRecipient_when_theEventIsStringified() {
        String rendered = new GuestRemindersDueEvent(List.of(new GuestReminderSms(PHONE, BODY))).toString();

        assertThat(rendered)
                .as("the whole batch is one framework log argument, actual=%s", rendered)
                .doesNotContain(PHONE)
                .doesNotContain(BODY)
                .contains("1");
    }

    /**
     * Reproduces the exact shape of the framework leak: {@code LoggingErrorHandler} formats the resolved
     * argument array, so the nested {@code List<GuestReminderSms>} would be walked element by element if
     * the event's own {@code toString()} ever went back to the generated one.
     */
    @Test
    @DisplayName("stringifying the event as a framework argument array must not walk into the recipients")
    void should_leakNothing_when_theEventIsFormattedAsALogArgument() {
        GuestRemindersDueEvent event = new GuestRemindersDueEvent(List.of(
                new GuestReminderSms(PHONE, BODY),
                new GuestReminderSms("+380509999999", BODY)));

        String logLine = "Resolved arguments: " + Arrays.toString(new Object[] {event});

        assertThat(logLine)
                .as("this is the literal line TaskUtils.LoggingErrorHandler emits, actual=%s", logLine)
                .doesNotContain(PHONE)
                .doesNotContain("+380509999999")
                .doesNotContain(BODY);
    }

    @Test
    @DisplayName("the reminder list must be copied on construction so the publisher cannot mutate it in flight")
    void should_copyTheReminderList_when_theEventIsConstructed() {
        List<GuestReminderSms> mutable = new java.util.ArrayList<>();
        mutable.add(new GuestReminderSms(PHONE, BODY));
        GuestRemindersDueEvent event = new GuestRemindersDueEvent(mutable);

        mutable.clear();

        assertThat(event.reminders())
                .as("the event is consumed after commit, on another thread — a live view of the job's "
                        + "list would be a data race")
                .hasSize(1);
    }
}
