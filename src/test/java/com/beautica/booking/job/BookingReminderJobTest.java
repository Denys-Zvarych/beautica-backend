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

    private Booking guestBooking(String phone) {
        User user = new User("m@beautica.test", "x", com.beautica.auth.Role.SALON_MASTER, "Марія", "Левченко", null);
        Master master = Master.builder().user(user).isActive(true).build();
        ServiceDefinition def = ServiceDefinition.builder()
                .name("Манікюр").baseDurationMinutes(60).bufferMinutesAfter(0)
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
