package com.beautica.notification.telegram.webhook;

import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.link.ClaimResult;
import com.beautica.notification.telegram.link.ContactProof;
import com.beautica.notification.telegram.link.ProofResult;
import com.beautica.notification.telegram.link.TelegramLinkService;
import com.beautica.notification.telegram.webhook.TelegramBotReplies.Reply;
import com.beautica.notification.telegram.webhook.TelegramUpdate.Chat;
import com.beautica.notification.telegram.webhook.TelegramUpdate.ChatMemberUpdated;
import com.beautica.notification.telegram.webhook.TelegramUpdate.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 406 D5 — turns one authenticated Bot API update into at most one {@code sendMessage} reply. A fresh LINKED
 * proof additionally schedules one outbound follow-up with the booking details ({@link LinkedBookingDetailsSender}).
 *
 * <p><b>Transaction boundary.</b> Deliberately not {@code @Transactional}: every {@link TelegramLinkService} call is
 * its own transaction (405 perf rule — {@code claim} / {@code proveContact} must never run inside an outer
 * transaction that already loaded the token), and the reply goes back in the HTTP response, so no outbound call
 * is ever made while a row or advisory lock is held — nor on the webhook thread at all: the LINKED follow-up is only
 * scheduled here, after the proof committed.
 *
 * <p><b>Staging inbound gate (432 D3).</b> Only private chats are processed, and a chat that the
 * {@link TelegramChatAllowList} refuses is dropped (no reply, no service call, no DB write) before anything beyond
 * {@code chat.id} / {@code chat.type} is read.
 *
 * <p><b>Lock timeouts.</b> Every link-service write fuses a 3 s {@code lock_timeout} (405; {@code unlinkChat},
 * {@code markBlocked}, {@code reactivate} since the 406 audit). A {@link PessimisticLockingFailureException} or
 * {@link QueryTimeoutException} is answered 200 with a «try again» reply (no reply for a membership update) rather
 * than a 5xx, so Telegram does not redeliver into the same contention. Each call rolls back, so nothing changed.
 *
 * <p><b>Busy.</b> {@link #busyReply} is what the controller answers when its concurrency cap is full: the same
 * «try again» copy, after the same private-chat and allow-list checks, with no service call.
 *
 * <p><b>Logging (D7).</b> One line per update: {@code event=telegram_update type=… result=…}. Never a token, text,
 * phone, chat id or user id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramUpdateHandler {

    private static final String START = "/start";
    private static final String STOP = "/stop";
    private static final String STATUS_KICKED = "kicked";
    private static final String STATUS_MEMBER = "member";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final TelegramLinkService linkService;
    private final TelegramChatAllowList allowList;
    private final LinkedBookingDetailsSender bookingDetailsSender;

    /**
     * The «try again» answer for an update the controller could not admit (concurrency cap full): no service call,
     * no DB access; nothing for membership updates, non-private chats or chats outside the allow-list.
     */
    public Optional<Reply> busyReply(TelegramUpdate update) {
        Message message = update.message();
        if (message == null || message.chat() == null || !message.chat().isPrivate()
                || !allowList.allows(message.chat().id())) {
            return ignored("busy");
        }
        logUpdate("busy", "TRY_AGAIN");
        // A contact share must get the one-time share button back, as on the lock-timeout path.
        Object keyboard = message.contact() != null ? TelegramBotReplies.SHARE_PHONE_KEYBOARD : null;
        return Optional.of(TelegramBotReplies.tryAgain(message.chat().id(), keyboard));
    }

    public Optional<Reply> handle(TelegramUpdate update) {
        if (update.message() != null) {
            return handleMessage(update.message());
        }
        if (update.myChatMember() != null) {
            handleMembership(update.myChatMember());
            return Optional.empty();
        }
        return ignored("unsupported_update");
    }

    private Optional<Reply> handleMessage(Message message) {
        Chat chat = message.chat();
        if (chat == null || !chat.isPrivate()) {
            return ignored("not_private");
        }
        long chatId = chat.id();
        if (!allowList.allows(chatId)) {
            return ignored("not_allow_listed");
        }
        if (message.contact() != null) {
            return Optional.of(onContact(chatId, message));
        }
        if (message.text() == null) {
            return ignored("no_text");
        }
        return Optional.of(onText(chatId, message.text().strip()));
    }

    private Reply onText(long chatId, String text) {
        if (TelegramBotReplies.UNLINK_BUTTON_TEXT.equals(text)) {
            return onStop(chatId);
        }
        String[] parts = WHITESPACE.split(text, 2);
        String command = commandName(parts[0]);
        if (STOP.equals(command)) {
            return onStop(chatId);
        }
        if (START.equals(command) && parts.length == 2) {
            return onStart(chatId, parts[1].strip());
        }
        logUpdate("help", "HELP");
        return TelegramBotReplies.help(chatId);
    }

    /**
     * {@code claim} answers {@code INVALID} for a re-tap whose link is BLOCKED (405 QA: only an ACTIVE link reads
     * {@code ALREADY_LINKED}). A message from the chat proves it no longer blocks the bot, so only on that answer
     * — and only for a well-formed token, so {@code /start <garbage>} costs no UPDATE — the chat's own BLOCKED links
     * are reactivated and, if one was, the claim is asked once more — the common
     * {@code CLAIMED} / {@code ALREADY_LINKED} paths stay one transaction (406 perf audit).
     */
    private Reply onStart(long chatId, String rawToken) {
        try {
            ClaimResult result = linkService.claim(rawToken, chatId);
            if (result == ClaimResult.INVALID && TelegramLinkService.isWellFormedToken(rawToken)
                    && linkService.reactivate(chatId) > 0) {
                result = linkService.claim(rawToken, chatId);
            }
            logUpdate("start", result.name());
            return switch (result) {
                case CLAIMED -> TelegramBotReplies.askPhone(chatId);
                case ALREADY_LINKED -> TelegramBotReplies.linked(chatId);
                case INVALID -> TelegramBotReplies.invalidLink(chatId);
            };
        } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
            logUpdate("start", "LOCK_TIMEOUT");
            return TelegramBotReplies.tryAgain(chatId, null);
        }
    }

    private Reply onContact(long chatId, Message message) {
        Long fromId = message.from() == null ? null : message.from().id();
        if (fromId == null) {
            logUpdate("contact", ProofResult.NOT_OWN_CONTACT.name());
            return TelegramBotReplies.notOwnContact(chatId);
        }
        try {
            ContactProof proof = linkService.proveContactForBooking(
                    chatId, message.contact().phoneNumber(), message.contact().userId(), fromId);
            ProofResult result = proof.result();
            logUpdate("contact", result.name());
            return switch (result) {
                case LINKED -> onLinked(chatId, proof);
                case MISMATCH -> TelegramBotReplies.mismatch(chatId);
                case NOT_OWN_CONTACT -> TelegramBotReplies.notOwnContact(chatId);
                case NO_PENDING -> TelegramBotReplies.helpRemovingKeyboard(chatId);
            };
        } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
            logUpdate("contact", "LOCK_TIMEOUT");
            return TelegramBotReplies.tryAgain(chatId, TelegramBotReplies.SHARE_PHONE_KEYBOARD);
        }
    }

    /**
     * The proof has committed (its own transaction), so the details follow-up is scheduled now; it reaches the chat
     * after this LINKED reply (see {@link LinkedBookingDetailsSender}). Only a fresh link triggers it — a re-tap of
     * an already-linked chat ({@code /start} → {@code ALREADY_LINKED}) does not repeat the booking details.
     */
    private Reply onLinked(long chatId, ContactProof proof) {
        proof.linkedBookingId().ifPresent(bookingId ->
                bookingDetailsSender.scheduleAfterLink(chatId, bookingId, proof.phoneHash()));
        return TelegramBotReplies.linked(chatId);
    }

    private Reply onStop(long chatId) {
        try {
            int removed = linkService.unlinkChat(chatId);
            logUpdate("stop", removed > 0 ? "UNLINKED" : "NOT_LINKED");
            return TelegramBotReplies.stopped(chatId);
        } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
            logUpdate("stop", "LOCK_TIMEOUT");
            return TelegramBotReplies.tryAgain(chatId, null);
        }
    }

    private void handleMembership(ChatMemberUpdated update) {
        Chat chat = update.chat();
        if (chat == null || !chat.isPrivate()) {
            ignored("not_private");
            return;
        }
        if (!allowList.allows(chat.id())) {
            ignored("not_allow_listed");
            return;
        }
        String status = update.newChatMember() == null ? null : update.newChatMember().status();
        try {
            applyMembership(chat.id(), status);
        } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
            // No reply channel and a 200: a lost kick is caught by 408's failed send, a lost unblock by a re-tap.
            logUpdate(STATUS_KICKED.equals(status) ? "blocked" : "unblocked", "LOCK_TIMEOUT");
        }
    }

    private void applyMembership(long chatId, String status) {
        if (STATUS_KICKED.equals(status)) {
            linkService.markBlocked(chatId);
            logUpdate("blocked", "BLOCKED");
        } else if (STATUS_MEMBER.equals(status)) {
            linkService.reactivate(chatId);
            logUpdate("unblocked", "REACTIVATED");
        } else {
            ignored("unsupported_status");
        }
    }

    /** {@code /Start@beautica_app_bot} → {@code /start}; Bot API commands are case-insensitive. */
    private static String commandName(String word) {
        int at = word.indexOf('@');
        return (at < 0 ? word : word.substring(0, at)).toLowerCase(Locale.ROOT);
    }

    private static Optional<Reply> ignored(String reason) {
        logUpdate("ignored", reason);
        return Optional.empty();
    }

    private static void logUpdate(String type, String result) {
        log.info("event=telegram_update type={} result={}", type, result);
    }
}
