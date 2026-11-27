package com.beautica.client.service;

import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.AttachReason;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.client.event.ClientBookingsAttachedEvent;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientPhoneAttachService — B527")
class ClientPhoneAttachServiceTest {

    private static final String PHONE = "+380671234567";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Mock private com.beautica.client.provider.service.ClientListRemovalService clientListRemovalService;
    @Mock private com.beautica.review.service.ClientRatingSubjectService clientRatingSubjectService;

    private ClientPhoneAttachService service;

    @BeforeEach
    void setUp() {
        service = new ClientPhoneAttachService(bookingRepository, appointmentRepository, userRepository,
                eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager, Runnable::run,
                clientListRemovalService, clientRatingSubjectService);
    }

    private static User account() {
        return mock(User.class);
    }

    private static User accountWithId() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        return user;
    }

    @Test
    @DisplayName("should_takeThePerPhoneLockThenAttachHeadersThenRows_when_anAccountOwnsThePhone")
    void should_takeThePerPhoneLockThenAttachHeadersThenRows_when_anAccountOwnsThePhone() {
        User user = accountWithId();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(5);

        int attached = service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        assertThat(attached).isEqualTo(5);
        InOrder order = inOrder(bookingRepository, userRepository, appointmentRepository);
        order.verify(bookingRepository).acquireWalkInPhoneLock(PHONE);
        order.verify(userRepository).findAttachableClientByVerifiedPhone(PHONE);
        order.verify(appointmentRepository).attachGuestVisitsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        order.verify(bookingRepository).attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        verify(eventPublisher).publishEvent(new ClientBookingsAttachedEvent(user.getId()));
    }

    @Test
    @DisplayName("should_attachNothingAndPublishNothing_when_noActiveClientOwnsThePhone")
    void should_attachNothingAndPublishNothing_when_noActiveClientOwnsThePhone() {
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.empty());

        int attached = service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        assertThat(attached).isZero();
        verify(bookingRepository).acquireWalkInPhoneLock(PHONE);
        verify(appointmentRepository, never()).attachGuestVisitsByPhone(any(), any(), any(), any());
        verify(bookingRepository, never()).attachGuestRowsByPhone(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("should_notPublishTheCacheEvent_when_nothingWasAttached")
    void should_notPublishTheCacheEvent_when_nothingWasAttached() {
        User user = accountWithId();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(any(), any(), any(), any())).thenReturn(0);

        int attached = service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        assertThat(attached).isZero();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("B546 (REV-39) — after the bookings UPDATE, the attached bookings' phone-keyed ratings move to the account in the same transaction")
    void should_moveGuestRatingsAfterTheBookingsUpdate_when_guestRowsAreAttached() {
        User user = accountWithId();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(2);

        service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        InOrder order = inOrder(bookingRepository, clientRatingSubjectService);
        order.verify(bookingRepository).attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        order.verify(clientRatingSubjectService).moveGuestRatingsToAccount(user.getId());
    }

    @Test
    @DisplayName("B546 — nothing attached, no rating move statement")
    void should_notMoveGuestRatings_when_nothingWasAttached() {
        User user = accountWithId();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(any(), any(), any(), any())).thenReturn(0);

        service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        verify(clientRatingSubjectService, never()).moveGuestRatingsToAccount(any());
    }

    @Test
    @DisplayName("B564 — after the guest rows are attached, the attached phone is purged from list-removal rows")
    void should_purgeTheAttachedGuestPhone_when_guestRowsAreAttached() {
        User user = accountWithId();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(3);

        service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        InOrder order = inOrder(appointmentRepository, bookingRepository, clientListRemovalService);
        order.verify(appointmentRepository).attachGuestVisitsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        order.verify(bookingRepository).attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        order.verify(clientListRemovalService).purgeGuestPhones(java.util.List.of(PHONE));
        verify(clientListRemovalService).purgeGuestPhones(java.util.List.of(PHONE));
        org.mockito.Mockito.verifyNoMoreInteractions(clientListRemovalService);
    }

    @Test
    @DisplayName("B564 — the owner's phone is purged even when it had no open guest rows left to attach")
    void should_stillPurgeTheGuestPhone_when_theOwnerHasZeroRowsToAttach() {
        User user = accountWithId();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(0);

        service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        InOrder order = inOrder(bookingRepository, clientListRemovalService);
        order.verify(bookingRepository).attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        order.verify(clientListRemovalService).purgeGuestPhones(java.util.List.of(PHONE));
    }

    @Test
    @DisplayName("B564 — no list-removal purge when no active client owns the phone (early return, nothing attached)")
    void should_notPurgeTheGuestPhone_when_noActiveClientOwnsThePhone() {
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.empty());

        int attached = service.attachByPhone(PHONE, AttachReason.PHONE_VERIFIED);

        assertThat(attached).isZero();
        verify(clientListRemovalService, never()).purgeGuestPhones(any());
    }

    private static User verifiedAccount() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getPhoneNumber()).thenReturn(PHONE);
        return user;
    }

    private org.springframework.transaction.TransactionStatus stubNewTransaction() {
        org.springframework.transaction.TransactionStatus status =
                mock(org.springframework.transaction.TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        return status;
    }

    /** Runs {@code attachAfterCommit} inside a simulated caller transaction, then fires its afterCommit callbacks. */
    private static void attachInCommittedTransaction(ClientPhoneAttachService target, User user) {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            target.attachAfterCommit(user, AttachReason.PHONE_VERIFIED);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("INFO audit: attachAfterCommit with no active transaction throws IllegalStateException and attaches nothing")
    void should_throwIllegalState_when_attachAfterCommitCalledWithoutTransaction() {
        User user = mock(User.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.attachAfterCommit(user, AttachReason.PHONE_VERIFIED))
                .isInstanceOf(IllegalStateException.class);

        verify(bookingRepository, never()).acquireWalkInPhoneLock(any());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    @DisplayName("audit LOW: attachAfterCommit attaches in its own REQUIRES_NEW transaction after re-resolving the owner under the lock")
    void should_attachInANewTransactionAfterReResolvingTheOwner_when_attachAfterCommit() {
        User user = verifiedAccount();
        stubNewTransaction();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(2);

        attachInCommittedTransaction(service, user);

        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> def =
                org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(transactionManager).getTransaction(def.capture());
        assertThat(def.getValue().getPropagationBehavior())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        InOrder order = inOrder(bookingRepository, userRepository, appointmentRepository);
        order.verify(bookingRepository).acquireWalkInPhoneLock(PHONE);
        order.verify(userRepository).findAttachableClientByVerifiedPhone(PHONE);
        order.verify(appointmentRepository).attachGuestVisitsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        order.verify(bookingRepository).attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        verify(transactionManager).commit(any());
        verify(eventPublisher).publishEvent(new ClientBookingsAttachedEvent(user.getId()));
    }

    @Test
    @DisplayName("audit LOW: a lock timeout (55P03) during the attach is swallowed — the verification still succeeds")
    void should_notThrowAndRollBackTheAttachOnly_when_theAttachHitsALockTimeout() {
        User user = verifiedAccount();
        org.springframework.transaction.TransactionStatus status = stubNewTransaction();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(appointmentRepository.attachGuestVisitsByPhone(any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));

        org.assertj.core.api.Assertions.assertThatCode(
                () -> attachInCommittedTransaction(service, user)).doesNotThrowAnyException();

        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(clientListRemovalService, never()).purgeGuestPhones(any());
    }

    @Test
    @DisplayName("audit LOW: the phone moved to another account between commit and attach -> nothing attaches")
    void should_attachNothing_when_thePhoneNowBelongsToAnotherAccount() {
        User user = verifiedAccount();
        User other = mock(User.class);
        when(other.getId()).thenReturn(UUID.randomUUID());
        stubNewTransaction();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(other));

        attachInCommittedTransaction(service, user);

        verify(appointmentRepository, never()).attachGuestVisitsByPhone(any(), any(), any(), any());
        verify(bookingRepository, never()).attachGuestRowsByPhone(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(clientListRemovalService, never()).purgeGuestPhones(any());
    }

    @Test
    @DisplayName("audit LOW: the account was deactivated / lost the phone between commit and attach -> nothing attaches")
    void should_attachNothing_when_noAttachableAccountOwnsThePhoneAnymore() {
        User user = verifiedAccount();
        stubNewTransaction();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.empty());

        attachInCommittedTransaction(service, user);

        verify(bookingRepository, never()).attachGuestRowsByPhone(any(), any(), any(), any());
        verify(appointmentRepository, never()).attachGuestVisitsByPhone(any(), any(), any(), any());
    }

    @Test
    @DisplayName("audit LOW: an after-commit attach failure is followed by a sweep run that attaches the rows")
    void should_attachTheRowsOnTheSweep_when_theAfterCommitAttachFailed() {
        User user = verifiedAccount();
        stubNewTransaction();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(appointmentRepository.attachGuestVisitsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"))
                .thenReturn(0);
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(3);
        when(bookingRepository.findAttachableGuestPhones(200)).thenReturn(java.util.List.of(PHONE));

        attachInCommittedTransaction(service, user);
        verify(bookingRepository, never()).attachGuestRowsByPhone(any(), any(), any(), any());

        int attached = service.sweep(200);

        assertThat(attached).isEqualTo(3);
        verify(eventPublisher).publishEvent(new ClientBookingsAttachedEvent(user.getId()));
    }

    @Test
    @DisplayName("audit LOW: a failing phone does not stop the sweep, and an empty work list does nothing")
    void should_continueTheSweep_when_onePhoneFails() {
        User user = accountWithId();
        stubNewTransaction();
        when(bookingRepository.findAttachableGuestPhones(200)).thenReturn(java.util.List.of("+380500000001", PHONE));
        when(userRepository.findAttachableClientByVerifiedPhone("+380500000001"))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(1);

        assertThat(service.sweep(200)).isEqualTo(1);
    }

    @Test
    @DisplayName("audit LOW (cycle 3): a persistently failing phone does not stop the others across two sweeps")
    void should_attachTheOthers_when_aPoisonPhoneKeepsFailingAcrossTwoSweeps() {
        User user = accountWithId();
        String poison = "+380500000001";
        stubNewTransaction();
        when(userRepository.findAttachableClientByVerifiedPhone(poison))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(bookingRepository.attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW)).thenReturn(2);
        // the selection rotates (ORDER BY random()): run 1 draws only the poison phone, run 2 draws it together with a good one
        when(bookingRepository.findAttachableGuestPhones(2))
                .thenReturn(java.util.List.of(poison))
                .thenReturn(java.util.List.of(poison, PHONE));

        int first = service.sweep(2);
        int second = service.sweep(2);

        assertThat(first).isZero();
        assertThat(second).isEqualTo(2);
        verify(bookingRepository).attachGuestRowsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
    }

    @Test
    @DisplayName("audit LOW: a rejected executor hand-off is swallowed (left for the sweep) — the verification still succeeds")
    void should_notThrow_when_theExecutorRejectsTheAttach() {
        ClientPhoneAttachService rejecting = new ClientPhoneAttachService(bookingRepository, appointmentRepository,
                userRepository, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager,
                r -> { throw new org.springframework.core.task.TaskRejectedException("full"); },
                clientListRemovalService, clientRatingSubjectService);
        User user = verifiedAccount();

        org.assertj.core.api.Assertions.assertThatCode(
                () -> attachInCommittedTransaction(rejecting, user)).doesNotThrowAnyException();

        verify(bookingRepository, never()).acquireWalkInPhoneLock(any());
    }

    @Test
    @DisplayName("audit LOW: inside the caller's transaction the attach is deferred to afterCommit — nothing runs before it")
    void should_deferTheAttachUntilAfterCommit_when_aTransactionIsActive() {
        User user = verifiedAccount();
        stubNewTransaction();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.attachAfterCommit(user, AttachReason.PHONE_VERIFIED);

            verify(bookingRepository, never()).acquireWalkInPhoneLock(any());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(bookingRepository).acquireWalkInPhoneLock(PHONE);
            verify(appointmentRepository).attachGuestVisitsByPhone(PHONE, user.getId(), "PHONE_VERIFIED", NOW);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("should_returnTheAccount_when_lookupUnderTheLockFindsOne")
    void should_returnTheAccount_when_lookupUnderTheLockFindsOne() {
        User user = account();
        when(userRepository.findAttachableClientByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));

        Optional<User> found = service.lockAndFindAccount(PHONE);

        assertThat(found).containsSame(user);
        InOrder order = inOrder(bookingRepository, userRepository);
        order.verify(bookingRepository).acquireWalkInPhoneLock(PHONE);
        order.verify(userRepository).findAttachableClientByVerifiedPhone(PHONE);
    }

    @Test
    @DisplayName("should_clearTheGuestPhoneAndStampTheLink_when_aNewBookingIsAttached")
    void should_clearTheGuestPhoneAndStampTheLink_when_aNewBookingIsAttached() {
        User user = account();
        Booking booking = Booking.builder().bookingSource(BookingSource.LINK).guestName("Олена")
                .guestPhone(PHONE).build();

        service.attachNew(user, booking);

        assertThat(booking.getClient()).isSameAs(user);
        assertThat(booking.getGuestPhone()).isNull();
        assertThat(booking.getGuestName()).isEqualTo("Олена");
        assertThat(booking.getClientLinkReason()).isEqualTo(AttachReason.BOOKING);
        assertThat(booking.getClientLinkedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("should_attachTheHeaderToo_when_aNewVisitIsAttached")
    void should_attachTheHeaderToo_when_aNewVisitIsAttached() {
        User user = account();
        Appointment header = Appointment.builder().bookingSource(BookingSource.STAFF).guestName("Олена")
                .guestPhone(PHONE).build();

        service.attachNew(user, header);

        assertThat(header.getClient()).isSameAs(user);
        assertThat(header.getGuestPhone()).isNull();
        assertThat(header.getClientLinkReason()).isEqualTo(AttachReason.BOOKING);
    }

    @Test
    @DisplayName("should_refuse_when_anAppBookingIsAttachedByPhone")
    void should_refuse_when_anAppBookingIsAttachedByPhone() {
        Booking appBooking = Booking.builder().bookingSource(BookingSource.APP).build();

        assertThatThrownBy(() -> service.attachNew(account(), appBooking))
                .isInstanceOf(IllegalStateException.class);
    }
}
