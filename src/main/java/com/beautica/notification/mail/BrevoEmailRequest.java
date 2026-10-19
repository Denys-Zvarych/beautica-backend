package com.beautica.notification.mail;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * JSON body of {@code POST /v3/smtp/email} (Phase 374, D7). Empty/null members are omitted. Never logged: it
 * carries recipients and the rendered body.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record BrevoEmailRequest(
        Address sender,
        List<Address> to,
        List<Address> cc,
        List<Address> bcc,
        Address replyTo,
        String subject,
        String htmlContent,
        String textContent,
        Map<String, String> headers,
        List<Attachment> attachment
) {

    /** A mailbox; {@code name} is omitted when absent and capped at Brevo's 70 characters. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Address(String email, String name) {
    }

    /**
     * One attachment: file name (its extension must be on Brevo's allow-list) and the raw bytes. Jackson writes a
     * {@code byte[]} as standard base64 straight into the output stream, so no encoded {@code String} copy of a
     * multi-MB attachment is ever held in memory — provided the RestClient has no request interceptor (an
     * interceptor buffers the whole body), which is why the Brevo clients use {@link BrevoRestClients#cleanBuilder}.
     * Equality is irrelevant here (never compared or hashed).
     */
    public record Attachment(String name, byte[] content) {
    }
}
