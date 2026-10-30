package com.beautica.notification.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.BookingSmsDispatcher;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService — unit")
class NotificationServiceTest {

    private static final String FRONTEND_BASE_URL = "https://app.beautica.ua";

    @Mock
    private EmailNotificationService emailService;
    @Mock
    private BookingSmsDispatcher bookingSmsDispatcher;
    @Mock
    private com.beautica.booking.service.GuestRescheduleSmsBudget rescheduleSmsBudget;

    private NotificationService service;

    private static final String GUEST_PHONE = "+380501234567";
    private static final BookingSmsDispatcher.Kind KIND = BookingSmsDispatcher.Kind.GUEST_PROVIDER_CANCELLATION;
    /** Kyiv 10:00 on 01.08.2026 — the start every guest mock below carries. */
    private static final String BODY_0108 = "Beautica: Ваш запис 01.08.2026 о 10:00 скасовано.";

    @BeforeEach
    void setUp() {
        BookingLinksProperties links = new BookingLinksProperties();
        links.setShareEnabled(true);
        service = new NotificationService(
                emailService, bookingSmsDispatcher, new GuestProviderCancellationSmsText(links),
                new GuestRescheduleSmsText(new com.beautica.config.BookingSmsProperties()), rescheduleSmsBudget,
                FRONTEND_BASE_URL);
        // Phase 431: the budget grants by default; the cap rows below override it.
        lenient().when(rescheduleSmsBudget.tryReserve(any(), any(), any())).thenReturn(true);
        // Phase 408 audit-fix: the walk-in cancellation budget grants by default; the cap row below overrides it.
        lenient().when(rescheduleSmsBudget.tryReserveWalkInCancellation(any(), any(), any(), any())).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Phase 339 — push is no longer sent from this class
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 339: NotificationService holds no PushNotificationService — every push comes "
            + "from the in-app write path (INAPP_PUSH), so each event has exactly one push source")
    void should_haveNoPushCollaborator_when_phase339Applied() {
        List<Class<?>> fieldTypes = java.util.Arrays.stream(NotificationService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .collect(java.util.stream.Collectors.toList());
        List<Class<?>> ctorParamTypes = java.util.Arrays.stream(NotificationService.class.getConstructors())
                .flatMap(c -> java.util.Arrays.stream(c.getParameterTypes()))
                .collect(java.util.stream.Collectors.toList());

        assertThat(fieldTypes).doesNotContain(PushNotificationService.class);
        assertThat(ctorParamTypes).doesNotContain(PushNotificationService.class);
    }

    // -------------------------------------------------------------------------
    // notifyNewBooking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send email to master when notifyNewBooking is called")
    void should_sendEmailToMaster_when_notifyNewBookingCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);

        service.notifyNewBooking(BookingVisit.single(booking));

