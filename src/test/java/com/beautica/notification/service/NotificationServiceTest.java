package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService — unit")
class NotificationServiceTest {

    private static final String FRONTEND_BASE_URL = "https://app.beautica.ua";

    @Mock
    private EmailNotificationService emailService;
    @Mock
    private PushNotificationService pushService;
    @Mock
    private SmsService smsService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                emailService, pushService, smsService, new BookingSmsProperties(), FRONTEND_BASE_URL);
    }

    // -------------------------------------------------------------------------
    // notifyNewBooking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send email and push to master when notifyNewBooking is called")
    void should_sendEmailAndPushToMaster_when_notifyNewBookingCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        String bookingId = booking.getId().toString();

        service.notifyNewBooking(booking);

        verify(emailService).sendNewBookingEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(masterUserId),
                eq("Нове бронювання"),
                anyString(),
                eq(Map.of("type", "NEW_BOOKING", "bookingId", bookingId))
        );
    }

    @Test
    @DisplayName("should include client name and service name in push body when notifyNewBooking is called")
    void should_includClientAndServiceInPushBody_when_notifyNewBookingCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).contains("Тест Клієнт").contains("Тест послуга");
    }

    @Test
    @DisplayName("notifyNewBooking falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_useGuestIdentity_when_notifyNewBookingForGuestBooking() {
        // Guest (LINK) booking: null client (V89 chk_bookings_guest_fields). GuestBookingService
        // enqueues NEW_BOOKING for EVERY guest booking, so an unguarded booking.getClient()
        // dereference here NPEs the drain worker on 100% of guest bookings — the master is never
        // notified at all and the outbox row goes DEAD. Falls back to guestName/guestSurname,
        // mirroring BookingDetailResponse.from.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Олена", "Коваль");
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(emailService).sendNewBookingEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(eq(masterUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .as("push body must carry the guest's name, not throw or read a null client")
                .contains("Олена Коваль");
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — CONFIRMED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send confirmed email and push to client when status is CONFIRMED")
    void should_sendConfirmedEmailAndPushToClient_when_statusConfirmed() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.CONFIRMED);
        String bookingId = booking.getId().toString();

        service.notifyBookingStatusChanged(booking);

        verify(emailService).sendBookingConfirmedEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(clientUserId),
                eq("Бронювання підтверджено"),
                anyString(),
                eq(Map.of("type", "BOOKING_CONFIRMED", "bookingId", bookingId))
        );
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — DECLINED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send declined email and push to client when status is DECLINED")
    void should_sendDeclinedEmailAndPushToClient_when_statusDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        String bookingId = booking.getId().toString();

        service.notifyBookingStatusChanged(booking);

        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(clientUserId),
                eq("Бронювання скасовано"),
                anyString(),
                eq(Map.of("type", "BOOKING_DECLINED", "bookingId", bookingId))
        );
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — DECLINED, guest (LINK / null-client) booking (Phase 25.7)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 25.7: a declined GUEST booking sends exactly one SMS with the provider's note "
            + "(no email/push — a guest has no account)")
    void should_sendExactlyOneSms_when_guestBookingDeclined() {
        Booking booking = buildGuestBookingMockForDecline("Майстер зачинений сьогодні через хворобу");

        service.notifyBookingStatusChanged(booking);

        verify(smsService, org.mockito.Mockito.times(1)).send(eq("+380501234567"), anyString());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Phase 25.7: the guest decline SMS carries the service, master, and the provider's note")
    void should_includeServiceMasterAndNote_when_guestBookingDeclinedSmsBuilt() {
        Booking booking = buildGuestBookingMockForDecline("Майстер захворів");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .contains("Тест послуга")
                .contains("Тест Майстер")
                .contains("Майстер захворів");
    }

    @Test
    @DisplayName("Phase 25.7: the guest decline SMS truncates a long provider note to 120 characters")
    void should_truncateNote_when_guestBookingDeclinedSmsNoteExceeds120Chars() {
        Booking booking = buildGuestBookingMockForDecline("А".repeat(500));
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String noteSegment = textCaptor.getValue().substring(textCaptor.getValue().indexOf("Причина: "));
        assertThat(noteSegment.length()).isLessThanOrEqualTo(120 + "Причина: ".length());
        assertThat(noteSegment).endsWith("…");
    }

    @Test
    @DisplayName("Phase 25.9: a null provider note produces a coherent guest decline SMS — no "
            + "dangling \"Причина: \" label with nothing after it (the note is now optional for all roles)")
    void should_omitReasonLine_when_guestBookingDeclinedWithNullProviderComment() {
        Booking booking = buildGuestBookingMockForDecline(null);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(eq("+380501234567"), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("a null note must never leave a dangling reason label, found=%s", text)
                .doesNotContain("Причина")
                .contains("Тест послуга")
                .contains("Тест Майстер")
                .doesNotEndWith(" ")
                .doesNotEndWith("\n");
    }

    @Test
    @DisplayName("Phase 25.9: a blank (whitespace-only) provider note is treated the same as null — "
            + "no dangling reason label")
    void should_omitReasonLine_when_guestBookingDeclinedWithBlankProviderComment() {
        Booking booking = buildGuestBookingMockForDecline("   ");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(eq("+380501234567"), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("a whitespace-only note must not produce a reason label")
                .doesNotContain("Причина");
    }

    @Test
    @DisplayName("SEC MEDIUM: the guest decline SMS strips a URL from the provider's note — "
            + "a Beautica-branded SMS to a real OTP-verified phone must not carry a link")
    void should_stripUrlFromGuestDeclineSms_whenProviderCommentContainsLink() {
        Booking booking = buildGuestBookingMockForDecline(
                "Master is sick, see https://evil.example.com/phish?x=1 for a refund and www.another-evil.test/y too");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("no scheme-URL, www-host, or bare-domain-with-path may reach the SMS body, found=%s", text)
                .doesNotContain("http")
                .doesNotContain("www.")
                .doesNotContain("evil.example.com")
                .doesNotContain("another-evil.test");
        assertThat(text)
                .as("the non-URL parts of the provider's note must still reach the SMS")
                .contains("Master is sick")
                .contains("for a refund");
    }

    @Test
    @DisplayName("SEC MEDIUM (residual): the guest decline SMS strips a BARE domain with no scheme "
            + "and no path — iOS/Android auto-linkify word.tld even without http(s):// or a trailing path")
    void should_stripBareDomainWithoutPath_whenProviderCommentContainsLookalikeDomain() {
        Booking booking = buildGuestBookingMockForDecline("перевірте beautica-verify.com для деталей");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("bare domain-with-no-path must not reach the SMS body, found=%s", text)
                .doesNotContain("beautica-verify.com");
        assertThat(text)
                .as("the non-URL parts of the provider's note must still reach the SMS")
                .contains("перевірте")
                .contains("для деталей");
    }

    @Test
    @DisplayName("SEC MEDIUM (residual): normal Ukrainian prose with a sentence-ending period followed "
            + "by another word must survive the bare-domain strip UNCHANGED (false-positive guard)")
    void should_leaveOrdinaryUkrainianProseUnchanged_whenNoteHasSentenceEndingPeriod() {
        Booking booking = buildGuestBookingMockForDecline("Вибачте. Захворіла.");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("ordinary Cyrillic prose must not be mistaken for a domain")
                .contains("Вибачте. Захворіла.");
    }

    @Test
    @DisplayName("SEC HIGH regression: a domain immediately followed by a sentence-ending period "
            + "(no space before the period) must still be stripped — a URL is a common place for "
            + "a Ukrainian sentence to end, and the prior possessive-quantifier regex failed to "
            + "backtrack far enough to match the TLD in exactly this shape")
    void should_stripDomainFollowedByTrailingPeriod_whenNoteEndsMidSentence() {
        Booking booking = buildGuestBookingMockForDecline("Уточнення тут: beautica-verify.com. Дякуємо.");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("a domain immediately followed by a trailing period must still be stripped, found=%s", text)
                .doesNotContain("beautica-verify.com");
        assertThat(text)
                .contains("Уточнення тут:")
                .contains("Дякуємо.");
    }

    @Test
    @DisplayName("SEC MEDIUM regression: non-allowlisted, cheaply-registerable TLDs (.xyz/.top/.click) "
            + "are stripped exactly like .com — the token-neutralization approach has no TLD list to "
            + "bypass in the first place")
    void should_stripNonAllowlistedTld_whenProviderNoteContainsCheapPhishingDomain() {
        Booking booking = buildGuestBookingMockForDecline(
                "see evil-phish.xyz or evil-phish.top or evil-phish.click for details");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("non-allowlisted TLDs must be stripped exactly like .com, found=%s", text)
                .doesNotContain("evil-phish.xyz")
                .doesNotContain("evil-phish.top")
                .doesNotContain("evil-phish.click");
        assertThat(text).contains("for details");
    }

    @Test
    @DisplayName("every plausible URL shape (scheme, www-host, bare domain+path, domain:port, "
            + "uppercase domain, multi-label subdomain, obfuscated scheme) is stripped")
    void should_stripEveryPlausibleUrlShape_whenProviderNoteContainsThem() {
        Booking booking = buildGuestBookingMockForDecline(
                "http://evil.com/x https://evil.com www.evil.com evil.com/path evil.com:8080 "
                        + "EVIL.COM sub.evil.co.uk hxxp://evil.com/path see you soon");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("no URL-shaped token in any form may reach the SMS body, found=%s", text)
                .doesNotContainIgnoringCase("evil.com")
                .doesNotContainIgnoringCase("sub.evil.co.uk")
                .doesNotContain("http")
                .doesNotContain("www.")
                .doesNotContain("hxxp");
        assertThat(text).contains("see you soon");
    }

    @Test
    @DisplayName("LOW regression: an email-shaped token (e.g. an @-address) is dropped WHOLE — not "
            + "just the domain half, avoiding a dangling '@' fragment in the SMS for a provider who "
            + "put legitimate contact info in their note")
    void should_dropEmailShapedTokenWhole_whenProviderNoteContainsAtSign() {
        Booking booking = buildGuestBookingMockForDecline("contact x@evil.com for a refund please");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("the @-token must be dropped whole, no dangling '@' or partial fragment, found=%s", text)
                .doesNotContain("@")
                .doesNotContain("evil.com");
        assertThat(text).contains("contact").contains("for a refund please");
    }

    @Test
    @DisplayName("LOW regression: a bare IPv4 literal (with or without a path) is stripped — Android "
            + "Linkify auto-links a bare IP exactly like a hostname")
    void should_stripBareIpv4Literal_whenProviderNoteContainsRawIpAddress() {
        Booking booking = buildGuestBookingMockForDecline("visit 1.2.3.4/login or just 1.2.3.4 for info");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String text = textCaptor.getValue();
        assertThat(text)
                .as("bare IPv4, with or without a path, must be stripped, found=%s", text)
                .doesNotContain("1.2.3.4");
        assertThat(text).contains("visit").contains("for info");
    }

    @Test
    @DisplayName("false-positive guard: English short sentences ending in a period, each followed by "
            + "another word, must survive unchanged (not mistaken for a domain)")
    void should_leaveEnglishProseUnchanged_whenNoteHasShortSentencesEndingInPeriod() {
        Booking booking = buildGuestBookingMockForDecline("Sorry. Ill today. Let's reschedule.");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("ordinary English prose must not be mistaken for a domain")
                .contains("Sorry. Ill today. Let's reschedule.");
    }

    @Test
    @DisplayName("false-positive guard: a decimal/time value (digits, not letters, after the dot) "
            + "must survive unchanged")
    void should_leaveDecimalTimeUnchanged_whenNoteContainsDigitsAfterDot() {
        Booking booking = buildGuestBookingMockForDecline("Прийду о 15.30");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("a decimal/time value must not be mistaken for a domain (digits, not letters, follow the dot)")
                .contains("Прийду о 15.30");
    }

    @Test
    @DisplayName("false-positive guard: ordinary multi-sentence Ukrainian prose with normal "
            + "punctuation survives entirely unchanged")
    void should_leaveOrdinaryMultiSentenceUkrainianProseUnchanged_whenNoteHasNoLinkShapedTokens() {
        String note = "Доброго дня. Нажаль не зможу прийти сьогодні. Перенесемо запис на інший день, "
                + "будь ласка. Дякую за розуміння.";
        Booking booking = buildGuestBookingMockForDecline(note);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("ordinary multi-sentence Ukrainian prose must reach the SMS byte-for-byte")
                .contains(note);
    }

    @Test
    @DisplayName("PERF INFO: stripping a 1000-char adversarial note (the DTO/DB length ceiling on "
            + "provider_comment) completes in well under 50ms — regression guard against ever "
            + "reintroducing a backtracking-regex ReDoS shape into this path")
    void should_stripAdversarialNote_withinBoundedTime() {
        String adversarial = "a.".repeat(500); // 1000 chars, no valid trailing TLD/structure anywhere
        Booking booking = buildGuestBookingMockForDecline(adversarial);

        assertTimeout(Duration.ofMillis(50), () -> service.notifyBookingStatusChanged(booking));

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("SEC MEDIUM: the URL strip is an SMS-rendering-path-only transform — the decline "
            + "EMAIL path never reads (and therefore never mutates) the provider's note")
    void should_notStripUrl_when_appBookingDeclinedEmailRendered() {
        // Email/app rendering is NOT the smishing channel (booking-declined.html renders the note
        // with Thymeleaf th:text — inert plain text, no clickable link) so the note must reach it
        // unmodified. NotificationService proves this structurally: on the registered-client
        // DECLINED path it hands the SAME Booking to EmailNotificationService and never touches
        // getProviderComment() itself, so no transform of any kind — URL-strip included — can be
        // applied on the way to the email. If someone ever moved stripUrlsForSms() out of
        // buildGuestDeclineSms and onto a shared path, this test goes red.
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);

        service.notifyBookingStatusChanged(booking);

        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(booking));
        verify(booking, never()).getProviderComment();
    }

    @Test
    @DisplayName("LOW: truncating a long guest decline note never splits a surrogate pair at the cut boundary")
    void should_truncateForSms_withoutSplittingSurrogatePair() {
        // 118 filler chars (indices 0..117) + a 2-char emoji surrogate pair at indices 118-119
        // (exactly straddling the pre-fix cut point: SMS_COMMENT_MAX_LENGTH - 1 = 119) + more
        // filler so the total length exceeds 120 and truncation actually triggers.
        String prefix = "a".repeat(118);
        String emoji = "😀"; // 😀 — a real UTF-16 surrogate pair
        String longComment = prefix + emoji + "bcdef";
        Booking booking = buildGuestBookingMockForDecline(longComment);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(booking);

        verify(smsService).send(anyString(), textCaptor.capture());
        String noteSegment = textCaptor.getValue().substring(textCaptor.getValue().indexOf("Причина: ") + "Причина: ".length());
        assertThat(noteSegment)
                .as("the emoji must be dropped whole (not split) — the high surrogate at index 118 "
                        + "must never survive as an unpaired trailing code unit")
                .isEqualTo(prefix + "…");
        assertThat(Character.isHighSurrogate(noteSegment.charAt(noteSegment.length() - 2)))
                .as("no unpaired high surrogate immediately before the ellipsis")
                .isFalse();
    }

    @Test
    @DisplayName("Phase 25.7: an APP (registered-client) booking decline sends zero SMS")
    void should_sendZeroSms_when_appBookingDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);

        service.notifyBookingStatusChanged(booking);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("Phase 25.7: a guest booking CONFIRMED/COMPLETED/NOT_COMPLETED status stays a clean "
            + "no-op — no SMS, no email, no push, no NPE (regression guard for the track 24.x "
            + "guest null-deref fixes this branch sits next to)")
    void should_noOp_when_guestBookingStatusIsNotDeclined() {
        for (BookingStatus status : new BookingStatus[] {
                BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED
        }) {
            Booking booking = buildGuestBookingMock(UUID.randomUUID(), "Олена", "Коваль");
            when(booking.getStatus()).thenReturn(status);

            service.notifyBookingStatusChanged(booking);

            verify(smsService, never()).send(anyString(), anyString());
            verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
            verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
            verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
        }
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — COMPLETED / NOT_COMPLETED / other
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should not send email or push when status is COMPLETED")
    void should_notSendEmailOrPush_when_statusCompleted() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.COMPLETED);

        service.notifyBookingStatusChanged(booking);

        verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("should not send email or push when status is NOT_COMPLETED")
    void should_notSendEmailOrPush_when_statusNotCompleted() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.NOT_COMPLETED);

        service.notifyBookingStatusChanged(booking);

        verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // notifyClientCancelled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send cancelled email and push to master when notifyClientCancelled is called")
    void should_sendCancelledEmailAndPushToMaster_when_notifyClientCancelledCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CANCELLED);
        String bookingId = booking.getId().toString();

        service.notifyClientCancelled(booking);

        verify(emailService).sendClientCancelledEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(masterUserId),
                eq("Клієнт скасував бронювання"),
                anyString(),
                eq(Map.of("type", "CLIENT_CANCELLED", "bookingId", bookingId))
        );
    }

    @Test
    @DisplayName("notifyClientCancelled falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_useGuestIdentity_when_notifyClientCancelledForGuestBooking() {
        // CLIENT_CANCELLED is enqueued ONLY by BookingCancellationService.cancel — the public
        // guest cancel-link flow — so booking.getClient() is guaranteed null on every real call
        // of this method. An unguarded dereference here NPEs 100% of the time.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Іван", "Петренко");
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyClientCancelled(booking);

        verify(emailService).sendClientCancelledEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(eq(masterUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .as("push body must carry the guest's name, not throw or read a null client")
                .contains("Іван Петренко");
    }

    // -------------------------------------------------------------------------
    // notifyReviewRequested (Phase 18.5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifyReviewRequested targets the CLIENT (email + push) with a scheme-valid review URL")
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

        // Push is delivered to the CLIENT user id (not the master) with the REVIEW_REQUESTED payload.
        verify(pushService).sendToUser(
                eq(clientUserId),
                eq("Оцініть візит"),
                anyString(),
                eq(Map.of("type", "REVIEW_REQUESTED", "bookingId", bookingId))
        );
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
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("notifyReviewRequested truncates the push body when the service name is very long")
    void should_truncatePushBody_when_notifyReviewRequestedServiceNameExceeds256Chars() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.COMPLETED);
        when(booking.getMasterService().getServiceDefinition().getName()).thenReturn("А".repeat(500));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyReviewRequested(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        String body = bodyCaptor.getValue();
        assertThat(body.length()).isLessThanOrEqualTo(256);
        assertThat(body).endsWith("…");
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
    // Push body safety — null & length
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should truncate push body when service name exceeds 256 chars")
    void should_truncatePushBody_when_serviceNameExceeds256Chars() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        String longServiceName = "А".repeat(500);
        when(booking.getMasterService().getServiceDefinition().getName()).thenReturn(longServiceName);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        String body = bodyCaptor.getValue();
        assertThat(body.length()).isLessThanOrEqualTo(256);
        assertThat(body).endsWith("…");
    }

    @Test
    @DisplayName("should handle null client name gracefully when firstName is null")
    void should_handleNullClientNameGracefully_when_firstNameIsNull() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        when(booking.getClient().getFirstName()).thenReturn(null);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).doesNotContain("null");
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
        when(booking.getId()).thenReturn(UUID.randomUUID());
        lenient().when(booking.getStatus()).thenReturn(status);

        User clientUser = mock(User.class);
        lenient().when(clientUser.getId()).thenReturn(clientUserId);
        lenient().when(clientUser.getEmail()).thenReturn("client@example.com");
        lenient().when(clientUser.getFirstName()).thenReturn("Тест");
        lenient().when(clientUser.getLastName()).thenReturn("Клієнт");
        when(booking.getClient()).thenReturn(clientUser);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getId()).thenReturn(masterUserId);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        Master master = mock(Master.class);
        lenient().when(master.getUser()).thenReturn(masterUser);
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
        when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);
        lenient().when(booking.getGuestName()).thenReturn(guestName);
        lenient().when(booking.getGuestSurname()).thenReturn(guestSurname);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getId()).thenReturn(masterUserId);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        Master master = mock(Master.class);
        lenient().when(master.getUser()).thenReturn(masterUser);
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
        Booking booking = mock(Booking.class);
        // lenient: only read on the (untested-here) blank-guestPhone warning branch.
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);
        when(booking.getStatus()).thenReturn(BookingStatus.DECLINED);
        when(booking.getGuestPhone()).thenReturn("+380501234567");
        when(booking.getStartsAt()).thenReturn(OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        when(booking.getProviderComment()).thenReturn(providerComment);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getFirstName()).thenReturn("Тест");
        lenient().when(masterUser.getLastName()).thenReturn("Майстер");
        Master master = mock(Master.class);
        lenient().when(master.getUser()).thenReturn(masterUser);
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn("Тест послуга");
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }
}
