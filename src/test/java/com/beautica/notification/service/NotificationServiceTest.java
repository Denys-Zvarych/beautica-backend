package com.beautica.notification.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
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
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeout;
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

        service.notifyNewBooking(BookingVisit.single(booking));

        verify(emailService).sendNewBookingEmail(anyString(), eq(BookingVisit.single(booking)));
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

        service.notifyNewBooking(BookingVisit.single(booking));

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

        service.notifyNewBooking(BookingVisit.single(booking));

        verify(emailService).sendNewBookingEmail(anyString(), eq(BookingVisit.single(booking)));
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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(emailService).sendBookingConfirmedEmail(anyString(), eq(BookingVisit.single(booking)));
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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(smsService, org.mockito.Mockito.times(1)).send(eq("+380501234567"), anyString());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Phase 25.7: the guest decline SMS carries the service, master, and the provider's note")
    void should_includeServiceMasterAndNote_when_guestBookingDeclinedSmsBuilt() {
        Booking booking = buildGuestBookingMockForDecline("Майстер захворів");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(smsService).send(anyString(), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .contains("Тест послуга")
                .contains("Тест Майстер")
                .contains("Майстер захворів");
    }

    @Test
    @DisplayName("a provider-chosen service name containing a template placeholder is emitted "
            + "LITERALLY in the guest decline SMS — it must not be expanded into a second, fabricated "
            + "date/time line")
    void should_notExpandAPlaceholderInsideAServiceName_when_theGuestDeclineSmsIsBuilt() {
        // The call-site twin of GuestBookingServiceTest's confirmation-SMS case. buildGuestDeclineSms
        // substituted {serviceName} BEFORE {date}/{time} with chained String.replace, so each later
        // replace re-scanned the name it had just written in. A service named "Манікюр {date} о {time}"
        // therefore rendered a SECOND, provider-positioned date/time line inside copy the guest reads
        // as platform text — and the guest decline SMS is the ONLY channel a guest has (no account for
        // email/push), so there is nothing to cross-check it against.
        Booking booking = buildGuestBookingMockForDecline(null, "Манікюр {date} о {time}");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(smsService).send(anyString(), textCaptor.capture());
        String sms = textCaptor.getValue();
        assertThat(sms)
                .as("the literal braces from the DATA must survive as text, never be expanded. sms=%s", sms)
                .contains("Манікюр {date} о {time}");
        assertThat(countOccurrences(sms, "01.08.2026"))
                .as("the real date appears exactly once, where the TEMPLATE puts it. sms=%s", sms)
                .isEqualTo(1);
        assertThat(countOccurrences(sms, "10:00"))
                .as("the real time appears exactly once — a provider must not be able to fabricate a "
                        + "second appointment time. sms=%s", sms)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 25.7: the guest decline SMS truncates a long provider note to 120 characters")
    void should_truncateNote_when_guestBookingDeclinedSmsNoteExceeds120Chars() {
        Booking booking = buildGuestBookingMockForDecline("А".repeat(500));
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        assertTimeout(Duration.ofMillis(50), () -> service.notifyBookingStatusChanged(BookingVisit.single(booking)));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

    /**
     * Phase 22.1 (security MEDIUM): the guest-SMS branch must key on {@code BookingSource.LINK},
     * not on {@code client == null}.
     *
     * <p>A LINK guest owns their phone — the number is proven by the OTP they answered before the
     * booking existed. A STAFF walk-in's phone is <em>typed in by a salon employee</em> about a
     * third party and is verified by nothing. Both shapes have a null client, so a
     * {@code client == null} guard treats them identically and turns every staff-entered digit
     * string into an SMS destination the platform will send to on a provider's say-so: a typo
     * texts a stranger, and a deliberately-entered number makes the platform an on-demand SMS
     * relay addressed to anyone, with no consent step anywhere in the flow.
     *
     * <p>This is the narrowest possible statement of the fix — a STAFF decline sends nothing at
     * all — and it is deliberately asserted with {@code verifyNoInteractions} rather than
     * {@code never().send(...)}, so a future "just a short courtesy SMS" addition cannot slip
     * past by using a different {@code SmsService} method.
     */
    @Test
    @DisplayName("a declined STAFF walk-in booking sends NO SMS — the phone was typed by staff, "
            + "not proven by the recipient (unlike a LINK guest's OTP-verified number)")
    void should_notSendDeclineSms_when_staffWalkInBookingDeclined() {
        Booking booking = buildStaffWalkInBookingMockForDecline("Майстер захворів");

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verifyNoInteractions(smsService);
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    /**
     * The positive half of the pair: the LINK path this gate must NOT break. Together these two
     * pin the gate as "SMS iff LINK" — either one alone is satisfied by a gate that is simply
     * always-off or always-on.
     */
    @Test
    @DisplayName("a declined LINK guest booking still sends its SMS once the source gate is in place")
    void should_stillSendDeclineSms_when_linkGuestBookingDeclined() {
        Booking booking = buildGuestBookingMockForDecline("Майстер захворів");

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(smsService).send(eq("+380501234567"), anyString());
    }

    @Test
    @DisplayName("Phase 25.7: an APP (registered-client) booking decline sends zero SMS")
    void should_sendZeroSms_when_appBookingDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

            service.notifyBookingStatusChanged(BookingVisit.single(booking));

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

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

        verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("should not send email or push when status is NOT_COMPLETED")
    void should_notSendEmailOrPush_when_statusNotCompleted() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.NOT_COMPLETED);

        service.notifyBookingStatusChanged(BookingVisit.single(booking));

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
    // notifyClosureReminder (Phase 29.5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifyClosureReminder targets the PROVIDER (email + push), never the client")
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

        // Push is delivered to the PROVIDER's user id with a CLOSURE_REMINDER payload.
        verify(pushService).sendToUser(
                eq(masterUserId),
                eq("Позначте візит"),
                anyString(),
                eq(Map.of("type", "CLOSURE_REMINDER", "bookingId", bookingId))
        );
        verifyNoMoreInteractions(smsService);
    }

    @Test
    @DisplayName("notifyClosureReminder falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_useGuestIdentity_when_notifyClosureReminderForGuestBooking() {
        // A guest (LINK) booking still has a real master to nudge — only the client account is
        // absent (V89 chk_bookings_guest_fields) — so unlike notifyReviewRequested this must NOT
        // no-op; it must still notify the provider, using the guest's name in the copy.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Олена", "Коваль");
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyClosureReminder(booking);

        verify(emailService).sendClosureReminderEmail(anyString(), eq(booking), anyString());
        verify(pushService).sendToUser(eq(masterUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .as("push body must carry the guest's name, not throw or read a null client")
                .contains("Олена Коваль");
    }

    @Test
    @DisplayName("notifyClosureReminder truncates the push body when the service name is very long")
    void should_truncatePushBody_when_notifyClosureReminderServiceNameExceeds256Chars() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.CONFIRMED);
        when(booking.getMasterService().getServiceDefinition().getName()).thenReturn("А".repeat(500));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyClosureReminder(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        String body = bodyCaptor.getValue();
        assertThat(body.length()).isLessThanOrEqualTo(256);
        assertThat(body).endsWith("…");
    }

    // -------------------------------------------------------------------------
    // notifySalonClosed (Phase 269/293)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifySalonClosed sends email + push to a registered client, never SMS")
    void should_sendEmailAndPush_when_notifySalonClosedForRegisteredClient() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        BookingVisit visit = BookingVisit.single(booking);
        String bookingId = booking.getId().toString();

        service.notifySalonClosed(visit);

        verify(emailService).sendSalonClosedEmail(eq("client@example.com"), eq(visit));
        verify(pushService).sendToUser(
                eq(clientUserId),
                eq("Салон закрито"),
                anyString(),
                eq(Map.of("type", "SALON_CLOSED", "bookingId", bookingId))
        );
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifySalonClosed sends SMS to the OTP-verified guestPhone for a LINK guest visit, "
            + "and never touches the email/push channels a guest has no account for")
    void should_sendSms_when_notifySalonClosedForGuestVisit() {
        Booking booking = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        BookingVisit visit = BookingVisit.single(booking);

        service.notifySalonClosed(visit);

        verify(smsService).send(eq("+380501234567"), anyString());
        verifyNoInteractions(emailService);
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("notifySalonClosed never sends SMS for a STAFF walk-in — the phone was typed by "
            + "staff, not proven by the recipient (same gate as notifyBookingStatusChanged)")
    void should_notSendSms_when_notifySalonClosedForStaffWalkIn() {
        Booking booking = buildStaffWalkInBookingMockForDecline(null);
        BookingVisit visit = BookingVisit.single(booking);

        service.notifySalonClosed(visit);

        verifyNoInteractions(smsService);
        verifyNoInteractions(emailService);
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("notifySalonClosed's push body names EVERY declined service of a multi-service "
            + "visit (D12) — the whole visit was collapsed to ONE outbox entry, so the copy must "
            + "not name only the representative's service")
    void should_namePushBodyForWholeVisit_when_notifySalonClosedForMultiServiceVisit() {
        UUID clientUserId = UUID.randomUUID();
        Booking lead = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        BookingVisit visit = visitOf(lead, 3);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifySalonClosed(visit);

        verify(emailService).sendSalonClosedEmail(anyString(), eq(visit));
        verify(pushService).sendToUser(eq(clientUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .contains("3 послуги")
                .doesNotContain("скасовано Тест послуга");
    }

    @Test
    @DisplayName("notifySalonClosed's guest SMS names EVERY declined service of a multi-service visit")
    void should_nameSmsForWholeVisit_when_notifySalonClosedForMultiServiceGuestVisit() {
        Booking lead = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        BookingVisit visit = visitOf(lead, 3);
        ArgumentCaptor<String> smsCaptor = ArgumentCaptor.forClass(String.class);

        service.notifySalonClosed(visit);

        verify(smsService).send(eq("+380501234567"), smsCaptor.capture());
        assertThat(smsCaptor.getValue()).contains("3 послуги");
    }

    @Test
    @DisplayName("notifySalonClosed swallows an SMS gateway failure (D11) — the salon deletion this "
            + "notification is dispatched from a queue AFTER must never see this exception")
    void should_swallowException_when_salonClosedSmsGatewayThrows() {
        Booking booking = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        doThrow(new RuntimeException("gateway down")).when(smsService).send(anyString(), anyString());

        assertThatCode(() -> service.notifySalonClosed(BookingVisit.single(booking)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("notifySalonClosed — QA-authored (Phase 293 audit, case 16): the raw OTP-verified "
            + "guest_phone is never logged when the SMS gateway throws — only the exception's class "
            + "name reaches the log line (Anti-Bug §I / PhoneMask discipline; log-capture pattern "
            + "mirrored from TurbosmsServiceTest, which pins the SAME discipline at the actual "
            + "gateway boundary — this test pins it at NotificationService's own catch block, one "
            + "layer up, which has no PhoneMask call at all today because it never references the "
            + "phone variable in its log message; a future edit that adds it back must trip this)")
    void should_notLogRawPhone_when_salonClosedSmsFails() {
        Booking booking = buildGuestBookingMockForSalonClosed(
                "Тест послуга", OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        doThrow(new RuntimeException("gateway down")).when(smsService).send(anyString(), anyString());

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(NotificationService.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        serviceLogger.setLevel(Level.WARN);
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
                .as("guest_phone must never appear in a log line emitted on SMS failure")
                .doesNotContain("+380501234567");
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
    @DisplayName("notifyNewBooking pushes the numeral phrase «3 послуги», not just the lead service, "
            + "when the visit carries three services")
    void should_pushServiceCountPhrase_when_notifyNewBookingForMultiServiceVisit() {
        UUID masterUserId = UUID.randomUUID();
        Booking lead = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        BookingVisit visit = visitOf(lead, 3);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(visit);

        verify(emailService).sendNewBookingEmail(anyString(), eq(visit));
        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .as("a 3-service visit must be announced as a count, never as the lead service alone")
                .contains("3 послуги")
                .doesNotContain("забронював Тест послуга");
    }

    @Test
    @DisplayName("notifyNewBooking uses the «послуг» genitive-plural form when the visit carries five services")
    void should_pushGenitivePluralForm_when_notifyNewBookingForFiveServiceVisit() {
        UUID masterUserId = UUID.randomUUID();
        Booking lead = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(visitOf(lead, 5));

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).contains("5 послуг").doesNotContain("5 послуги");
    }

    @Test
    @DisplayName("notifyBookingStatusChanged CONFIRMED pushes the numeral phrase for a multi-service "
            + "visit and forwards the whole visit to the confirmation email")
    void should_pushServiceCountPhrase_when_multiServiceVisitConfirmed() {
        UUID clientUserId = UUID.randomUUID();
        Booking lead = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.CONFIRMED);
        BookingVisit visit = visitOf(lead, 2);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(visit);

        verify(emailService).sendBookingConfirmedEmail(anyString(), eq(visit));
        verify(pushService).sendToUser(eq(clientUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).contains("2 послуги");
    }

    @Test
    @DisplayName("a single-service visit still pushes the SERVICE NAME — the pre-visit wording is unchanged")
    void should_pushServiceName_when_notifyNewBookingForSingleServiceVisit() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(BookingVisit.single(booking));

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .isEqualTo("Клієнт Тест Клієнт забронював Тест послуга")
                .doesNotContain("послуги")
                .doesNotContain("послуг ");
    }

    @Test
    @DisplayName("a DECLINED item of a multi-service visit names ONLY the declined service — a "
            + "decline is enqueued per item, so naming the whole visit would misreport it")
    void should_nameOnlyTheDeclinedService_when_oneItemOfAMultiServiceVisitIsDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking lead = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        BookingVisit visit = visitOf(lead, 3);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(visit);

        // The counterpart of the CONFIRMED case above, and the guard on the asymmetry the
        // production comment documents: CONFIRMED is enqueued ONCE per visit so it must name every
        // service, DECLINED is enqueued per item so it must NOT. Without this, a "make it
        // consistent" refactor routing DECLINED through bookedSubject() would tell the client
        // «Ваше бронювання на 3 послуги скасовано» when only one service was actually cancelled.
        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(lead));
        verify(pushService).sendToUser(eq(clientUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .isEqualTo("Ваше бронювання на Тест послуга скасовано")
                .doesNotContain("3 послуги");
    }

    @Test
    @DisplayName("a WHOLE-VISIT decline names EVERY cancelled service — the client must not be left "
            + "believing the rest of the visit still stands")
    void should_nameEveryService_when_theWholeVisitIsDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking lead = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        // The counterpart of the per-item case above, and the OTHER half of the asymmetry:
        // AppointmentTransitionService#declineAppointment moves the header AND every item to
        // DECLINED but enqueues ONE STATUS_CHANGED against items.get(0). Naming only that lead —
        // the pre-fix behaviour — told the client one service was cancelled while the whole visit
        // was gone, and they turned up for the rest.
        BookingVisit visit = wholeVisitDecline(lead, 3);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(visit);

        verify(emailService).sendVisitDeclinedEmail(anyString(), eq(visit));
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any(Booking.class));
        verify(pushService).sendToUser(eq(clientUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).isEqualTo("Ваше бронювання на 3 послуги скасовано");
    }

    @Test
    @DisplayName("a single-service visit whose appointment header is DECLINED keeps the pre-visit "
            + "decline wording — one service, named")
    void should_keepTheSingleServiceDeclineWording_when_aOneItemVisitHeaderIsDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking lead = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        BookingVisit visit = wholeVisitDecline(lead, 1);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyBookingStatusChanged(visit);

        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(lead));
        verify(pushService).sendToUser(eq(clientUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).isEqualTo("Ваше бронювання на Тест послуга скасовано");
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

        service.notifyNewBooking(BookingVisit.single(booking));

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

        service.notifyNewBooking(BookingVisit.single(booking));

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
        when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);
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
        when(booking.getGuestPhone()).thenReturn("+380501234567");
        when(booking.getStartsAt()).thenReturn(OffsetDateTime.parse("2026-08-01T10:00:00+03:00"));
        when(booking.getProviderComment()).thenReturn(providerComment);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getFirstName()).thenReturn("Тест");
        lenient().when(masterUser.getLastName()).thenReturn("Майстер");
        // A REAL Master, not a mock (phase 294): the notification paths read the provider name
        // through Master#displayFirstName()/#displayLastName(), whose attached-vs-detached branch
        // only executes on a real instance.
        Master master = Master.builder().user(masterUser).build();
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
