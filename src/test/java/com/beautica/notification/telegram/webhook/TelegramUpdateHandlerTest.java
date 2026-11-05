package com.beautica.notification.telegram.webhook;

import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.link.ClaimResult;
import com.beautica.notification.telegram.link.ContactProof;
import com.beautica.notification.telegram.link.ProofResult;
import com.beautica.notification.telegram.link.TelegramLinkService;
import com.beautica.notification.telegram.webhook.TelegramBotReplies.Reply;
import com.beautica.notification.telegram.webhook.TelegramUpdate.Chat;
import com.beautica.notification.telegram.webhook.TelegramUpdate.ChatMember;
import com.beautica.notification.telegram.webhook.TelegramUpdate.ChatMemberUpdated;
import com.beautica.notification.telegram.webhook.TelegramUpdate.Contact;
import com.beautica.notification.telegram.webhook.TelegramUpdate.Message;
import com.beautica.notification.telegram.webhook.TelegramUpdate.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramUpdateHandler — conversation table (phase 406 D5)")
class TelegramUpdateHandlerTest {

    private static final long CHAT = 7_123_456_789L;
    private static final String TOKEN = "A".repeat(43);
    private static final String PHONE = "+380501234567";
    private static final UUID BOOKING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final byte[] PHONE_HASH = {1, 2, 3, 4};

    @Mock private TelegramLinkService linkService;
    @Mock private TelegramChatAllowList allowList;
    @Mock private LinkedBookingDetailsSender bookingDetailsSender;

