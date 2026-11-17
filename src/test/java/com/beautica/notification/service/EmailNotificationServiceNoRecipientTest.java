package com.beautica.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.mockito.Mockito.verifyNoInteractions;

/**
 * B514 (NTF-56, NTF-D15) — a registered user without a confirmed email (phone-registered: {@code email = null})
 * gets no email of any kind, and nothing (not even the .ics, whose attendee is that email) is built for it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailNotificationService — no email to a user without one (B514, NTF-56)")
class EmailNotificationServiceNoRecipientTest {

    @Mock
    private JavaMailSender mailSender;
    @Mock
    private SpringTemplateEngine templateEngine;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(mailSender, templateEngine, "noreply@beautica.app");
    }

    @Test
    @DisplayName("a client booking receipt is not sent when the client has no email")
    void should_sendNothing_when_clientReceiptRecipientHasNoEmail() {
        service.sendBookingConfirmedEmail(null, null);
        service.sendClientCancelReceiptEmail(null, null);
        service.sendBookingRescheduledClientEmail(null, null, true);
        service.sendBookingReminderEmail(null, null, null);

        verifyNoInteractions(mailSender, templateEngine);
    }

    @Test
    @DisplayName("a provider notification is not sent when the provider has no email")
    void should_sendNothing_when_providerRecipientHasNoEmail() {
        service.sendNewBookingEmail(null, null);
        service.sendBookingRescheduledEmail(" ", null);
        service.sendClientCancelledEmail((String) null, (com.beautica.booking.entity.Booking) null);
        service.sendRequestDecisionEmail(null, RequestKind.values()[0], true, "name", null);

        verifyNoInteractions(mailSender, templateEngine);
    }

    @Test
    @DisplayName("client-facing booking outcome emails (decline, salon closed, master removed, review, reschedule "
            + "receipt) send nothing to a client without an email — B514 QA completes the per-method pin")
    void should_sendNothing_when_clientOutcomeRecipientHasNoEmail() {
        service.sendBookingDeclinedEmail(null, null);
        service.sendVisitDeclinedEmail(null, null);
        service.sendSalonClosedEmail(null, null);
        service.sendMasterRemovedEmail(" ", null);
        service.sendReviewRequestEmail(null, null, "https://beautica.app/bookings/x/review");
        service.sendBookingRescheduledClientEmail(null, null);

        verifyNoInteractions(mailSender, templateEngine);
    }

    @Test
    @DisplayName("provider work-queue emails (salon reschedule / decline, closure reminder, client cancelled visit) "
            + "send nothing to a provider without an email")
    void should_sendNothing_when_providerWorkQueueRecipientHasNoEmail() {
        service.sendBookingRescheduledBySalonEmail(null, null);
        service.sendBookingDeclinedBySalonEmail(null, null);
        service.sendClosureReminderEmail("", null, "https://beautica.app/bookings/x");
        service.sendClientCancelledEmail((String) null, (BookingVisit) null);

        verifyNoInteractions(mailSender, templateEngine);
    }

    @Test
    @DisplayName("account emails (invite, email verification code) are not attempted without a recipient address")
    void should_sendNothing_when_accountEmailRecipientMissing() {
        service.sendInviteEmail(null, "https://beautica.app/invite/accept?token=t", "Salon");
        service.sendVerificationEmail(null, "123456");

        verifyNoInteractions(mailSender, templateEngine);
    }
}
