package com.beautica.booking.retention;

import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.BookingCancellationService;
import com.beautica.booking.service.GuestVisitCancellationService;
import com.beautica.moderation.service.ClientBlockService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

/**
 * Phase 398 QA LOW — {@link GuestPiiAnonymisationService#eraseByPhone} renames the phone's GUEST blocks (D7), with
 * the canonical E.164 phone and the anonymised name, inside the same erase.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuestPiiAnonymisationService — eraseByPhone anonymises guest blocks (phase 398 D7)")
class GuestPiiAnonymisationServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private BookingCancellationService bookingCancellationService;
    @Mock private GuestVisitCancellationService guestVisitCancellationService;
    @Mock private PhoneOtpService phoneOtpService;
    @Mock private ClientBlockService clientBlockService;
    @Mock private PlatformTransactionManager transactionManager;

    private GuestPiiAnonymisationService service;

    @BeforeEach
    void setUp() {
        service = new GuestPiiAnonymisationService(bookingRepository, appointmentRepository,
                bookingCancellationService, guestVisitCancellationService, phoneOtpService, clientBlockService,
                null, new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC), transactionManager);
    }

    @Test
    void should_anonymiseGuestBlocksWithCanonicalPhone_when_erasingByPhone() {
        service.eraseByPhone("050 123 45 67", "SUP-1");

        verify(clientBlockService).anonymiseGuestBlocks("+380501234567", GuestPii.ANONYMISED_NAME);
    }

    @Test
    void should_anonymiseBlocksAfterBookingWipe_when_erasingByPhone() {
        service.eraseByPhone("+380501234567", null);

        InOrder order = inOrder(bookingRepository, phoneOtpService, clientBlockService);
        order.verify(bookingRepository).anonymiseGuestBookingsByPhone("+380501234567", GuestPii.ANONYMISED_NAME);
        order.verify(phoneOtpService).deleteAllForPhone("+380501234567");
        order.verify(clientBlockService).anonymiseGuestBlocks("+380501234567", GuestPii.ANONYMISED_NAME);
    }
}