    private TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramUpdateHandler(linkService, allowList, bookingDetailsSender);
        lenient().when(allowList.allows(anyLong())).thenReturn(true);
    }

    private static TelegramUpdate text(String text) {
        return new TelegramUpdate(1L, new Message(new Chat(CHAT, "private"), new User(CHAT), text, null), null);
    }

    private static TelegramUpdate contact(Long contactUserId, Long fromId) {
        return new TelegramUpdate(1L, new Message(new Chat(CHAT, "private"),
                fromId == null ? null : new User(fromId), null, new Contact(PHONE, contactUserId)), null);
    }

    private static TelegramUpdate membership(String type, String status) {
        return new TelegramUpdate(1L, null, new ChatMemberUpdated(new Chat(CHAT, type), new ChatMember(status)));
    }

    private Reply reply(TelegramUpdate update) {
        Optional<Reply> reply = handler.handle(update);
        assertThat(reply).isPresent();
        assertThat(reply.get().method()).isEqualTo("sendMessage");
        assertThat(reply.get().chatId()).isEqualTo(CHAT);
        return reply.get();
    }

    @Test
    @DisplayName("/start with a fresh token claims the link and asks the guest to share their phone via the share keyboard")
    void should_askForPhoneWithShareKeyboard_when_startClaims() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.CLAIMED);

        Reply reply = reply(text("/start " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.ASK_PHONE);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
    }

    @Test
    @DisplayName("/start on an already-linked chat replies «linked» with the unlink keyboard, claims once and never reactivates")
    void should_claimOnceWithoutReactivate_when_claimAnswersAlreadyLinked() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.ALREADY_LINKED);

        Reply reply = reply(text("/start " + TOKEN));

        verify(linkService, times(1)).claim(TOKEN, CHAT);
        verify(linkService, never()).reactivate(anyLong());
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.LINKED);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.UNLINK_KEYBOARD);
    }

    @Test
    @DisplayName("/start rejected for a chat that had blocked the bot reactivates the link, retries the claim and replies «linked»")
    void should_reactivateThenReclaim_when_invalidAndChatHadBlockedLink() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.INVALID, ClaimResult.ALREADY_LINKED);
        when(linkService.reactivate(CHAT)).thenReturn(1);

        Reply reply = reply(text("/start " + TOKEN));

        InOrder order = inOrder(linkService);
        order.verify(linkService).claim(TOKEN, CHAT);
        order.verify(linkService).reactivate(CHAT);
        order.verify(linkService).claim(TOKEN, CHAT);
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.LINKED);
    }

    @Test
    @DisplayName("/start rejected with nothing to reactivate claims only once and replies «invalid link»")
    void should_notReclaim_when_invalidAndNothingReactivated() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.INVALID);
        when(linkService.reactivate(CHAT)).thenReturn(0);

        Reply reply = reply(text("/start " + TOKEN));

        verify(linkService, times(1)).claim(TOKEN, CHAT);
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.INVALID_LINK);
    }

    @ParameterizedTest
    @DisplayName("/start with a payload that is not token-shaped replies «invalid link» and never tries to reactivate")
    @ValueSource(strings = {"garbage", "A", "%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%"})
    void should_notReactivate_when_startPayloadIsNotTokenShaped(String payload) {
        when(linkService.claim(payload, CHAT)).thenReturn(ClaimResult.INVALID);

        Reply reply = reply(text("/start " + payload));

        verify(linkService, never()).reactivate(anyLong());
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.INVALID_LINK);
    }

    @Test
    @DisplayName("busy webhook answers a contact share with «try again» and keeps the share keyboard, without touching the link service")
    void should_returnShareKeyboard_when_busyReplyForContactShare() {
        Reply reply = handler.busyReply(contact(CHAT, CHAT)).orElseThrow();

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("busy webhook answers a text command without changing the keyboard")
    void should_leaveKeyboardUnchanged_when_busyReplyForText() {
        Reply reply = handler.busyReply(text("/stop")).orElseThrow();

        assertThat(reply.replyMarkup()).isNull();
    }

    @Test
    @DisplayName("/stop during a row-lock timeout replies «try again» and leaves the keyboard unchanged")
    void should_replyTryAgain_when_unlinkHitsLockTimeout() {
        when(linkService.unlinkChat(CHAT)).thenThrow(new PessimisticLockingFailureException("55P03"));

        Reply reply = reply(text("/stop"));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isNull();
    }

    @Test
    @DisplayName("bot-blocked event during a query timeout is swallowed with no reply")
    void should_swallowWithoutReply_when_membershipHitsQueryTimeout() {
        doThrow(new QueryTimeoutException("55P03")).when(linkService).markBlocked(CHAT);

        Optional<Reply> reply = handler.handle(membership("private", "kicked"));

        assertThat(reply).isEmpty();
    }

    @Test
    @DisplayName("busy webhook answers an allow-listed private chat with «try again» and never calls the link service")
    void should_answerTryAgainWithoutServiceCall_when_busyReplyForListedPrivateChat() {
        Reply reply = handler.busyReply(text("/start " + TOKEN)).orElseThrow();

        assertThat(reply.chatId()).isEqualTo(CHAT);
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("busy webhook stays silent for an unlisted chat, a group chat and a membership event")
    void should_answerNothing_when_busyReplyForUnlistedGroupOrMembership() {
        when(allowList.allows(CHAT)).thenReturn(false);
        TelegramUpdate group = new TelegramUpdate(1L,
                new Message(new Chat(-100L, "group"), new User(CHAT), "/stop", null), null);

        assertThat(handler.busyReply(text("/stop"))).isEmpty();
        assertThat(handler.busyReply(group)).isEmpty();
        assertThat(handler.busyReply(membership("private", "kicked"))).isEmpty();
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("/start with an unknown or expired token replies «invalid link» and removes the keyboard")
    void should_replyInvalidAndRemoveKeyboard_when_claimInvalid() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.INVALID);

        Reply reply = reply(text("/start " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.INVALID_LINK);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @Test
    @DisplayName("/Start@bot_name with a token is treated like /start and asks for the phone")
    void should_acceptBotSuffixedCommand_when_startAddressedToBot() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.CLAIMED);

        Reply reply = reply(text("/Start@beautica_app_bot " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.ASK_PHONE);
    }

    @Test
    @DisplayName("/start during a row-lock timeout replies «try again» and leaves the keyboard unchanged")
    void should_replyTryAgain_when_claimHitsLockTimeout() {
        when(linkService.claim(TOKEN, CHAT)).thenThrow(new PessimisticLockingFailureException("55P03"));

        Reply reply = reply(text("/start " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isNull();
    }

    @ParameterizedTest
    @DisplayName("/start without a token, /help or free text replies with help, keeps the keyboard and never calls the link service")
    @ValueSource(strings = {"/start", "/start   ", "/help", "привіт"})
    void should_replyHelpWithoutKeyboardChange_when_noTokenOrOtherText(String text) {
        Reply reply = reply(text(text));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.HELP);
        assertThat(reply.replyMarkup()).isNull();
        verifyNoInteractions(linkService);
    }

    @ParameterizedTest
    @DisplayName("/stop or an unlink button label (any case for /stop, padded labels) unlinks the chat, confirms and removes the keyboard")
    @ValueSource(strings = {"/stop", "/STOP", "Відписатися від Telegram", "  Відписатися від Telegram ",
            "Повернутися до SMS", "  Повернутися до SMS "})
    void should_unlinkAndRemoveKeyboard_when_stopOrUnlinkButton(String text) {
        Reply reply = reply(text(text));

        verify(linkService).unlinkChat(CHAT);
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.STOPPED);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @ParameterizedTest
    @DisplayName("text that only resembles an unlink button label replies with help and keeps the link")
    @ValueSource(strings = {"Відписатися", "від Telegram", "Відписатися від Telegram зараз",
            "Будь ласка, Відписатися від Telegram", "відписатися від telegram", "Відписатися від Telegram!",
            "Повернутися", "Повернутися до SMS будь ласка", "Хочу Повернутися до SMS", "повернутися до sms"})
    void should_replyHelpAndKeepLink_when_textIsNearMissOfUnlinkLabel(String text) {
        Reply reply = reply(text(text));

        assertThat(reply.text()).as("near-miss «%s» must not unlink", text).isEqualTo(TelegramBotReplies.HELP);
        assertThat(reply.replyMarkup()).isNull();
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("«linked» reply quotes the current unlink button label and never the legacy one")
    void should_nameCurrentUnlinkLabel_when_linkedReplyText() {
        assertThat(TelegramBotReplies.LINKED)
                .contains("«" + TelegramBotReplies.UNLINK_BUTTON_TEXT + "»")
                .doesNotContain(TelegramBotReplies.LEGACY_UNLINK_BUTTON_TEXT);
    }

    @Test
    @DisplayName("guest shares their own phone matching the booking: link confirmed and the unlink keyboard shown")
    void should_replyLinkedWithUnlinkKeyboard_when_contactMatches() {
        when(linkService.proveContactForBooking(CHAT, PHONE, CHAT, CHAT))
                .thenReturn(ContactProof.linked(BOOKING_ID, PHONE_HASH));

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.LINKED);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.UNLINK_KEYBOARD);
    }

    @Test
    @DisplayName("guest shares a phone that differs from the booking: «mismatch» reply and the keyboard removed")
    void should_replyMismatchAndRemoveKeyboard_when_contactDiffers() {
        when(linkService.proveContactForBooking(CHAT, PHONE, CHAT, CHAT))
                .thenReturn(ContactProof.of(ProofResult.MISMATCH));

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.MISMATCH);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @Test
    @DisplayName("guest shares the matching phone: booking details are scheduled exactly once for that booking")
    void should_scheduleBookingDetailsOnce_when_contactMatches() {
        when(linkService.proveContactForBooking(CHAT, PHONE, CHAT, CHAT))
                .thenReturn(ContactProof.linked(BOOKING_ID, PHONE_HASH));

        reply(contact(CHAT, CHAT));

        verify(bookingDetailsSender, times(1)).scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);
        verifyNoMoreInteractions(bookingDetailsSender);
    }

    @ParameterizedTest
    @EnumSource(value = ProofResult.class, names = {"MISMATCH", "NOT_OWN_CONTACT", "NO_PENDING"})
    @DisplayName("contact share that does not link (mismatch, not own contact, no pending claim) schedules no booking details")
    void should_scheduleNoBookingDetails_when_proofDoesNotLink(ProofResult result) {
        when(linkService.proveContactForBooking(CHAT, PHONE, CHAT, CHAT)).thenReturn(ContactProof.of(result));

        reply(contact(CHAT, CHAT));

        verifyNoInteractions(bookingDetailsSender);
    }

    @Test
    @DisplayName("contact share during a row-lock timeout schedules no booking details")
    void should_scheduleNoBookingDetails_when_proofHitsLockTimeout() {
        when(linkService.proveContactForBooking(CHAT, PHONE, CHAT, CHAT))
                .thenThrow(new PessimisticLockingFailureException("55P03"));

        reply(contact(CHAT, CHAT));

        verifyNoInteractions(bookingDetailsSender);
    }

    @ParameterizedTest
    @EnumSource(ClaimResult.class)
    @DisplayName("/start never schedules booking details, whatever the claim result, including an already-linked re-tap")
    void should_scheduleNoBookingDetails_when_startIsHandled(ClaimResult result) {
        lenient().when(linkService.claim(TOKEN, CHAT)).thenReturn(result);

        reply(text("/start " + TOKEN));

        verifyNoInteractions(bookingDetailsSender);
    }

    @Test
    @DisplayName("guest forwards someone else's contact: asked again for their own phone with the share keyboard")
    void should_askOwnContactAgain_when_contactIsForwarded() {
        when(linkService.proveContactForBooking(CHAT, PHONE, 42L, CHAT))
                .thenReturn(ContactProof.of(ProofResult.NOT_OWN_CONTACT));

        Reply reply = reply(contact(42L, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.NOT_OWN_CONTACT);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
    }

    @Test
    @DisplayName("contact message without a sender replies «not your contact» and never calls the link service")
    void should_replyNotOwnWithoutServiceCall_when_contactHasNoSender() {
        Reply reply = reply(contact(CHAT, null));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.NOT_OWN_CONTACT);
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("contact shared with no pending /start claim replies with help and removes the keyboard")
    void should_replyHelpAndRemoveKeyboard_when_contactHasNoPendingClaim() {
        when(linkService.proveContactForBooking(CHAT, PHONE, CHAT, CHAT))
                .thenReturn(ContactProof.of(ProofResult.NO_PENDING));

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.HELP);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @Test
    @DisplayName("contact share during a row-lock timeout replies «try again» and keeps the share keyboard")
    void should_replyTryAgainWithShareKeyboard_when_proofHitsLockTimeout() {
        when(linkService.proveContactForBooking(CHAT, PHONE, CHAT, CHAT))
                .thenThrow(new PessimisticLockingFailureException("55P03"));

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
    }

    @Test
    @DisplayName("guest blocks the bot in a private chat: link marked blocked, no reply sent")
    void should_markBlockedWithoutReply_when_kicked() {
        Optional<Reply> reply = handler.handle(membership("private", "kicked"));

        assertThat(reply).isEmpty();
        verify(linkService).markBlocked(CHAT);
    }

    @Test
    @DisplayName("guest unblocks the bot in a private chat: link reactivated, no reply sent")
    void should_reactivateWithoutReply_when_memberAgain() {
        Optional<Reply> reply = handler.handle(membership("private", "member"));

        assertThat(reply).isEmpty();
        verify(linkService).reactivate(CHAT);
    }

    @Test
    @DisplayName("membership event with an unsupported status or from a group chat is ignored")
    void should_ignoreMembership_when_statusUnsupportedOrChatNotPrivate() {
        handler.handle(membership("private", "left"));
        handler.handle(membership("group", "kicked"));

        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("/start from a group chat is ignored with no reply, no allow-list check and no link service call")
    void should_ignoreWithoutServiceCall_when_chatNotPrivate() {
        TelegramUpdate group = new TelegramUpdate(1L,
                new Message(new Chat(-100L, "group"), new User(CHAT), "/start " + TOKEN, null), null);

        Optional<Reply> reply = handler.handle(group);

        assertThat(reply).isEmpty();
        verifyNoInteractions(linkService, allowList);
    }

    @Test
    @DisplayName("chat not on the allow-list: /start, contact share and block events are dropped with no reply and no link service call")
    void should_dropWithoutServiceCall_when_chatNotAllowListed() {
        when(allowList.allows(CHAT)).thenReturn(false);

        Optional<Reply> start = handler.handle(text("/start " + TOKEN));
        Optional<Reply> share = handler.handle(contact(CHAT, CHAT));
        Optional<Reply> kicked = handler.handle(membership("private", "kicked"));

        assertThat(start).isEmpty();
        assertThat(share).isEmpty();
        assertThat(kicked).isEmpty();
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("message without text (e.g. a sticker) or an unsupported update type is ignored")
    void should_ignore_when_messageHasNoTextOrUpdateIsUnsupported() {
        TelegramUpdate sticker = new TelegramUpdate(1L,
                new Message(new Chat(CHAT, "private"), new User(CHAT), null, null), null);

        assertThat(handler.handle(sticker)).isEmpty();
        assertThat(handler.handle(new TelegramUpdate(1L, null, null))).isEmpty();
        verifyNoInteractions(linkService);
    }
}
