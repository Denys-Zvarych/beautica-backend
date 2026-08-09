package com.beautica.booking.job;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.event.GuestRemindersDueEvent;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.sms.SmsService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingReminderJob — hourly 24h reminder sweep")
class BookingReminderJobTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private ApplicationEventPublisher events;

    private BookingReminderJob job;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger jobLogger;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(OffsetDateTime.parse("2026-06-01T10:00:00Z").toInstant(), ZoneOffset.UTC);
        job = new BookingReminderJob(bookingRepository, new BookingSmsProperties(), events, clock);
        logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger = (Logger) LoggerFactory.getLogger(BookingReminderJob.class);
        jobLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("should publish one reminder per due booking and mark reminderSent=true")
    void should_publishRemindersAndMark_when_bookingsDue() {
        Booking b1 = guestBooking("+380501111111");
        Booking b2 = guestBooking("+380502222222");
        when(bookingRepository.findGuestBookingsForReminder(any(), any())).thenReturn(List.of(b1, b2));

        job.sendReminders();

        assertThat(publishedEvent().reminders())
                .extracting(GuestRemindersDueEvent.GuestReminderSms::phoneE164)
                .containsExactly("+380501111111", "+380502222222");
        assertThat(b1.isReminderSent()).isTrue();
        assertThat(b2.isReminderSent()).isTrue();
    }

    /**
     * The rows come from {@code findGuestBookingsForReminder} inside this same {@code @Transactional}
     * method, so they are managed and Hibernate's dirty check flushes {@code reminderSent} at commit. The
     * explicit {@code saveAll} only added a merge pass over entities already in the persistence context.
     * Asserting its ABSENCE (rather than deleting the old assertion silently) keeps the reasoning visible:
     * re-adding it must be a deliberate act, and the flag assertions above still pin the behaviour that
     * matters.
     */
    @Test
    @DisplayName("should not re-save managed rows — dirty checking flushes reminderSent at commit")
    void should_notCallSaveAll_when_bookingsDue() {
        when(bookingRepository.findGuestBookingsForReminder(any(), any()))
                .thenReturn(List.of(guestBooking("+380501111111")));

        job.sendReminders();

        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("should publish nothing and not save when nothing is due (already-reminded excluded by query)")
    void should_doNothing_when_noBookingsDue() {
        when(bookingRepository.findGuestBookingsForReminder(any(), any())).thenReturn(List.of());

        job.sendReminders();

        verifyNoInteractions(events);
        verify(bookingRepository, times(0)).saveAll(any());
    }

    /**
     * The audit trail after a lost batch. {@code due.size()} alone cannot distinguish a whole batch from
     * a half-delivered one: BE-7 dedup means an N-item visit contributes N rows but exactly ONE reminder,
     * and a render failure silently drops a reminder without moving the row count either. So a sweep that
     * dispatched 2 of 3 and one that dispatched 3 of 3 logged the identical line — and since the flags are
     * committed regardless, the log was the only surviving evidence. This fixture is deliberately shaped so
     * the two numbers DIVERGE (3 rows → 2 reminders); asserting a row count here would pass on either.
     */
    @Test
    @DisplayName("the sweep must log the DISPATCHED reminder count, not the dedup-inflated row count")
    void should_logTheDispatchedReminderCount_when_aVisitDedupesRows() {
        UUID appointmentId = UUID.randomUUID();
        Booking single = guestBooking("+380501111111");
        Booking visitItem1 = guestBooking("+380502222222");
        Booking visitItem2 = guestBooking("+380502222222");
        visitItem1.setAppointment(Appointment.builder().id(appointmentId).build());
        visitItem2.setAppointment(Appointment.builder().id(appointmentId).build());
        when(bookingRepository.findGuestBookingsForReminder(any(), any()))
                .thenReturn(List.of(single, visitItem1, visitItem2));

        job.sendReminders();

        assertThat(publishedEvent().reminders())
                .as("fixture sanity: 3 due rows must collapse to 2 reminders, or the counts cannot diverge")
                .hasSize(2);
        assertThat(logAppender.list)
                .as("a line reporting only rows reads identically whether the batch went out whole or not")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("2 dispatched")
                        .contains("3 due rows"));
    }

    /**
     * Pins backend-perf MEDIUM at its structural seam: the job no longer holds an {@link SmsService} at
     * all, so no blocking Turbosms call can exist inside its {@code @Transactional} sweep whatever a
     * future edit does to the loop body. Delivery is the after-commit dispatcher's job.
     */
    @Test
    @DisplayName("the transactional sweep must hold no SMS sender — sending cannot happen inside the transaction")
    void should_holdNoSmsSender_when_theJobIsWired() {
        assertThat(BookingReminderJob.class.getDeclaredFields())
                .as("an SmsService field would put a blocking provider call back inside the transaction")
                .extracting(Field::getType)
                .doesNotContain(SmsService.class);
    }

    /**
     * Pins the at-most-once contract. The flag must already be set when the event is published, because
     * the publish rides the SAME commit: the dispatcher can therefore only ever attempt a send for a
     * booking durably marked as reminded. Flag the other way round (publish, then set) and a crash
     * between commit and dispatch re-sends the whole batch on the next tick.
     */
    @Test
    @DisplayName("reminderSent must already be true when the batch is handed off, so a lost dispatch never duplicates")
    void should_markReminderSentBeforePublishing_when_bookingsDue() {
        Booking due = guestBooking("+380501111111");
        when(bookingRepository.findGuestBookingsForReminder(any(), any())).thenReturn(List.of(due));
        List<Boolean> flagAtPublish = new ArrayList<>();
        doAnswer(inv -> flagAtPublish.add(due.isReminderSent())).when(events).publishEvent(any(Object.class));

        job.sendReminders();

        assertThat(flagAtPublish).containsExactly(true);
    }

    @Test
    @DisplayName("the reminder SMS must not expand a {time} placeholder that arrived inside the service name")
    void should_notExpandAPlaceholderThatArrivedAsAValue_when_aServiceNameContainsOne() {
        // buildReminderSms substituted {serviceName} BEFORE {time} with chained String.replace, so
        // the later replace re-scanned the name it had just written in. The service name is
        // provider-controlled free text, so a provider naming a service "Манікюр {time}" rendered a
        // SECOND, provider-positioned time inside copy the guest reads as platform text — and SMS is
        // the only channel a guest has.
        Booking due = guestBooking("+380501111111", "Манікюр {time}");
        when(bookingRepository.findGuestBookingsForReminder(any(), any())).thenReturn(List.of(due));

        job.sendReminders();

        assertThat(publishedEvent().reminders().get(0).text())
                .as("the literal braces from the DATA must survive as text, never be expanded")
                .contains("Манікюр {time}")
                .as("the real time appears exactly once, where the TEMPLATE puts it — a provider must "
                        + "not be able to fabricate a second appointment time")
                .containsOnlyOnce("10:00");
    }

    private GuestRemindersDueEvent publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(GuestRemindersDueEvent.class);
        return (GuestRemindersDueEvent) captor.getValue();
    }

    private Booking guestBooking(String phone) {
        return guestBooking(phone, "Манікюр");
    }

    private Booking guestBooking(String phone, String serviceName) {
        User user = new User("m@beautica.test", "x", com.beautica.auth.Role.SALON_MASTER, "Марія", "Левченко", null);
        Master master = Master.builder().user(user).isActive(true).build();
        ServiceDefinition def = ServiceDefinition.builder()
                .name(serviceName).baseDurationMinutes(60).bufferMinutesAfter(0)
                .basePrice(new BigDecimal("350.00")).build();
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .master(master).serviceDefinition(def).isActive(true).build();
        return Booking.guestBooking(
                master, msa, null,
                OffsetDateTime.parse("2026-06-02T10:00:00+03:00"),
                OffsetDateTime.parse("2026-06-02T11:00:00+03:00"),
                new BigDecimal("350.00"), null, 60, 0, "Олена", "Коваль", phone);
    }
}
