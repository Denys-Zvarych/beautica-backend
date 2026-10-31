package com.beautica.notification.channel;

import com.beautica.booking.event.GuestReminderDispatcher;
import com.beautica.booking.service.BookingSmsDispatcher;
import com.beautica.config.TelegramConfig;
import com.beautica.notification.service.NotificationService;
import com.beautica.notification.sms.SmsDeliveryException;
import com.beautica.notification.telegram.TelegramNotAllowListedException;
import com.beautica.review.invite.service.ReviewInviteSendJob;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.telegram.TelegramDeliveryException;
import com.beautica.notification.telegram.TelegramMessenger;
import com.beautica.notification.telegram.TelegramRecipientGoneException;
import com.beautica.notification.telegram.link.ActiveLink;
import com.beautica.notification.telegram.link.TelegramLinkService;
import com.beautica.notification.telegram.webhook.LinkedBookingDetailsSender;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Phase 408 D2 — one test per resolution branch, plus the SMS-failure propagation and the unrouted-path fence. */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuestMessageRouter — Telegram when linked, SMS otherwise, SMS on every Telegram failure")
class GuestMessageRouterTest {

    private static final String PHONE = "+380501234567";
    private static final String TEXT = "Beautica: Нагадуємо!\nМанікюр у Олена\nЗавтра о 10:00";
    private static final long CHAT_ID = 7_100_000_001L;
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000408");
    private static final GuestMessageKind KIND = GuestMessageKind.GUEST_REMINDER_24H;

    @Mock
    private SmsService smsService;
    @Mock
    private TelegramMessenger telegramMessenger;
    @Mock
    private TelegramLinkService linkService;

