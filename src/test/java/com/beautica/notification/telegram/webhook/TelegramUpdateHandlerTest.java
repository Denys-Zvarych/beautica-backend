package com.beautica.notification.telegram.webhook;

import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.link.ClaimResult;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramUpdateHandler — conversation table (phase 406 D5)")
class TelegramUpdateHandlerTest {

    private static final long CHAT = 7_123_456_789L;
    private static final String TOKEN = "A".repeat(43);
    private static final String PHONE = "+380501234567";

    @Mock private TelegramLinkService linkService;
    @Mock private TelegramChatAllowList allowList;

    private TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramUpdateHandler(linkService, allowList);
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
    @DisplayName("ask for phone with share keyboard when start claims")
    void should_askForPhoneWithShareKeyboard_when_startClaims() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.CLAIMED);

        Reply reply = reply(text("/start " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.ASK_PHONE);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
    }

    @Test
    @DisplayName("claim once without reactivate when claim answers already linked")
    void should_claimOnceWithoutReactivate_when_claimAnswersAlreadyLinked() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.ALREADY_LINKED);

        Reply reply = reply(text("/start " + TOKEN));

        verify(linkService, times(1)).claim(TOKEN, CHAT);
        verify(linkService, never()).reactivate(anyLong());
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.LINKED);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.UNLINK_KEYBOARD);
    }

    @Test
    @DisplayName("reactivate then reclaim when invalid and chat had blocked link")
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
    @DisplayName("not reclaim when invalid and nothing reactivated")
    void should_notReclaim_when_invalidAndNothingReactivated() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.INVALID);
        when(linkService.reactivate(CHAT)).thenReturn(0);

        Reply reply = reply(text("/start " + TOKEN));

        verify(linkService, times(1)).claim(TOKEN, CHAT);
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.INVALID_LINK);
    }

    @ParameterizedTest
    @DisplayName("not reactivate when start payload is not token shaped")
    @ValueSource(strings = {"garbage", "A", "%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%"})
    void should_notReactivate_when_startPayloadIsNotTokenShaped(String payload) {
        when(linkService.claim(payload, CHAT)).thenReturn(ClaimResult.INVALID);

        Reply reply = reply(text("/start " + payload));

        verify(linkService, never()).reactivate(anyLong());
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.INVALID_LINK);
    }

    @Test
    @DisplayName("return share keyboard when busy reply for contact share")
    void should_returnShareKeyboard_when_busyReplyForContactShare() {
        Reply reply = handler.busyReply(contact(CHAT, CHAT)).orElseThrow();

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("leave keyboard unchanged when busy reply for text")
    void should_leaveKeyboardUnchanged_when_busyReplyForText() {
        Reply reply = handler.busyReply(text("/stop")).orElseThrow();

        assertThat(reply.replyMarkup()).isNull();
    }

    @Test
    @DisplayName("reply try again when unlink hits lock timeout")
    void should_replyTryAgain_when_unlinkHitsLockTimeout() {
        when(linkService.unlinkChat(CHAT)).thenThrow(new PessimisticLockingFailureException("55P03"));

        Reply reply = reply(text("/stop"));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isNull();
    }

    @Test
    @DisplayName("swallow without reply when membership hits query timeout")
    void should_swallowWithoutReply_when_membershipHitsQueryTimeout() {
        doThrow(new QueryTimeoutException("55P03")).when(linkService).markBlocked(CHAT);

        Optional<Reply> reply = handler.handle(membership("private", "kicked"));

        assertThat(reply).isEmpty();
    }

    @Test
    @DisplayName("answer try again without service call when busy reply for listed private chat")
    void should_answerTryAgainWithoutServiceCall_when_busyReplyForListedPrivateChat() {
        Reply reply = handler.busyReply(text("/start " + TOKEN)).orElseThrow();

        assertThat(reply.chatId()).isEqualTo(CHAT);
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("answer nothing when busy reply for unlisted group or membership")
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
    @DisplayName("reply invalid and remove keyboard when claim invalid")
    void should_replyInvalidAndRemoveKeyboard_when_claimInvalid() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.INVALID);

        Reply reply = reply(text("/start " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.INVALID_LINK);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @Test
    @DisplayName("accept bot suffixed command when start addressed to bot")
    void should_acceptBotSuffixedCommand_when_startAddressedToBot() {
        when(linkService.claim(TOKEN, CHAT)).thenReturn(ClaimResult.CLAIMED);

        Reply reply = reply(text("/Start@beautica_app_bot " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.ASK_PHONE);
    }

    @Test
    @DisplayName("reply try again when claim hits lock timeout")
    void should_replyTryAgain_when_claimHitsLockTimeout() {
        when(linkService.claim(TOKEN, CHAT)).thenThrow(new PessimisticLockingFailureException("55P03"));

        Reply reply = reply(text("/start " + TOKEN));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isNull();
    }

    @ParameterizedTest
    @DisplayName("reply help without keyboard change when no token or other text")
    @ValueSource(strings = {"/start", "/start   ", "/help", "привіт"})
    void should_replyHelpWithoutKeyboardChange_when_noTokenOrOtherText(String text) {
        Reply reply = reply(text(text));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.HELP);
        assertThat(reply.replyMarkup()).isNull();
        verifyNoInteractions(linkService);
    }

    @ParameterizedTest
    @DisplayName("unlink and remove keyboard when stop or unlink button")
    @ValueSource(strings = {"/stop", "/STOP", "Повернутися до SMS", "  Повернутися до SMS "})
    void should_unlinkAndRemoveKeyboard_when_stopOrUnlinkButton(String text) {
        Reply reply = reply(text(text));

        verify(linkService).unlinkChat(CHAT);
        assertThat(reply.text()).isEqualTo(TelegramBotReplies.STOPPED);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @Test
    @DisplayName("reply linked with unlink keyboard when contact matches")
    void should_replyLinkedWithUnlinkKeyboard_when_contactMatches() {
        when(linkService.proveContact(CHAT, PHONE, CHAT, CHAT)).thenReturn(ProofResult.LINKED);

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.LINKED);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.UNLINK_KEYBOARD);
    }

    @Test
    @DisplayName("reply mismatch and remove keyboard when contact differs")
    void should_replyMismatchAndRemoveKeyboard_when_contactDiffers() {
        when(linkService.proveContact(CHAT, PHONE, CHAT, CHAT)).thenReturn(ProofResult.MISMATCH);

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.MISMATCH);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @Test
    @DisplayName("ask own contact again when contact is forwarded")
    void should_askOwnContactAgain_when_contactIsForwarded() {
        when(linkService.proveContact(CHAT, PHONE, 42L, CHAT)).thenReturn(ProofResult.NOT_OWN_CONTACT);

        Reply reply = reply(contact(42L, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.NOT_OWN_CONTACT);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
    }

    @Test
    @DisplayName("reply not own without service call when contact has no sender")
    void should_replyNotOwnWithoutServiceCall_when_contactHasNoSender() {
        Reply reply = reply(contact(CHAT, null));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.NOT_OWN_CONTACT);
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("reply help and remove keyboard when contact has no pending claim")
    void should_replyHelpAndRemoveKeyboard_when_contactHasNoPendingClaim() {
        when(linkService.proveContact(CHAT, PHONE, CHAT, CHAT)).thenReturn(ProofResult.NO_PENDING);

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.HELP);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.REMOVE_KEYBOARD);
    }

    @Test
    @DisplayName("reply try again with share keyboard when proof hits lock timeout")
    void should_replyTryAgainWithShareKeyboard_when_proofHitsLockTimeout() {
        when(linkService.proveContact(CHAT, PHONE, CHAT, CHAT))
                .thenThrow(new PessimisticLockingFailureException("55P03"));

        Reply reply = reply(contact(CHAT, CHAT));

        assertThat(reply.text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        assertThat(reply.replyMarkup()).isEqualTo(TelegramBotReplies.SHARE_PHONE_KEYBOARD);
    }

    @Test
    @DisplayName("mark blocked without reply when kicked")
    void should_markBlockedWithoutReply_when_kicked() {
        Optional<Reply> reply = handler.handle(membership("private", "kicked"));

        assertThat(reply).isEmpty();
        verify(linkService).markBlocked(CHAT);
    }

    @Test
    @DisplayName("reactivate without reply when member again")
    void should_reactivateWithoutReply_when_memberAgain() {
        Optional<Reply> reply = handler.handle(membership("private", "member"));

        assertThat(reply).isEmpty();
        verify(linkService).reactivate(CHAT);
    }

    @Test
    @DisplayName("ignore membership when status unsupported or chat not private")
    void should_ignoreMembership_when_statusUnsupportedOrChatNotPrivate() {
        handler.handle(membership("private", "left"));
        handler.handle(membership("group", "kicked"));

        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("ignore without service call when chat not private")
    void should_ignoreWithoutServiceCall_when_chatNotPrivate() {
        TelegramUpdate group = new TelegramUpdate(1L,
                new Message(new Chat(-100L, "group"), new User(CHAT), "/start " + TOKEN, null), null);

        Optional<Reply> reply = handler.handle(group);

        assertThat(reply).isEmpty();
        verifyNoInteractions(linkService, allowList);
    }

    @Test
    @DisplayName("drop without service call when chat not allow listed")
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
    @DisplayName("ignore when message has no text or update is unsupported")
    void should_ignore_when_messageHasNoTextOrUpdateIsUnsupported() {
        TelegramUpdate sticker = new TelegramUpdate(1L,
                new Message(new Chat(CHAT, "private"), new User(CHAT), null, null), null);

        assertThat(handler.handle(sticker)).isEmpty();
        assertThat(handler.handle(new TelegramUpdate(1L, null, null))).isEmpty();
        verifyNoInteractions(linkService);
    }
}
