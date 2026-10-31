package com.beautica.notification.telegram.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Phase 406 D5 — the bot's Ukrainian copy and reply keyboards, and the webhook-response {@code sendMessage} shape.
 *
 * <p>Every reply travels in the webhook HTTP response body ({@code {"method":"sendMessage",...}}), so the backend
 * never makes an outbound call for a conversation step. No reply ever carries booking data or a phone.
 */
public final class TelegramBotReplies {

    /** Text of the K2 button; the bot treats an incoming message with exactly this text like {@code /stop}. */
    public static final String UNLINK_BUTTON_TEXT = "Повернутися до SMS";
    static final String SHARE_PHONE_BUTTON_TEXT = "Поділитися номером";

    static final String ASK_PHONE = "Щоб отримувати нагадування про запис тут, підтвердьте номер телефону, який ви "
            + "вказали під час запису: натисніть «Поділитися номером» нижче.";
    static final String LINKED = "Тепер усі сповіщення про ваш запис у Beautica ви отримуватимете тут: нагадування "
            + "та зміни від майстра чи салону. Підтвердження запису й коди й надалі приходять SMS. Щоб повернутися "
            + "до SMS, натисніть «Повернутися до SMS» або надішліть /stop.";
    static final String INVALID_LINK = "Посилання недійсне або застаріло. Відкрийте його знову зі сторінки запису на "
            + "beautica.app. Поки що сповіщення надходитимуть SMS.";
    static final String MISMATCH = "Цей номер не збігається з номером у записі, тому Telegram не підключено. "
            + "Сповіщення надходитимуть SMS. Якщо ви записувалися з іншого номера, відкрийте посилання з телефону з "
            + "Telegram на тому самому номері.";
    static final String NOT_OWN_CONTACT = "Будь ласка, поділіться саме своїм номером — кнопкою «Поділитися номером».";
    static final String STOPPED = "Telegram-сповіщення вимкнено. Надалі ми надсилатимемо SMS.";
    static final String HELP = "Це бот сповіщень Beautica. Щоб підключити його, на сторінці запису на beautica.app "
            + "оберіть «Telegram». /stop — повернутися до SMS.";
    /**
     * Not in D5: the answer to a 3 s {@code lock_timeout} in {@code claim} / {@code proveContact} (405). Answered
     * 200 with this copy instead of a 5xx, so Telegram does not redeliver into the same contention.
     */
    static final String TRY_AGAIN = "Не вдалося обробити запит. Спробуйте ще раз за хвилину.";

    /** K1 — one-time «share my phone» button. */
    static final ReplyKeyboard SHARE_PHONE_KEYBOARD = new ReplyKeyboard(
            List.of(List.of(new KeyboardButton(SHARE_PHONE_BUTTON_TEXT, true))), true, true, null);
    /** K2 — persistent «back to SMS» button (plain text, so no callback_query handling). */
    static final ReplyKeyboard UNLINK_KEYBOARD = new ReplyKeyboard(
            List.of(List.of(new KeyboardButton(UNLINK_BUTTON_TEXT, null))), true, null, true);
    static final RemoveKeyboard REMOVE_KEYBOARD = new RemoveKeyboard(true);

    private static final String SEND_MESSAGE = "sendMessage";

    private TelegramBotReplies() {
    }

    static Reply askPhone(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, ASK_PHONE, SHARE_PHONE_KEYBOARD);
    }

    static Reply linked(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, LINKED, UNLINK_KEYBOARD);
    }

    static Reply invalidLink(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, INVALID_LINK, REMOVE_KEYBOARD);
    }

    static Reply mismatch(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, MISMATCH, REMOVE_KEYBOARD);
    }

    static Reply notOwnContact(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, NOT_OWN_CONTACT, SHARE_PHONE_KEYBOARD);
    }

    static Reply stopped(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, STOPPED, REMOVE_KEYBOARD);
    }

    /** R-HELP with the keyboard left as it is. */
    static Reply help(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, HELP, null);
    }

    /** R-HELP after a contact share with no pending claim: the share button is removed. */
    static Reply helpRemovingKeyboard(long chatId) {
        return new Reply(SEND_MESSAGE, chatId, HELP, REMOVE_KEYBOARD);
    }

    static Reply tryAgain(long chatId, Object keyboard) {
        return new Reply(SEND_MESSAGE, chatId, TRY_AGAIN, keyboard);
    }

    /** Bot API {@code sendMessage} as a webhook reply; {@code reply_markup} omitted = keyboard unchanged. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(
            String method,
            @JsonProperty("chat_id") long chatId,
            String text,
            @JsonProperty("reply_markup") Object replyMarkup) {

        /** Redacted: a chat id is a Telegram user id. */
        @Override
        public String toString() {
            return "Reply[method=" + method + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReplyKeyboard(
            List<List<KeyboardButton>> keyboard,
            @JsonProperty("resize_keyboard") Boolean resizeKeyboard,
            @JsonProperty("one_time_keyboard") Boolean oneTimeKeyboard,
            @JsonProperty("is_persistent") Boolean isPersistent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record KeyboardButton(String text, @JsonProperty("request_contact") Boolean requestContact) {
    }

    record RemoveKeyboard(@JsonProperty("remove_keyboard") boolean removeKeyboard) {
    }
}
