package com.beautica.booking.retention;

import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.GuestBookingSubject;
import com.beautica.booking.service.BookingCancellationService;
import com.beautica.booking.service.GuestVisitCancellationService;
import com.beautica.moderation.service.ClientBlockService;
import com.beautica.notification.telegram.link.TelegramLinkRetentionService;
import com.beautica.review.invite.service.GuestReviewRetentionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Phase 398 QA LOW — {@link GuestPiiAnonymisationService#eraseByPhone} renames the phone's GUEST blocks (D7), with
 * the canonical E.164 phone and the anonymised name, inside the same erase.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuestPiiAnonymisationService — eraseByPhone anonymises guest blocks (phase 398 D7)")
class GuestPiiAnonymisationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private BookingCancellationService bookingCancellationService;
    @Mock private GuestVisitCancellationService guestVisitCancellationService;
    @Mock private PhoneOtpService phoneOtpService;
    @Mock private ClientBlockService clientBlockService;
    @Mock private GuestReviewRetentionService guestReviewRetentionService;
    @Mock private TelegramLinkRetentionService telegramLinkRetentionService;
    @Mock private PlatformTransactionManager transactionManager;

    private GuestPiiAnonymisationService service;

    @BeforeEach
    void setUp() {
        service = new GuestPiiAnonymisationService(bookingRepository, appointmentRepository,
                bookingCancellationService, guestVisitCancellationService, phoneOtpService, clientBlockService,
                guestReviewRetentionService, telegramLinkRetentionService, null, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
        lenient().when(telegramLinkRetentionService.eraseForPhone(anyString()))
                .thenReturn(new TelegramLinkRetentionService.Erasure(0, 0));
    }

    @Test
    @DisplayName("phase 409 audit-fix: a claim batch aborted by a lock timeout ends the claim loop; the header sweeps "
            + "still run")
    void should_runSweeps_when_claimBatchHitsLockTimeout() {
        when(bookingRepository.anonymiseDueGuestBookingsWithPhones(any(), any(), anyInt()))
                .thenThrow(new CannotAcquireLockException("55P03"));

        GuestPiiAnonymisationResult result = service.anonymiseDue(NOW, 10, 5);

        assertThat(result.bookingsAnonymised()).isZero();
        verify(bookingRepository, times(1)).anonymiseDueGuestBookingsWithPhones(any(), any(), anyInt());
        verify(appointmentRepository).anonymiseEndedGuestAppointments(any(), any(), any(), anyInt());
        verify(appointmentRepository).anonymiseLeftoverGuestAppointments(any(), any(), anyInt());
    }

    @Test
    @DisplayName("phase 409 D3: erase deletes the canonical phone's Telegram link + tokens, after every other step")
    void should_eraseTelegramDataOfCanonicalPhoneLast_when_erasingByPhone() {
        service.eraseByPhone("050 123 45 67", "SUP-3");

        InOrder order = inOrder(guestReviewRetentionService, telegramLinkRetentionService);
        order.verify(guestReviewRetentionService).expireSentInvitesOfPhone("+380501234567", NOW);
        order.verify(telegramLinkRetentionService).eraseForPhone("+380501234567");
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

    @Test
    @DisplayName("phase 402: erase skips the invites of the item's visit, the erased visit and the single booking, "
            + "then anonymises their reviews — after the block rename")
    void should_skipInvitesAndAnonymiseReviewsLast_when_erasingByPhone() {
        UUID item = UUID.randomUUID();
        UUID itemVisit = UUID.randomUUID();
        UUID single = UUID.randomUUID();
        UUID erasedVisit = UUID.randomUUID();
        String phone = "+380501234567";
        when(bookingRepository.anonymiseGuestBookingsByPhone(phone, GuestPii.ANONYMISED_NAME))
                .thenReturn(List.of(item, single));
        when(appointmentRepository.anonymiseGuestAppointmentsByPhone(phone, GuestPii.ANONYMISED_NAME))
                .thenReturn(List.of(erasedVisit));
        when(bookingRepository.findGuestBookingSubjects(List.of(item, single)))
                .thenReturn(List.of(subject(item, itemVisit), subject(single, null)));

        service.eraseByPhone(phone, null);

        InOrder order = inOrder(clientBlockService, guestReviewRetentionService);
        order.verify(clientBlockService).anonymiseGuestBlocks(phone, GuestPii.ANONYMISED_NAME);
        order.verify(guestReviewRetentionService).skipScheduledInvites(Set.of(erasedVisit, itemVisit), List.of(single));
        order.verify(guestReviewRetentionService).anonymiseReviewAuthors(List.of(item, single));
        order.verify(guestReviewRetentionService).expireSentInvitesOfPhone(phone, NOW);
    }

    @Test
    @DisplayName("phase 402 audit-fix: erase expires the canonical phone's SENT review invites at the clock's now")
    void should_expireSentInvitesOfCanonicalPhone_when_erasingByPhone() {
        service.eraseByPhone("050 123 45 67", "SUP-2");

        verify(guestReviewRetentionService).expireSentInvitesOfPhone("+380501234567", NOW);
    }

    @Test
    @DisplayName("phase 402: an erase that anonymised no booking never reads booking subjects")
    void should_notReadSubjects_when_eraseAnonymisedNoBooking() {
        service.eraseByPhone("+380501234567", null);

        verify(bookingRepository, never()).findGuestBookingSubjects(anyCollection());
        verify(guestReviewRetentionService).skipScheduledInvites(Set.of(), List.of());
        verify(guestReviewRetentionService).anonymiseReviewAuthors(List.of());
    }

    private static GuestBookingSubject subject(UUID bookingId, UUID appointmentId) {
        return new GuestBookingSubject() {
            @Override
            public UUID getBookingId() {
                return bookingId;
            }

            @Override
            public UUID getAppointmentId() {
                return appointmentId;
            }
        };
    }
}