        verify(emailService).sendNewBookingEmail(anyString(), eq(BookingVisit.single(booking)));
    }

    @Test
    @DisplayName("notifyNewBooking falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_notThrow_when_notifyNewBookingForGuestBooking() {
        // Guest (LINK) booking: null client (V89 chk_bookings_guest_fields). GuestBookingService
        // enqueues NEW_BOOKING for EVERY guest booking, so an unguarded booking.getClient()
        // dereference here NPEs the drain worker on 100% of guest bookings — the master is never
        // notified at all and the outbox row goes DEAD.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Олена", "Коваль");

        service.notifyNewBooking(BookingVisit.single(booking));

        verify(emailService).sendNewBookingEmail(anyString(), eq(BookingVisit.single(booking)));
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — CONFIRMED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send confirmed email to client when status is CONFIRMED")
    void should_sendConfirmedEmailToClient_when_statusConfirmed() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.CONFIRMED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(emailService).sendBookingConfirmedEmail(anyString(), eq(BookingVisit.single(booking)));
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — DECLINED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send declined email to client when status is DECLINED")
    void should_sendDeclinedEmailToClient_when_statusDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(booking));
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — DECLINED, guest (LINK / null-client) booking (Phase 25.7 / 390)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 390: a declined GUEST booking queues exactly one provider-cancellation SMS "
            + "(no email/push — a guest has no account)")
    void should_dispatchExactlyOneSms_when_guestBookingDeclined() {
        Booking booking = buildGuestBookingMockForDecline("Майстер зачинений сьогодні через хворобу");

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(bookingSmsDispatcher, org.mockito.Mockito.times(1)).dispatchOrThrow(eq(KIND), eq(GUEST_PHONE), anyString());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
    }

    @Test
    @DisplayName("Phase 390: a saturated smsSendExecutor propagates out of the guest decline so the outbox "
            + "drain re-queues the row instead of marking it SENT")
    void should_propagateRejection_when_guestDeclineSmsExecutorSaturated() {
        Booking booking = buildGuestBookingMockForDecline(null);
        doThrow(new java.util.concurrent.RejectedExecutionException("saturated"))
                .when(bookingSmsDispatcher).dispatchOrThrow(eq(KIND), eq(GUEST_PHONE), anyString());

        assertThatThrownBy(() -> service.notifyBookingStatusChanged(BookingVisit.single(booking)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        verify(bookingSmsDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    @DisplayName("Phase 390: the guest decline SMS is the fixed copy + the master's rebook link — no "
            + "provider note, service name or master name")
    void should_renderFixedCopyWithoutProviderText_when_guestBookingDeclined() {
        Booking booking = buildGuestBookingMockForDecline("Майстер захворів https://evil.example");

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        assertThat(dispatchedText())
                .isEqualTo(BODY_0108 + " Записатися знову: beautica.app/book/test-master")
                .doesNotContain("Майстер захворів")
                .doesNotContain("evil")
                .doesNotContain("Тест послуга")
                .doesNotContain("Тест Майстер");
        verify(booking, never()).getProviderComment();
    }

    @Test
    @DisplayName("Phase 390: a provider-chosen service name can no longer reach the guest SMS at all")
    void should_notRenderServiceName_when_serviceNameCarriesAPlaceholder() {
        Booking booking = buildGuestBookingMockForDecline(null, "Манікюр {date} о {time}");

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        String sms = dispatchedText();
        assertThat(sms).doesNotContain("Манікюр");
        assertThat(countOccurrences(sms, "01.08.2026")).isEqualTo(1);
        assertThat(countOccurrences(sms, "10:00")).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 390: a WHOLE-VISIT decline queues ONE SMS naming the visit start")
    void should_dispatchOneSmsAtVisitStart_when_wholeGuestVisitDeclined() {
        Booking lead = buildGuestBookingMockForDecline(null);
        BookingVisit visit = wholeVisitDecline(lead, 3);

        service.notifyBookingStatusChanged(visit);

        // visitOf re-stubs the lead to 2026-06-15T10:00Z = 13:00 Kyiv (EEST).
        assertThat(dispatchedText()).startsWith("Beautica: Ваш запис 15.06.2026 о 13:00 скасовано.");
        verifyNoMoreInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 390: a PER-ITEM decline names that line's own start, not the visit start")
    void should_nameDeclinedLineStart_when_oneGuestVisitItemDeclined() {
        Booking declined = buildGuestBookingMockForDecline(null);
        Booking earlierSibling = mock(Booking.class);
        lenient().when(earlierSibling.getId()).thenReturn(UUID.randomUUID());
        lenient().when(earlierSibling.getStartsAt()).thenReturn(OffsetDateTime.parse("2026-08-01T09:00:00+03:00"));
        BookingVisit perItem = BookingVisit.of(declined, List.of(earlierSibling, declined));

        service.notifyBookingStatusChanged(perItem);

        assertThat(dispatchedText()).startsWith(BODY_0108);
    }

    /**
     * Phase 408 (user decision 2026-10-28, reverses the Phase 22.1 / 390 LINK-only scope): a declined STAFF
     * walk-in with a phone gets the same fixed provider-cancellation copy as a LINK guest — no provider note.
     */
    @Test
    @DisplayName("a declined STAFF walk-in with a phone gets ONE provider-cancellation message, same fixed "
            + "copy as a LINK guest, no provider note and no email")
    void should_sendDeclineSms_when_staffWalkInBookingDeclined() {
        Booking booking = buildStaffWalkInBookingMockForDecline("Майстер захворів");

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        assertThat(dispatchedText()).isEqualTo(BODY_0108);
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
    }

    @Test
    @DisplayName("Phase 408 audit-fix: a walk-in whose phone budget is exhausted gets no cancellation message, silently")
    void should_skipWalkInCancellationSms_when_phoneBudgetExhausted() {
        Booking booking = buildStaffWalkInBookingMockForDecline(null);
        when(rescheduleSmsBudget.tryReserveWalkInCancellation(any(), any(), eq(GUEST_PHONE), any())).thenReturn(false);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verifyNoInteractions(bookingSmsDispatcher);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 408 audit-fix cycle 2: the drained outbox entry id reaches the walk-in budget (retry idempotency)")
    void should_passOutboxEntryIdToWalkInBudget_when_drainedWithEntryId() {
        Booking booking = buildStaffWalkInBookingMockForDecline(null);
        UUID entryId = UUID.randomUUID();

        service.notifyBookingStatusChanged(BookingVisit.single(booking), entryId);

        verify(rescheduleSmsBudget).tryReserveWalkInCancellation(any(), any(), eq(GUEST_PHONE), eq(entryId));
        assertThat(dispatchedText()).isEqualTo(BODY_0108);
    }

    @Test
    @DisplayName("Phase 408 audit-fix: a LINK guest's decline never touches the walk-in budget")
    void should_notReserveWalkInBudget_when_linkGuestDeclined() {
        Booking booking = buildGuestBookingMockForDecline(null);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(rescheduleSmsBudget, never()).tryReserveWalkInCancellation(any(), any(), any(), any());
        assertThat(dispatchedText()).startsWith("Beautica: Ваш запис ");
    }

    @Test
    @DisplayName("a declined STAFF walk-in WITHOUT a phone gets nothing")
    void should_sendNothing_when_staffWalkInWithoutPhoneDeclined() {
        Booking booking = buildStaffWalkInBookingMockForDecline(null);
        when(booking.getGuestPhone()).thenReturn(null);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verifyNoInteractions(bookingSmsDispatcher);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("a declined LINK guest with no guestPhone queues nothing and does not throw")
    void should_skipSms_when_guestPhoneBlank() {
        Booking booking = buildGuestBookingMockForDecline(null);
        when(booking.getGuestPhone()).thenReturn("  ");

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verifyNoInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 25.7: an APP (registered-client) booking decline sends zero SMS")
    void should_sendZeroSms_when_appBookingDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verifyNoInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("the decline EMAIL path never reads the provider's note itself — the email service "
            + "receives the booking untouched")
    void should_notReadProviderComment_when_appBookingDeclinedEmailRendered() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(booking));
        verify(booking, never()).getProviderComment();
    }

    @Test
    @DisplayName("a guest booking CONFIRMED/COMPLETED/NOT_COMPLETED status stays a clean no-op — no "
            + "SMS, no email (NOT_COMPLETED is not a provider cancellation)")
    void should_noOp_when_guestBookingStatusIsNotDeclined() {
        for (BookingStatus status : new BookingStatus[] {
                BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED
        }) {
            Booking booking = buildGuestBookingMock(UUID.randomUUID(), "Олена", "Коваль");
            when(booking.getStatus()).thenReturn(status);

            service.notifyBookingStatusChanged(BookingVisit.single(booking));

            verifyNoInteractions(bookingSmsDispatcher);
            verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
            verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        }
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — COMPLETED / NOT_COMPLETED / other
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should not send email or push when status is COMPLETED")
    void should_notSendEmailOrPush_when_statusCompleted() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.COMPLETED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
    }

    @Test
    @DisplayName("should not send email or push when status is NOT_COMPLETED")
    void should_notSendEmailOrPush_when_statusNotCompleted() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.NOT_COMPLETED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
    }

    // -------------------------------------------------------------------------
    // notifyClientCancelled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send cancelled email to master when notifyClientCancelled is called")
    void should_sendCancelledEmailToMaster_when_notifyClientCancelledCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CANCELLED);

        service.notifyClientCancelled(booking);

        verify(emailService).sendClientCancelledEmail(anyString(), eq(booking));
    }

    @Test
    @DisplayName("notifyClientCancelled falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_useGuestIdentity_when_notifyClientCancelledForGuestBooking() {
        // CLIENT_CANCELLED is enqueued ONLY by BookingCancellationService.cancel — the public
        // guest cancel-link flow — so booking.getClient() is guaranteed null on every real call
        // of this method. An unguarded dereference here NPEs 100% of the time.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Іван", "Петренко");

        service.notifyClientCancelled(booking);

        verify(emailService).sendClientCancelledEmail(anyString(), eq(booking));
    }

    // -------------------------------------------------------------------------
    // notifyReviewRequested (Phase 18.5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifyReviewRequested targets the CLIENT (email) with a scheme-valid review URL")
    void should_targetClientWithReviewUrl_when_notifyReviewRequestedCalled() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.COMPLETED);
        String bookingId = booking.getId().toString();
        String expectedUrl = FRONTEND_BASE_URL + "/bookings/" + bookingId + "/review";
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyReviewRequested(booking);

        // Email is addressed to the client, carries the booking, and the scheme-valid review URL.
        verify(emailService).sendReviewRequestEmail(eq("client@example.com"), eq(booking), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).isEqualTo(expectedUrl);
        assertThat(urlCaptor.getValue()).startsWith("https://");
    }

    @Test
    @DisplayName("notifyReviewRequested is a safe no-op (no email/push, no throw) for a null-client guest booking")
    void should_noOp_when_notifyReviewRequestedForGuestBooking() {
        // Guest (LINK) booking: null client (V89 chk_bookings_guest_fields). If a REVIEW_REQUESTED
        // row ever reaches the drain route for such a booking, dispatch must be a clean no-op —
        // no NPE on booking.getClient(), no email/push — so the outbox entry settles SENT, not DEAD.
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);

        service.notifyReviewRequested(booking);

        verify(emailService, never()).sendReviewRequestEmail(anyString(), any(), anyString());
    }

    // -------------------------------------------------------------------------
    // notifyClosureReminder (Phase 29.5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifyClosureReminder targets the PROVIDER (email), never the client")
    void should_targetProviderWithBookingUrl_when_notifyClosureReminderCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        String bookingId = booking.getId().toString();
        String expectedUrl = FRONTEND_BASE_URL + "/bookings/" + bookingId;
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyClosureReminder(booking);

        // Email is addressed to the MASTER (provider), never the client — this is a work-queue
        // nudge, not a customer-facing message.
        verify(emailService).sendClosureReminderEmail(eq("master@example.com"), eq(booking), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).isEqualTo(expectedUrl);
        assertThat(urlCaptor.getValue()).startsWith("https://");
        // Never routed to the client-facing review email.
        verify(emailService, never()).sendReviewRequestEmail(anyString(), any(), anyString());
        verifyNoInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("notifyClosureReminder falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_useGuestIdentity_when_notifyClosureReminderForGuestBooking() {
        // A guest (LINK) booking still has a real master to nudge — only the client account is
        // absent (V89 chk_bookings_guest_fields) — so unlike notifyReviewRequested this must NOT
        // no-op; it must still notify the provider.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Олена", "Коваль");

        service.notifyClosureReminder(booking);

        verify(emailService).sendClosureReminderEmail(anyString(), eq(booking), anyString());
    }

    // -------------------------------------------------------------------------
    // notifySalonClosed (Phase 269/293)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifySalonClosed sends email to a registered client, never SMS")
    void should_sendEmail_when_notifySalonClosedForRegisteredClient() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        BookingVisit visit = BookingVisit.single(booking);

        service.notifySalonClosed(visit);

        verify(emailService).sendSalonClosedEmail(eq("client@example.com"), eq(visit));
        verifyNoInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 390: notifySalonClosed queues the fixed provider-cancellation SMS (no rebook "
            + "link — the salon is gone) and never touches email for a LINK guest visit")
    void should_dispatchBodyOnlySms_when_notifySalonClosedForGuestVisit() {
        Booking booking = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));

        service.notifySalonClosed(BookingVisit.single(booking));

        assertThat(dispatchedText()).isEqualTo(BODY_0108);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 408: notifySalonClosed sends a STAFF walk-in with a phone the same closure copy as a "
            + "LINK guest (no rebook link), no email")
    void should_sendSms_when_notifySalonClosedForStaffWalkIn() {
        Booking booking = buildStaffWalkInBookingMockForDecline(null);
        BookingVisit visit = BookingVisit.single(booking);

        service.notifySalonClosed(visit);

        assertThat(dispatchedText()).isEqualTo(BODY_0108);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 390: a multi-service guest visit closed by the salon gets ONE SMS at the visit "
            + "start and no service names")
    void should_dispatchOneSmsAtVisitStart_when_notifySalonClosedForMultiServiceGuestVisit() {
        Booking lead = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        BookingVisit visit = visitOf(lead, 3);

        service.notifySalonClosed(visit);

        assertThat(dispatchedText()).isEqualTo("Beautica: Ваш запис 15.06.2026 о 13:00 скасовано.");
        verifyNoMoreInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("the guest phone never appears in a NotificationService log line on the SMS path")
    void should_notLogRawPhone_when_salonClosedSmsQueued() {
        Booking booking = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(NotificationService.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        serviceLogger.setLevel(Level.DEBUG);
        try {
            service.notifySalonClosed(BookingVisit.single(booking));
        } finally {
            serviceLogger.detachAppender(logAppender);
        }

        StringBuilder allLogs = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            allLogs.append(event.getFormattedMessage()).append('\n');
        }
        assertThat(allLogs.toString())
                .doesNotContain(GUEST_PHONE)
                .doesNotContain("скасовано");
    }

    // -------------------------------------------------------------------------
    // notifyMasterRemoved (Phase 298) — same delivery matrix as notifySalonClosed, deliberately
    // NOT a reuse of it (the salon did not close, only the master left it).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifyMasterRemoved sends email to a registered client, never SMS")
    void should_sendEmail_when_notifyMasterRemovedForRegisteredClient() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        BookingVisit visit = BookingVisit.single(booking);

        service.notifyMasterRemoved(visit);

        verify(emailService).sendMasterRemovedEmail(eq("client@example.com"), eq(visit));
        verifyNoInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 390: notifyMasterRemoved queues the fixed SMS with the SALON's rebook link — "
            + "never the removed master's own page — and never touches email")
    void should_dispatchSalonRebookLink_when_notifyMasterRemovedForGuestVisit() {
        Booking booking = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        lenient().when(booking.getMaster()).thenReturn(
                // INDEPENDENT_MASTER so the master link WOULD be eligible on a decline — proving the
                // master-removal path ignores it by design, not by the salon-master type gate.
                Master.builder().masterType(MasterType.INDEPENDENT_MASTER)
                        .isActive(true).bookingSlug("test-master").build());
        lenient().when(booking.getSalon()).thenReturn(
                com.beautica.salon.entity.Salon.builder().isActive(true).bookingSlug("test-salon").build());

        service.notifyMasterRemoved(BookingVisit.single(booking));

        assertThat(dispatchedText())
                .isEqualTo(BODY_0108 + " Записатися знову: beautica.app/book/s/test-salon")
                .doesNotContain("test-master")
                .doesNotContain("Тест послуга");
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 390: a multi-service guest visit whose master was removed gets ONE SMS")
    void should_dispatchOneSms_when_notifyMasterRemovedForMultiServiceGuestVisit() {
        Booking lead = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        BookingVisit visit = visitOf(lead, 3);

        service.notifyMasterRemoved(visit);

        assertThat(dispatchedText()).isEqualTo("Beautica: Ваш запис 15.06.2026 о 13:00 скасовано.");
        verifyNoMoreInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 408: notifyMasterRemoved sends a STAFF walk-in with a phone the same master-removed "
            + "copy as a LINK guest, no email")
    void should_sendSms_when_notifyMasterRemovedForStaffWalkIn() {
        Booking booking = buildStaffWalkInBookingMockForDecline(null);
        BookingVisit visit = BookingVisit.single(booking);

        service.notifyMasterRemoved(visit);

        assertThat(dispatchedText()).isEqualTo(BODY_0108);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("notifyMasterRemoved forwards the WHOLE visit (D12 — one outbox entry per visit) "
            + "to the master-removed email")
    void should_forwardWholeVisitToEmail_when_notifyMasterRemovedForMultiServiceVisit() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);
        BookingVisit visit = visitOf(lead, 3);

        service.notifyMasterRemoved(visit);

        verify(emailService).sendMasterRemovedEmail(anyString(), eq(visit));
    }

    // -------------------------------------------------------------------------
    // sendInviteEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should forward URL verbatim to EmailService when sendInviteEmail called")
    void should_delegateToEmailService_when_sendInviteEmailCalled() {
        // Arrange
        String email = "user@example.com";
        String inviteUrl = "https://app.beautica.ua/invite/accept?token=ABC123";
        String salonName = "Test Salon";

        // Act
        service.sendInviteEmail(email, inviteUrl, salonName);

        // Assert — exact-arg forwarding, no transformation
        verify(emailService).sendInviteEmail(email, inviteUrl, salonName);
        verifyNoMoreInteractions(emailService);
    }

    // -------------------------------------------------------------------------
    // Multi-service visit — ONE notification naming the whole visit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifyNewBooking forwards the WHOLE visit to the new-booking email")
    void should_forwardWholeVisitToEmail_when_notifyNewBookingForMultiServiceVisit() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.CONFIRMED);
        BookingVisit visit = visitOf(lead, 3);

        service.notifyNewBooking(visit);

        verify(emailService).sendNewBookingEmail(anyString(), eq(visit));
    }

    @Test
    @DisplayName("notifyBookingStatusChanged CONFIRMED forwards the whole visit to the confirmation email")
    void should_forwardWholeVisitToEmail_when_multiServiceVisitConfirmed() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.CONFIRMED);
        BookingVisit visit = visitOf(lead, 2);

        service.notifyBookingStatusChanged(visit);

        verify(emailService).sendBookingConfirmedEmail(anyString(), eq(visit));
    }

    @Test
    @DisplayName("a DECLINED item of a multi-service visit routes to the PER-ITEM decline email — a "
            + "decline is enqueued per item, so describing the whole visit would misreport it")
    void should_sendPerItemDeclineEmail_when_oneItemOfAMultiServiceVisitIsDeclined() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);
        BookingVisit visit = visitOf(lead, 3);

        service.notifyBookingStatusChanged(visit);

        // CONFIRMED is enqueued ONCE per visit so it describes every service; DECLINED is enqueued
        // per item so it must NOT be routed through the whole-visit email.
        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(lead));
        verify(emailService, never()).sendVisitDeclinedEmail(anyString(), any());
    }

    @Test
    @DisplayName("a WHOLE-VISIT decline routes to the whole-visit decline email — the client must not "
            + "be left believing the rest of the visit still stands")
    void should_sendWholeVisitDeclineEmail_when_theWholeVisitIsDeclined() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);
        // AppointmentTransitionService#declineAppointment moves the header AND every item to
        // DECLINED but enqueues ONE STATUS_CHANGED against items.get(0).
        BookingVisit visit = wholeVisitDecline(lead, 3);

        service.notifyBookingStatusChanged(visit);

        verify(emailService).sendVisitDeclinedEmail(anyString(), eq(visit));
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any(Booking.class));
    }

    @Test
    @DisplayName("a single-service visit whose appointment header is DECLINED keeps the single-service "
            + "decline email")
    void should_keepTheSingleServiceDeclineEmail_when_aOneItemVisitHeaderIsDeclined() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);
        BookingVisit visit = wholeVisitDecline(lead, 1);

        service.notifyBookingStatusChanged(visit);

        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(lead));
    }

    /**
     * A visit of {@code size} chained bookings sharing {@code lead}'s master/client — only the
     * item count and per-item service names matter to the copy under test, so the siblings are
     * minimal mocks ordered an hour apart.
     *
     * <p>Carries NO appointment status, which is what makes it a PER-ITEM event: see
     * {@link #wholeVisitDecline(Booking, int)} for the whole-visit twin.
     */
    private static BookingVisit visitOf(Booking lead, int size) {
        OffsetDateTime base = OffsetDateTime.parse("2026-06-15T10:00:00Z");
        lenient().when(lead.getStartsAt()).thenReturn(base);
        List<Booking> items = new ArrayList<>();
        items.add(lead);
        for (int i = 1; i < size; i++) {
            Booking sibling = mock(Booking.class);
            lenient().when(sibling.getId()).thenReturn(UUID.randomUUID());
            lenient().when(sibling.getStartsAt()).thenReturn(base.plusHours(i));
            ServiceDefinition sd = mock(ServiceDefinition.class);
            lenient().when(sd.getName()).thenReturn("Послуга " + i);
            MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
            lenient().when(msa.getServiceDefinition()).thenReturn(sd);
            lenient().when(sibling.getMasterService()).thenReturn(msa);
            items.add(sibling);
        }
        return BookingVisit.of(lead, items);
    }

    /**
     * The same shape as {@link #visitOf(Booking, int)} but stamped with a {@code DECLINED}
     * appointment header — the signal that says "the provider cancelled the WHOLE visit", which the
     * item rows alone cannot express. The resolver stamps this in production; here it is set
     * explicitly so the branch is exercised without a repository.
     */
    private static BookingVisit wholeVisitDecline(Booking lead, int size) {
        BookingVisit perItem = visitOf(lead, size);
        return BookingVisit.of(lead, perItem.items(), BookingStatus.DECLINED);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal Booking mock with master, client, and masterService chains.
     * Master-chain stubs use lenient() where methods are not accessed by CONFIRMED/DECLINED paths.
     */
    private Booking buildBookingMock(UUID masterUserId, UUID clientUserId, BookingStatus status) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        lenient().when(booking.getStatus()).thenReturn(status);

        User clientUser = mock(User.class);
        lenient().when(clientUser.getId()).thenReturn(clientUserId);
        lenient().when(clientUser.getEmail()).thenReturn("client@example.com");
        lenient().when(clientUser.getFirstName()).thenReturn("Тест");
        lenient().when(clientUser.getLastName()).thenReturn("Клієнт");
        lenient().when(booking.getClient()).thenReturn(clientUser);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getId()).thenReturn(masterUserId);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        // A REAL Master, not a mock (phase 294): the notification paths read the provider name
        // through Master#displayFirstName()/#displayLastName(), whose attached-vs-detached branch
        // only executes on a real instance.
        Master master = Master.builder().user(masterUser).build();
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn("Тест послуга");
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }

    /**
     * Builds a guest (LINK) booking mock: {@code getClient()} returns null (V89
     * chk_bookings_guest_fields), and {@code getGuestName}/{@code getGuestSurname} carry the
     * OTP-verified guest identity instead.
     */
    private Booking buildGuestBookingMock(UUID masterUserId, String guestName, String guestSurname) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        lenient().when(booking.getClient()).thenReturn(null);
        lenient().when(booking.getGuestName()).thenReturn(guestName);
        lenient().when(booking.getGuestSurname()).thenReturn(guestSurname);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getId()).thenReturn(masterUserId);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        // A REAL Master, not a mock (phase 294): the notification paths read the provider name
        // through Master#displayFirstName()/#displayLastName(), whose attached-vs-detached branch
        // only executes on a real instance.
        Master master = Master.builder().user(masterUser).build();
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn("Тест послуга");
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }

    /**
     * Builds a DECLINED guest (LINK) booking mock carrying everything
     * {@code buildGuestDeclineSms} reads: {@code guestPhone}, {@code startsAt}, the master's
     * name, the service name, and the given {@code providerComment} (Phase 25.7).
     */
    private Booking buildGuestBookingMockForDecline(String providerComment) {
        return buildGuestBookingMockForDecline(providerComment, "Тест послуга");
    }

    /**
     * Same as {@link #buildGuestBookingMockForDecline(String)} but with a caller-chosen service
     * NAME — the one field a provider controls and the only untrusted input reaching the decline
     * SMS template. Every other caller keeps the shared constant through the delegate above.
     */
    private Booking buildGuestBookingMockForDecline(String providerComment, String serviceName) {
        Booking booking = mock(Booking.class);
        // lenient: only read on the (untested-here) blank-guestPhone warning branch.
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);
        // lenient: read only once the guest-SMS branch is gated on the SOURCE rather than on a
        // null client (Phase 22.1 security MEDIUM). Stubbed unconditionally so every existing
        // LINK case keeps describing a genuine LINK booking rather than "some null-client row".
        lenient().when(booking.getBookingSource()).thenReturn(BookingSource.LINK);
        when(booking.getStatus()).thenReturn(BookingStatus.DECLINED);
        lenient().when(booking.getGuestPhone()).thenReturn("+380501234567");
        lenient().when(booking.getStartsAt()).thenReturn(OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        lenient().when(booking.getProviderComment()).thenReturn(providerComment);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getFirstName()).thenReturn("Тест");
        lenient().when(masterUser.getLastName()).thenReturn("Майстер");
        // A REAL Master, not a mock: phase 390 reads its isActive flag and booking slug.
        Master master = Master.builder().user(masterUser).masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true).bookingSlug("test-master").build();
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn(serviceName);
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }

    /**
     * A DECLINED STAFF walk-in: identical to {@link #buildGuestBookingMockForDecline(String)} in
     * every respect the notification path can see — null client, guest identity, a phone — except
     * that {@code bookingSource} is {@code STAFF}. That single-field difference is the point: it is
     * the only thing distinguishing a number the recipient proved by OTP from one a salon employee
     * typed about a third party, so it must be the thing the SMS gate reads.
     */
    private Booking buildStaffWalkInBookingMockForDecline(String providerComment) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);
        when(booking.getBookingSource()).thenReturn(BookingSource.STAFF);
        lenient().when(booking.getStatus()).thenReturn(BookingStatus.DECLINED);
        lenient().when(booking.getGuestPhone()).thenReturn("+380501234567");
        lenient().when(booking.getGuestName()).thenReturn("Олена");
        lenient().when(booking.getGuestSurname()).thenReturn("Коваль");
        lenient().when(booking.getStartsAt()).thenReturn(OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        lenient().when(booking.getProviderComment()).thenReturn(providerComment);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getFirstName()).thenReturn("Тест");
        lenient().when(masterUser.getLastName()).thenReturn("Майстер");
        // A REAL Master, not a mock (phase 294): the notification paths read the provider name
        // through Master#displayFirstName()/#displayLastName(), whose attached-vs-detached branch
        // only executes on a real instance.
        Master master = Master.builder().user(masterUser).build();
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn("Тест послуга");
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }

    /**
     * Builds a LINK guest booking mock for {@code notifySalonClosed} tests: {@code getClient()}
     * and {@code getBookingSource()} are strict (both are read on every call, to decide the
     * SMS-vs-email branch); everything else is {@code lenient} so this composes cleanly with
     * {@link #visitOf(Booking, int)}, which re-stubs {@code getStartsAt()} on the lead itself.
     *
     * <p>Deliberately carries NO {@code providerComment}/{@code status} stub —
     * {@code notifySalonClosed} never reads either (D10: {@link BookingVisit} exposes no note
     * accessor, and the salon-closure copy does not branch on booking status the way
     * {@code notifyBookingStatusChanged} does) — reusing {@code buildGuestBookingMockForDecline}
     * here would leave those two stubs unread and trip Mockito's strict-stubs check.
     */
    private Booking buildGuestBookingMockForSalonClosed(String serviceName, OffsetDateTime startsAt) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);
        when(booking.getBookingSource()).thenReturn(BookingSource.LINK);
        lenient().when(booking.getGuestPhone()).thenReturn("+380501234567");
        lenient().when(booking.getStartsAt()).thenReturn(startsAt);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn(serviceName);
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }

    /** The one text queued for the guest — fails if anything other than exactly one dispatch happened. */
    private String dispatchedText() {
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(bookingSmsDispatcher).dispatchOrThrow(eq(KIND), eq(GUEST_PHONE), textCaptor.capture());
        return textCaptor.getValue();
    }

    // -------------------------------------------------------------------------
    // Phase 431 — guest reschedule SMS (notifyBookingRescheduledClient, null-client branch)
    // -------------------------------------------------------------------------

    private static final BookingSmsDispatcher.Kind RESCHEDULE_KIND = BookingSmsDispatcher.Kind.GUEST_PROVIDER_RESCHEDULE;
    /** The NEW start every reschedule mock carries: Kyiv 10:00 on 01.08.2026. */
    private static final String RESCHEDULE_0108 = "Beautica: Ваш запис перенесено на 01.08.2026 о 10:00.";

    @Test
    @DisplayName("Phase 431: a provider reschedule of a LINK guest booking queues ONE fixed-copy SMS with the new "
            + "Kyiv date/time, no email")
    void should_dispatchOneRescheduleSms_when_linkGuestBookingRescheduledByProvider() {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, GUEST_PHONE);

        service.notifyBookingRescheduledClient(booking);

        verify(bookingSmsDispatcher).dispatchOrThrow(RESCHEDULE_KIND, GUEST_PHONE, RESCHEDULE_0108);
        verifyNoMoreInteractions(bookingSmsDispatcher);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 431: a provider reschedule of a STAFF walk-in queues the same SMS (user decision 2026-10-30)")
    void should_dispatchOneRescheduleSms_when_staffWalkInRescheduledByProvider() {
        Booking booking = lenientRouteBooking(BookingSource.STAFF, null, BookingStatus.CONFIRMED, GUEST_PHONE);

        service.notifyBookingRescheduledClient(booking);

        verify(bookingSmsDispatcher).dispatchOrThrow(RESCHEDULE_KIND, GUEST_PHONE, RESCHEDULE_0108);
        verifyNoInteractions(emailService);
    }

    @ParameterizedTest(name = "phone=[{0}]")
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"  "})
    @DisplayName("Phase 431: an anonymised (guest_phone NULL) or phone-less guest gets no reschedule SMS and nothing throws")
    void should_skipRescheduleSms_when_guestPhoneMissing(String phone) {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, phone);

        service.notifyBookingRescheduledClient(booking);

        verifyNoInteractions(bookingSmsDispatcher, emailService);
    }

    @Test
    @DisplayName("Phase 431: a registered client's provider reschedule still sends the client email only — no SMS")
    void should_sendClientEmailOnly_when_registeredClientRescheduledByProvider() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.CONFIRMED);

        service.notifyBookingRescheduledClient(booking);

        verify(emailService).sendBookingRescheduledClientEmail("client@example.com", booking);
        verifyNoInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 431: a saturated SMS pool propagates out of the guest reschedule so the drain re-queues the row")
    void should_propagateBackpressure_when_guestRescheduleSmsPoolSaturated() {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, GUEST_PHONE);
        doThrow(new com.beautica.notification.sms.SmsBackpressureException("saturated"))
                .when(bookingSmsDispatcher).dispatchOrThrow(eq(RESCHEDULE_KIND), eq(GUEST_PHONE), anyString());

        assertThatThrownBy(() -> service.notifyBookingRescheduledClient(booking))
                .isInstanceOf(com.beautica.notification.sms.SmsBackpressureException.class);
    }

    @Test
    @DisplayName("Phase 431: the CLIENT-initiated reschedule (provider-facing email) never sends an SMS, even for a "
            + "LINK guest row with a phone")
    void should_neverSendSms_when_clientInitiatedRescheduleNotifiesProvider() {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, GUEST_PHONE);
        User masterUser = mock(User.class);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        lenient().when(booking.getMaster()).thenReturn(Master.builder().user(masterUser).build());

        service.notifyBookingRescheduled(booking);

        verifyNoInteractions(bookingSmsDispatcher);
    }

    // -------------------------------------------------------------------------
    // Phase 431 audit-fix cycle 1 — stale / burst / budget bounds on the guest reschedule SMS
    // -------------------------------------------------------------------------

    /** 2026-08-01T10:00+03:00 — the start every lenientRouteBooking carries. */
    private static final long ROUTE_START_EPOCH = OffsetDateTime.parse("2026-08-01T10:00:00+03:00").toEpochSecond();

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Phase 431: a booking cancelled / declined / closed before the drain gets no reschedule SMS")
    void should_skipSms_when_bookingNoLongerConfirmed(BookingStatus status) {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, status, GUEST_PHONE);

        service.notifyBookingRescheduledClient(booking, UUID.randomUUID(), ROUTE_START_EPOCH);

        verifyNoInteractions(bookingSmsDispatcher, rescheduleSmsBudget);
    }

    @Test
    @DisplayName("Phase 431: a row superseded by a later move (payload start != current start) sends nothing")
    void should_skipSms_when_rowSupersededByLaterMove() {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, GUEST_PHONE);

        service.notifyBookingRescheduledClient(booking, UUID.randomUUID(), ROUTE_START_EPOCH - 3600);

        verifyNoInteractions(bookingSmsDispatcher, rescheduleSmsBudget);
    }

    @Test
    @DisplayName("Phase 431: the row for the CURRENT start reserves budget under its outbox entry id, then sends")
    void should_reserveAndSend_when_rowMatchesCurrentStart() {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, GUEST_PHONE);
        UUID entryId = UUID.randomUUID();

        service.notifyBookingRescheduledClient(booking, entryId, ROUTE_START_EPOCH);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rescheduleSmsBudget, bookingSmsDispatcher);
        order.verify(rescheduleSmsBudget).tryReserve(booking.getId(), GUEST_PHONE, entryId);
        order.verify(bookingSmsDispatcher).dispatchOrThrow(RESCHEDULE_KIND, GUEST_PHONE, RESCHEDULE_0108);
    }

    @Test
    @DisplayName("Phase 431: over the per-booking cap or the per-phone budget — no SMS, no exception, no email")
    void should_skipSms_when_budgetRefuses() {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, GUEST_PHONE);
        when(rescheduleSmsBudget.tryReserve(any(), any(), any())).thenReturn(false);

        service.notifyBookingRescheduledClient(booking, UUID.randomUUID(), ROUTE_START_EPOCH);

        verifyNoInteractions(bookingSmsDispatcher, emailService);
    }

    @Test
    @DisplayName("Phase 431: a pre-431 row (no start) still sends while CONFIRMED")
    void should_sendSms_when_legacyRowHasNoStart() {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.CONFIRMED, GUEST_PHONE);

        service.notifyBookingRescheduledClient(booking, UUID.randomUUID(), null);

        verify(bookingSmsDispatcher).dispatchOrThrow(RESCHEDULE_KIND, GUEST_PHONE, RESCHEDULE_0108);
    }

    // -------------------------------------------------------------------------
    // Phase 431 — performing-master e-mail on a provider reschedule / decline
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 431: the salon owner moves the salon master's booking — the master gets the 'salon moved it' email")
    void should_emailPerformingMaster_when_otherProviderReschedules() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);

        service.notifyMasterOfProviderReschedule(booking, UUID.randomUUID());

        verify(emailService).sendBookingRescheduledBySalonEmail("master@example.com", booking);
        verifyNoMoreInteractions(emailService);
        verifyNoInteractions(bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 431: the performing master moved the booking themselves — no self-notification")
    void should_notEmailMaster_when_masterIsTheRescheduleActor() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);

        service.notifyMasterOfProviderReschedule(booking, masterUserId);

        verifyNoInteractions(emailService, bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 431: an unknown actor (pre-431 row) never emails the master")
    void should_notEmailMaster_when_rescheduleActorUnknown() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.CONFIRMED);

        service.notifyMasterOfProviderReschedule(booking, null);

        verifyNoInteractions(emailService, bookingSmsDispatcher);
    }

    @Test
    @DisplayName("Phase 431: a detached master (no user) gets nothing and nothing throws")
    void should_notEmailMaster_when_performingMasterDetached() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);
        when(booking.getMaster()).thenReturn(Master.builder().user(null).build());

        service.notifyMasterOfProviderDecline(BookingVisit.single(booking), UUID.randomUUID());

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 431: an admin declines the salon master's single booking — the master gets ONE 'salon "
            + "cancelled' email for that booking")
    void should_emailPerformingMaster_when_otherProviderDeclines() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);

        service.notifyMasterOfProviderDecline(BookingVisit.single(booking), UUID.randomUUID());

        verify(emailService).sendBookingDeclinedBySalonEmail("master@example.com", BookingVisit.single(booking));
        verifyNoMoreInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 431: a whole-visit decline emails the master ONCE, naming the whole visit")
    void should_emailMasterOncePerVisit_when_wholeVisitDeclined() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);
        BookingVisit visit = wholeVisitDecline(lead, 3);

        service.notifyMasterOfProviderDecline(visit, UUID.randomUUID());

        verify(emailService).sendBookingDeclinedBySalonEmail("master@example.com", visit);
        verifyNoMoreInteractions(emailService);
    }

    @Test
    @DisplayName("Phase 431: a per-service decline within a visit names only that service")
    void should_emailMasterForDeclinedServiceOnly_when_oneVisitItemDeclined() {
        Booking lead = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.DECLINED);
        BookingVisit perItem = visitOf(lead, 3);

        service.notifyMasterOfProviderDecline(perItem, UUID.randomUUID());

        verify(emailService).sendBookingDeclinedBySalonEmail("master@example.com", BookingVisit.single(lead));
    }

    @Test
    @DisplayName("Phase 431: the performing master declined it themselves — no self-notification")
    void should_notEmailMaster_when_masterIsTheDeclineActor() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.DECLINED);

        service.notifyMasterOfProviderDecline(BookingVisit.single(booking), masterUserId);

        verifyNoInteractions(emailService);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "DECLINED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Phase 431: only a DECLINED row emails the master — every other status is a no-op")
    void should_notEmailMaster_when_statusIsNotDeclined(BookingStatus status) {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), status);

        service.notifyMasterOfProviderDecline(BookingVisit.single(booking), UUID.randomUUID());

        verifyNoInteractions(emailService);
    }

    // -------------------------------------------------------------------------
    // routesToLinkGuestSms — parity with the three LINK-guest SMS branches (phase 390, perf LOW-1)
    // -------------------------------------------------------------------------

    /**
     * Every source × client × status × phone shape, for each of the three SMS-capable event types. Every
     * {@link BookingStatus} (not just DECLINED/CONFIRMED): a future widening of the STATUS_CHANGED guest
     * branch to a second status must break this parity row, not slip past it.
     */
    static Stream<Arguments> smsRouteMatrix() {
        List<Arguments> rows = new ArrayList<>();
        for (OutboxEventType type : List.of(
                OutboxEventType.STATUS_CHANGED,
                OutboxEventType.SALON_CLOSED,
                OutboxEventType.MASTER_REMOVED,
                OutboxEventType.BOOKING_RESCHEDULED)) {
            for (BookingSource source : BookingSource.values()) {
                for (boolean hasClient : new boolean[] {false, true}) {
                    for (BookingStatus status : BookingStatus.values()) {
                        for (String phone : java.util.Arrays.asList(GUEST_PHONE, " ", null)) {
                            rows.add(Arguments.of(
                                    type, source, hasClient, status, phone));
                        }
                    }
                }
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} source={1} client={2} status={3} phone=[{4}]")
    @MethodSource("smsRouteMatrix")
    @DisplayName("routesToLinkGuestSms is true exactly when dispatching that row hands an SMS to dispatchOrThrow")
    void should_matchActualSmsDispatch_when_routesToLinkGuestSmsAsked(
            OutboxEventType type, BookingSource source, boolean hasClient,
            BookingStatus status, String phone) {
        User client = mock(User.class);
        lenient().when(client.getEmail()).thenReturn("client@example.com");
        Booking booking = lenientRouteBooking(source, hasClient ? client : null, status, phone);
        BookingVisit visit = BookingVisit.single(booking);

        boolean predicted = service.routesToLinkGuestSms(type, booking);
        switch (type) {
            case STATUS_CHANGED -> service.notifyBookingStatusChanged(visit);
            case SALON_CLOSED -> service.notifySalonClosed(visit);
            case MASTER_REMOVED -> service.notifyMasterRemoved(visit);
            // Phase 431 — the provider-initiated route (the only one a null-client booking can take).
            case BOOKING_RESCHEDULED -> service.notifyBookingRescheduledClient(booking);
            default -> throw new IllegalArgumentException(type.name());
        }

        verify(bookingSmsDispatcher, org.mockito.Mockito.times(predicted ? 1 : 0))
                .dispatchOrThrow(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = OutboxEventType.class,
            names = {"STATUS_CHANGED", "SALON_CLOSED", "MASTER_REMOVED", "BOOKING_RESCHEDULED"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("routesToLinkGuestSms is false for every event type that never sends a guest SMS, even on a "
            + "declined LINK guest booking")
    void should_returnFalse_when_eventTypeHasNoGuestSmsBranch(OutboxEventType type) {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.DECLINED, GUEST_PHONE);

        boolean routed = service.routesToLinkGuestSms(type, booking);

        assertThat(routed).isFalse();
    }

    /**
     * Phase 390 QA (perf INFO-2): the other half of the parity claim. {@code routesToLinkGuestSms} is
     * false for every non-SMS event type (above) — this proves the matching notify path, exactly as
     * {@code NotificationOutboxDrainWorker#dispatch} routes that type, really never hands an SMS to the
     * dispatcher, on the most SMS-shaped booking there is (declined LINK guest with a phone). Without it
     * a future SMS branch on, say, CLIENT_CANCELLED would be sent from the drain while the predicate
     * still says "not an SMS row", and the backpressure deferral would silently skip it.
     * INAPP_PUSH is excluded: the drain hands it to {@code InAppPushDispatcher}, never to this class.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = OutboxEventType.class,
            names = {"STATUS_CHANGED", "SALON_CLOSED", "MASTER_REMOVED", "BOOKING_RESCHEDULED", "INAPP_PUSH"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every non-SMS event type's notify path never reaches the SMS dispatcher, even for a declined "
            + "LINK guest booking with a phone")
    void should_neverCallSmsDispatcher_when_nonSmsEventTypeNotifyPathRuns(
            OutboxEventType type) {
        Booking booking = lenientRouteBooking(BookingSource.LINK, null, BookingStatus.DECLINED, GUEST_PHONE);
        User masterUser = mock(User.class);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        lenient().when(booking.getMaster()).thenReturn(Master.builder().user(masterUser).build());

        switch (type) {
            case NEW_BOOKING -> service.notifyNewBooking(BookingVisit.single(booking));
            case CLIENT_CANCELLED -> service.notifyClientCancelled(booking);
            case REVIEW_REQUESTED -> service.notifyReviewRequested(booking);
            case CLOSURE_REMINDER -> service.notifyClosureReminder(booking);
            case INVITE -> service.sendInviteEmail("invitee@example.com", "https://app.beautica.ua/i", "Salon");
            case MASTER_BOOKING_RESCHEDULED -> service.notifyMasterOfProviderReschedule(booking, UUID.randomUUID());
            case MASTER_BOOKING_DECLINED ->
                    service.notifyMasterOfProviderDecline(BookingVisit.single(booking), UUID.randomUUID());
            default -> throw new IllegalArgumentException("unmapped event type " + type.name());
        }

        assertThat(service.routesToLinkGuestSms(type, booking)).isFalse();
        verifyNoInteractions(bookingSmsDispatcher);
    }

    /**
     * All-lenient booking for the route-parity rows: each row reads a different subset of these
     * (the predicate short-circuits, the notify paths branch), so strict stubs would fail on the rows
     * that legitimately skip one. No master/salon — the SMS text then simply carries no rebook link.
     */
    private static Booking lenientRouteBooking(BookingSource source, User client, BookingStatus status, String phone) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        lenient().when(booking.getBookingSource()).thenReturn(source);
        lenient().when(booking.getClient()).thenReturn(client);
        lenient().when(booking.getStatus()).thenReturn(status);
        lenient().when(booking.getGuestPhone()).thenReturn(phone);
        lenient().when(booking.getStartsAt()).thenReturn(OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        return booking;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
