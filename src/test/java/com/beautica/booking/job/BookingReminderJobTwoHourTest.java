package com.beautica.booking.job;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.event.GuestRemindersDueEvent;
import com.beautica.booking.event.GuestRemindersDueEvent.ReminderType;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.AuditableEntity;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 413 — {@link BookingReminderJob#sweepTwoHour()}: window, Kyiv night rule (incl. DST days), short-notice
 * skip, single claim across overlapping ticks. Clocks are pinned instants; the Kyiv conversion is explicit, so the
 * JVM default time zone cannot mask a bug.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingReminderJob — 2h-before reminder sweep (phase 413)")
class BookingReminderJobTwoHourTest {

    /** 12:00 Kyiv (EEST, UTC+3). */
    private static final Instant MIDDAY_KYIV = Instant.parse("2026-06-01T09:00:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private ApplicationEventPublisher events;

    private BookingSmsProperties properties;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        properties = new BookingSmsProperties();
        properties.getReminder2h().setEnabled(true);
        meters = new SimpleMeterRegistry();
        lenient().when(bookingRepository.claimTwoHourRemindersByIds(any()))
                .thenAnswer(inv -> new ArrayList<>((Collection<UUID>) inv.getArgument(0)));
        lenient().when(bookingRepository.claimTwoHourVisitRemindersByAppointmentIds(any()))
                .thenAnswer(inv -> new ArrayList<>((Collection<UUID>) inv.getArgument(0)));
    }

    @Test
    @DisplayName("audit-fix: the 2h cron is offset from the top of the hour (:05/:20/:35/:50)")
    void should_offsetCronFromTopOfHour_when_scheduled() throws NoSuchMethodException {
        org.springframework.scheduling.annotation.Scheduled scheduled = BookingReminderJob.class
                .getMethod("sweepTwoHour").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 5/15 * * * *");
    }

    @Test
    @DisplayName("flag off → no query, no claim, no event")
    void should_doNothing_when_flagOff() {
        properties.getReminder2h().setEnabled(false);

        jobAt(MIDDAY_KYIV).sweepTwoHour();

        verifyNoInteractions(bookingRepository, events);
    }

    @Test
    @DisplayName("window = [now+1h45, now+2h15): the query gets exactly those bounds")
    void should_queryHalfOpenWindow_when_daytime() {
        when(bookingRepository.findGuestBookingsForTwoHourReminder(any(), any(), any())).thenReturn(List.of());

        jobAt(MIDDAY_KYIV).sweepTwoHour();

        OffsetDateTime now = OffsetDateTime.ofInstant(MIDDAY_KYIV, ZoneOffset.UTC);
        verify(bookingRepository).findGuestBookingsForTwoHourReminder(
                now.plus(Duration.ofMinutes(105)), now.plus(Duration.ofMinutes(135)), Limit.of(500));
    }

    @ParameterizedTest(name = "{0} → night={1}")
    @CsvSource({
            // ordinary summer day (EEST, UTC+3)
            "2026-06-01T17:59:00Z, false",  // 20:59 Kyiv
            "2026-06-01T18:00:00Z, true",   // 21:00
            "2026-06-01T05:59:00Z, true",   // 08:59
            "2026-06-01T06:00:00Z, false",  // 09:00
            // DST start 2027-03-28 (03:00 → 04:00): 09:00 EEST = 06:00Z
            "2027-03-28T05:59:00Z, true",
            "2027-03-28T06:00:00Z, false",
            // DST end 2026-10-25 (04:00 → 03:00): 09:00 EET = 07:00Z, 21:00 EET = 19:00Z
            "2026-10-25T06:59:00Z, true",
            "2026-10-25T07:00:00Z, false",
            "2026-10-25T18:59:00Z, false",
            "2026-10-25T19:00:00Z, true"
    })
    @DisplayName("Kyiv night is [21:00, 09:00) in wall-clock time, DST-aware")
    void should_classifyKyivNight_when_instantGiven(String instant, boolean night) {
        assertThat(BookingReminderJob.isKyivNight(Instant.parse(instant))).isEqualTo(night);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"2026-06-01T18:00:00Z", "2026-06-01T05:59:00Z", "2026-06-01T23:30:00Z"})
    @DisplayName("at night the sweep neither selects nor claims nor publishes (skip, not defer)")
    void should_selectNothing_when_kyivNight(String instant) {
        jobAt(Instant.parse(instant)).sweepTwoHour();

        verifyNoInteractions(bookingRepository, events);
        assertThat(meters.counter(BookingReminderJob.REMINDER_METRIC, "type", "h2", "outcome",
                BookingReminderJob.OUTCOME_SKIPPED_NIGHT).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("20:59 Kyiv still sends")
    void should_send_when_justBeforeNight() {
        Instant now = Instant.parse("2026-06-01T17:59:00Z");
        Booking booking = guestBooking("+380501111111", now.plus(Duration.ofHours(2)), now.minus(Duration.ofDays(1)));
        when(bookingRepository.findGuestBookingsForTwoHourReminder(any(), any(), any())).thenReturn(List.of(booking));

        jobAt(now).sweepTwoHour();

        assertThat(publishedEvent().reminders()).hasSize(1);
    }

    @Test
    @DisplayName("publishes ONE H2 event rendered with the 2h template, claimed through the lock_timeout wrapper")
    void should_publishH2EventWithTwoHourTemplate_when_bookingDue() {
        Booking booking = guestBooking("+380501111111", MIDDAY_KYIV.plus(Duration.ofHours(2)),
                MIDDAY_KYIV.minus(Duration.ofDays(1)));
        when(bookingRepository.findGuestBookingsForTwoHourReminder(any(), any(), any())).thenReturn(List.of(booking));

        jobAt(MIDDAY_KYIV).sweepTwoHour();

        GuestRemindersDueEvent event = publishedEvent();
        assertThat(event.type()).isEqualTo(ReminderType.H2);
        assertThat(event.reminders()).singleElement().satisfies(r -> {
            assertThat(r.phoneE164()).isEqualTo("+380501111111");
            assertThat(r.text()).isEqualTo("Beautica: Нагадуємо!\nМанікюр у Марія Левченко\nСьогодні о 14:00");
        });
        verify(bookingRepository).claimTwoHourRemindersByIds(any());
        verify(bookingRepository, never()).claimRemindersByIds(any());
    }

    @Test
    @DisplayName("short notice (created < min-lead before start) → not claimed, not sent, counted")
    void should_skipAndNotClaim_when_shortNotice() {
        Instant startsAt = MIDDAY_KYIV.plus(Duration.ofHours(2));
        Booking shortNotice = guestBooking("+380501111111", startsAt, startsAt.minus(Duration.ofMinutes(179)));
        when(bookingRepository.findGuestBookingsForTwoHourReminder(any(), any(), any())).thenReturn(List.of(shortNotice));

        jobAt(MIDDAY_KYIV).sweepTwoHour();

        verify(bookingRepository, never()).claimTwoHourRemindersByIds(any());
        verifyNoInteractions(events);
        assertThat(meters.counter(BookingReminderJob.REMINDER_METRIC, "type", "h2", "outcome",
                BookingReminderJob.OUTCOME_SKIPPED_SHORT_NOTICE).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("created exactly min-lead before start is NOT short notice")
    void should_send_when_createdExactlyMinLeadBefore() {
        Instant startsAt = MIDDAY_KYIV.plus(Duration.ofHours(2));
        Booking booking = guestBooking("+380501111111", startsAt, startsAt.minus(Duration.ofHours(3)));
        when(bookingRepository.findGuestBookingsForTwoHourReminder(any(), any(), any())).thenReturn(List.of(booking));

        jobAt(MIDDAY_KYIV).sweepTwoHour();

        assertThat(publishedEvent().reminders()).hasSize(1);
    }

    @Test
    @DisplayName("a second overlapping tick that loses the claim publishes nothing (claimed once)")
    void should_publishNothing_when_claimLost() {
        Booking booking = guestBooking("+380501111111", MIDDAY_KYIV.plus(Duration.ofHours(2)),
                MIDDAY_KYIV.minus(Duration.ofDays(1)));
        when(bookingRepository.findGuestBookingsForTwoHourReminder(any(), any(), any())).thenReturn(List.of(booking));
        doReturn(List.of()).when(bookingRepository).claimTwoHourRemindersByIds(any());

        jobAt(MIDDAY_KYIV).sweepTwoHour();

        verifyNoInteractions(events);
    }

    private BookingReminderJob jobAt(Instant now) {
        return new BookingReminderJob(bookingRepository, properties, events, Clock.fixed(now, ZoneOffset.UTC), meters);
    }

    private GuestRemindersDueEvent publishedEvent() {
        ArgumentCaptor<GuestRemindersDueEvent> captor = ArgumentCaptor.forClass(GuestRemindersDueEvent.class);
        verify(events).publishEvent(captor.capture());
        return captor.getValue();
    }

    private static Booking guestBooking(String phone, Instant startsAt, Instant createdAt) {
        User user = new User("m@beautica.test", "x", com.beautica.auth.Role.SALON_MASTER, "Марія", "Левченко", null);
        Master master = Master.builder().user(user).isActive(true).build();
        ServiceDefinition def = ServiceDefinition.builder()
                .name("Манікюр").baseDurationMinutes(60).bufferMinutesAfter(0)
                .basePrice(new BigDecimal("350.00")).build();
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .master(master).serviceDefinition(def).isActive(true).build();
        OffsetDateTime start = OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC);
        Booking booking = Booking.guestBooking(master, msa, null, start, start.plusHours(1),
                new BigDecimal("350.00"), null, 60, 0, "Олена", "Коваль", phone, null);
        setCreatedAt(booking, createdAt);
        return booking;
    }

    private static void setCreatedAt(Booking booking, Instant createdAt) {
        try {
            Field field = AuditableEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(booking, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
