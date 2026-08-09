package com.beautica.booking.job;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.sms.SmsService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingReminderJob — hourly 24h reminder sweep")
class BookingReminderJobTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private SmsService smsService;

    private BookingReminderJob job;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(OffsetDateTime.parse("2026-06-01T10:00:00Z").toInstant(), ZoneOffset.UTC);
        job = new BookingReminderJob(bookingRepository, smsService, new BookingSmsProperties(), clock);
    }

    @Test
    @DisplayName("should send one SMS per due booking, mark reminderSent=true, and bulk-save")
    void should_sendRemindersAndMark_when_bookingsDue() {
        Booking b1 = guestBooking("+380501111111");
        Booking b2 = guestBooking("+380502222222");
        when(bookingRepository.findGuestBookingsForReminder(any(), any())).thenReturn(List.of(b1, b2));

        job.sendReminders();

        verify(smsService).send(eq("+380501111111"), anyString());
        verify(smsService).send(eq("+380502222222"), anyString());
        assertThat(b1.isReminderSent()).isTrue();
        assertThat(b2.isReminderSent()).isTrue();
        verify(bookingRepository).saveAll(List.of(b1, b2));
    }

    @Test
    @DisplayName("should send no SMS and not save when nothing is due (already-reminded excluded by query)")
    void should_doNothing_when_noBookingsDue() {
        when(bookingRepository.findGuestBookingsForReminder(any(), any())).thenReturn(List.of());

        job.sendReminders();

        verifyNoInteractions(smsService);
        verify(bookingRepository, times(0)).saveAll(any());
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
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        job.sendReminders();

        verify(smsService).send(eq("+380501111111"), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("the literal braces from the DATA must survive as text, never be expanded")
                .contains("Манікюр {time}")
                .as("the real time appears exactly once, where the TEMPLATE puts it — a provider must "
                        + "not be able to fabricate a second appointment time")
                .containsOnlyOnce("10:00");
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
