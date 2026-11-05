package com.beautica.notification.telegram.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 406 D3 — the minimal Bot API {@code Update} the webhook reads. Unknown fields are ignored; every field is
 * nullable (Telegram omits absent ones). Every {@code toString} is redacted: chat ids, user ids, text and phones are
 * personal data and must never reach a log line.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
        @JsonProperty("update_id") Long updateId,
        Message message,
        @JsonProperty("my_chat_member") ChatMemberUpdated myChatMember) {

    /** Bot API {@code Chat.type} of a one-to-one chat with the bot. */
    static final String PRIVATE_CHAT = "private";

    @Override
    public String toString() {
        return "TelegramUpdate[redacted]";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Chat chat, User from, String text, Contact contact) {
        @Override
        public String toString() {
            return "Message[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(Long id, String type) {
        /** {@code true} only for a private chat that carries an id. */
        boolean isPrivate() {
            return id != null && PRIVATE_CHAT.equals(type);
        }

        @Override
        public String toString() {
            return "Chat[type=" + type + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(Long id) {
        @Override
        public String toString() {
            return "User[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(
            @JsonProperty("phone_number") String phoneNumber,
            @JsonProperty("user_id") Long userId) {
        @Override
        public String toString() {
            return "Contact[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMemberUpdated(
            Chat chat,
            @JsonProperty("new_chat_member") ChatMember newChatMember) {
        @Override
        public String toString() {
            return "ChatMemberUpdated[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMember(String status) {
    }
}