    private SimpleMeterRegistry meters;
    private GuestMessageRouter router;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        router = routerWith(true, true);
    }

    @Test
    @DisplayName("D2.1 flag off → the exact SMS call, no lookup, no Telegram")
    void should_sendSmsOnly_when_flagOff() {
        GuestMessageRouter disabled = routerWith(false, true);

        disabled.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        verifyNoInteractions(telegramMessenger, linkService);
        assertThat(count("sms", "flag_off")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D2.2 no ACTIVE link → SMS, Telegram never called")
    void should_sendSms_when_notLinked() {
        when(linkService.findActiveForPhone(PHONE)).thenReturn(Optional.empty());

        router.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        verifyNoInteractions(telegramMessenger);
        assertThat(count("sms", "not_linked")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D2.3 ACTIVE link + Telegram OK → Telegram with the identical text, touchLastSent, no SMS")
    void should_sendTelegramOnly_when_linkedAndSendSucceeds() {
        linked();

        router.deliver(KIND, PHONE, TEXT);

        verify(telegramMessenger).sendWaiting(CHAT_ID, TEXT);
        verify(linkService).touchLastSent(LINK_ID);
        verifyNoInteractions(smsService);
        assertThat(count("telegram", "sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D2.3 a failed touchLastSent after delivery never triggers an SMS or escapes")
    void should_notSendSms_when_touchLastSentFailsAfterDelivery() {
        linked();
        doThrow(new IllegalStateException("db down")).when(linkService).touchLastSent(LINK_ID);

        router.deliver(KIND, PHONE, TEXT);

        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("D2.4 recipient gone → exactly one SMS FIRST, then markBlocked(linkId, chatId)")
    void should_markBlockedThenSendSms_when_recipientGone() {
        linked();
        doThrow(new TelegramRecipientGoneException("403 bot was blocked")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);

        router.deliver(KIND, PHONE, TEXT);

        InOrder order = inOrder(smsService, linkService);
        order.verify(smsService).send(PHONE, TEXT);
        order.verify(linkService).markBlocked(LINK_ID, CHAT_ID);
        verify(linkService, never()).touchLastSent(any());
        assertThat(count("sms", "gone_fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D2.4 recipient gone + SMS failing → the SMS failure propagates AND the link is still blocked")
    void should_stillMarkBlocked_when_goneFallbackSmsFails() {
        linked();
        doThrow(new TelegramRecipientGoneException("403")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);
        SmsDeliveryException smsFailure = new SmsDeliveryException("turbosms down");
        doThrow(smsFailure).when(smsService).send(PHONE, TEXT);

        assertThatThrownBy(() -> router.deliver(KIND, PHONE, TEXT)).isSameAs(smsFailure);

        verify(linkService).markBlocked(LINK_ID, CHAT_ID);
    }

    @Test
    @DisplayName("D2.4 markBlocked itself failing still ends in the SMS")
    void should_stillSendSms_when_markBlockedFails() {
        linked();
        doThrow(new TelegramRecipientGoneException("403")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);
        doThrow(new IllegalStateException("db down")).when(linkService).markBlocked(LINK_ID, CHAT_ID);

        router.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
    }

    @Test
    @DisplayName("D2.5 TelegramDeliveryException (transient / allow-list / NoOp) → SMS, link untouched")
    void should_sendSmsAndLeaveLink_when_telegramDeliveryFails() {
        linked();
        doThrow(new TelegramDeliveryException("429 retry after 30")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);

        router.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        verify(linkService, never()).markBlocked(any(UUID.class), org.mockito.ArgumentMatchers.anyLong());
        verify(linkService, never()).touchLastSent(any());
        assertThat(count("sms", "transient_fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D2.5 any other RuntimeException on the Telegram path → SMS")
    void should_sendSms_when_telegramThrowsUnexpectedRuntimeException() {
        linked();
        doThrow(new IllegalArgumentException("text too long")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);

        router.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        assertThat(count("sms", "transient_fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D2.6 the link lookup throws → SMS, Telegram never called")
    void should_sendSms_when_lookupThrows() {
        when(linkService.findActiveForPhone(PHONE)).thenThrow(new IllegalStateException("pool exhausted"));

        router.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        verifyNoInteractions(telegramMessenger);
        assertThat(count("sms", "lookup_error_fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SMS failing after a transient Telegram failure propagates (the caller's retry semantics)")
    void should_propagateSmsFailure_when_smsFailsAfterTelegramTransient() {
        linked();
        doThrow(new TelegramDeliveryException("500")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);
        SmsDeliveryException smsFailure = new SmsDeliveryException("turbosms down");
        doThrow(smsFailure).when(smsService).send(PHONE, TEXT);

        assertThatThrownBy(() -> router.deliver(KIND, PHONE, TEXT)).isSameAs(smsFailure);
    }

    @Test
    @DisplayName("audit-fix decision: app.booking.sms.enabled=false → no Telegram attempt, the (no-op) SMS sender only")
    void should_skipTelegram_when_smsGateOff() {
        GuestMessageRouter dark = routerWith(true, false);

        dark.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        verifyNoInteractions(telegramMessenger, linkService);
        assertThat(count("sms", "sms_gate_off")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("staging allow-list refusal → SMS, counted not_allow_listed (never transient_fallback)")
    void should_countNotAllowListed_when_allowListRefuses() {
        linked();
        doThrow(new TelegramNotAllowListedException("not listed")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);

        router.deliver(KIND, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        assertThat(count("sms", "not_allow_listed")).isEqualTo(1.0);
        assertThat(count("sms", "transient_fallback")).isZero();
    }

    @Test
    @DisplayName("Telegram-only: gates open + send OK → sendWaiting with the text, true, no SMS, no lookup")
    void should_sendViaGateWithoutSms_when_telegramOnlySucceeds() {

        boolean sent = router.deliverTelegramOnly(CHAT_ID, TEXT);

        assertThat(sent).isTrue();
        verify(telegramMessenger).sendWaiting(CHAT_ID, TEXT);
        verifyNoInteractions(smsService, linkService);
    }

    @Test
    @DisplayName("Telegram-only: any Telegram failure → false, dropped, never an SMS, link untouched")
    void should_dropWithoutSms_when_telegramOnlyFails() {
        doThrow(new TelegramRecipientGoneException("403")).when(telegramMessenger).sendWaiting(CHAT_ID, TEXT);

        boolean sent = router.deliverTelegramOnly(CHAT_ID, TEXT);

        assertThat(sent).isFalse();
        verifyNoInteractions(smsService, linkService);
    }

    @Test
    @DisplayName("Telegram-only: Telegram flag off or booking-SMS gate off → false, nothing sent on any channel")
    void should_sendNothing_when_telegramOnlyGated() {
        GuestMessageRouter flagOff = routerWith(false, true);
        GuestMessageRouter smsGateOff = routerWith(true, false);

        boolean flagOffSent = flagOff.deliverTelegramOnly(CHAT_ID, TEXT);
        boolean gateOffSent = smsGateOff.deliverTelegramOnly(CHAT_ID, TEXT);

        assertThat(flagOffSent).isFalse();
        assertThat(gateOffSent).isFalse();
        verifyNoInteractions(telegramMessenger, smsService, linkService);
    }

    @Test
    @DisplayName("allow-list: only the 3 routed callers (+ the Telegram-only details sender) may depend on the router; Telegram only via router/config")
    void should_confineRouterAndMessenger_when_scanningProductionClasses() {
        JavaClasses production = production();

        noClasses()
                .that().doNotHaveFullyQualifiedName(GuestMessageRouter.class.getName())
                .and().doNotHaveFullyQualifiedName(GuestReminderDispatcher.class.getName())
                .and().doNotHaveFullyQualifiedName(BookingSmsDispatcher.class.getName())
                .and().doNotHaveFullyQualifiedName(ReviewInviteSendJob.class.getName())
                // Phase 433: the post-link booking-details follow-up, via the Telegram-only entry point
                // (deliverTelegramOnly) so the bounded-wait gate stays owned by the router — never sendWaiting.
                .and().doNotHaveFullyQualifiedName(LinkedBookingDetailsSender.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(GuestMessageRouter.class.getName())
                .check(production);
        noClasses()
                .that().doNotHaveFullyQualifiedName(GuestMessageRouter.class.getName())
                .and().doNotHaveFullyQualifiedName(TelegramConfig.class.getName())
                .and().resideOutsideOfPackage("com.beautica.notification.telegram")
                .should().dependOnClassesThat().haveFullyQualifiedName(TelegramMessenger.class.getName())
                .check(production);
    }

    @Test
    @DisplayName("only the router calls TelegramMessenger#sendWaiting (the bounded-wait gate entry point)")
    void should_confineSendWaitingToRouter_when_scanningProductionClasses() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(GuestMessageRouter.class.getName())
                .and().resideOutsideOfPackage("com.beautica.notification.telegram")
                .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.core.domain.properties.HasName.Predicates.name("sendWaiting")))
                .check(production());
    }

    @Test
    @DisplayName("Kind.GUEST_PROVIDER_CANCELLATION (the one routed booking kind) is used only by NotificationService")
    void should_referenceRoutedKindOnlyFromNotificationService_when_scanningProductionClasses() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(NotificationService.class.getName())
                .and().doNotHaveFullyQualifiedName(BookingSmsDispatcher.class.getName())
                .and().doNotHaveFullyQualifiedName(BookingSmsDispatcher.Kind.class.getName())
                .should().accessField(BookingSmsDispatcher.Kind.class, "GUEST_PROVIDER_CANCELLATION")
                .check(production());
    }

    private static JavaClasses production() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.beautica");
        assertThat(production.stream().map(c -> c.getSimpleName()))
                .as("non-vacuous: the guarded classes are in the scan")
                .contains("GuestMessageRouter", "TelegramMessenger", "NotificationService", "PhoneOtpService");
        return production;
    }

    private GuestMessageRouter routerWith(boolean telegramEnabled, boolean smsEnabled) {
        return new GuestMessageRouter(smsService, telegramMessenger, linkService,
                GuestMessageRouters.telegramProperties(telegramEnabled), GuestMessageRouters.bookingSms(smsEnabled),
                meters);
    }

    private void linked() {
        when(linkService.findActiveForPhone(PHONE)).thenReturn(Optional.of(new ActiveLink(LINK_ID, CHAT_ID)));
    }

    private double count(String channel, String reason) {
        return meters.counter(GuestMessageRouter.METRIC, "kind", KIND.token(), "channel", channel, "reason", reason)
                .count();
    }
}
