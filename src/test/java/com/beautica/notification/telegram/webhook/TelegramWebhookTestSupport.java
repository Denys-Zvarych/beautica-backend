package com.beautica.notification.telegram.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Synthetic Bot API updates + the authenticated POST for the phase 406 webhook ITs (shared by
 * {@code TelegramWebhookControllerIT} and {@code TelegramWebhookAllowListIT}, §M.3).
 */
public final class TelegramWebhookTestSupport {

    static final String PATH = "/api/v1/telegram/webhook";
    /**
     * Bot API {@code secret_token} alphabet; a compile-time constant (used in the ITs' test property source).
     * Phrased without the annotation literal: {@code TestHttpClientPolicyTest} treats a source containing the
     * at-sign + "Test" prefix as a test class, and this helper borrows its caller's already-bounded template.
     */
    static final String SECRET = "it-webhook-secret-0123456789abcdef0123456789abcdef0123456789abcdefzz";
    static final String ENABLED_PROPERTY = "app.telegram.enabled=true";
    static final String CAPTURE_MODE_PROPERTY = "app.telegram.mode=capture";
    static final String SECRET_PROPERTY = "app.telegram.webhook-secret=" + SECRET;

    private final TestRestTemplate restTemplate;

    public TelegramWebhookTestSupport(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    ResponseEntity<String> post(String json) {
        return post(json, SECRET, "198.51.100.1");
    }

    /** Public (phase 408 QA): {@code GuestMessageRoutingIT} links a phone through the real webhook with its own secret. */
    public ResponseEntity<String> post(String body, String secret, String clientIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        if (secret != null) {
            headers.set(TelegramWebhookSecretVerifier.SECRET_HEADER, secret);
        }
        return restTemplate.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** Built with Jackson, not string formatting, so any text or phone is escaped correctly (QA Q16). */
    private static final ObjectMapper JSON = new ObjectMapper();

    public static String text(long chatId, String text) {
        return chatMessage(chatId, "private", chatId, text);
    }

    /** A text message in a chat of any {@code type} ({@code group}, {@code supergroup}, {@code channel}, …). */
    public static String chatMessage(long chatId, String chatType, long fromId, String text) {
        ObjectNode update = JSON.createObjectNode().put("update_id", 1);
        ObjectNode message = update.putObject("message").put("message_id", 5).put("date", 1_761_818_400);
        message.putObject("chat").put("id", chatId).put("type", chatType).put("first_name", "Гість");
        message.putObject("from").put("id", fromId).put("is_bot", false).put("first_name", "Гість");
        message.put("text", text);
        return update.toString();
    }

    public static String contact(long chatId, String phone, long contactUserId) {
        ObjectNode update = JSON.createObjectNode().put("update_id", 2);
        ObjectNode message = update.putObject("message").put("message_id", 6).put("date", 1_761_818_400);
        message.putObject("chat").put("id", chatId).put("type", "private");
        message.putObject("from").put("id", chatId).put("is_bot", false);
        message.putObject("contact").put("phone_number", phone).put("first_name", "Гість")
                .put("user_id", contactUserId);
        return update.toString();
    }

    static String membership(long chatId, String status) {
        return membership(chatId, "private", status);
    }

    static String membership(long chatId, String chatType, String status) {
        ObjectNode update = JSON.createObjectNode().put("update_id", 3);
        ObjectNode member = update.putObject("my_chat_member");
        member.putObject("chat").put("id", chatId).put("type", chatType);
        member.putObject("from").put("id", chatId);
        member.put("date", 1_761_818_400);
        member.putObject("old_chat_member").put("status", "member").putObject("user").put("id", 1);
        member.putObject("new_chat_member").put("status", status).putObject("user").put("id", 1);
        return update.toString();
    }
}
