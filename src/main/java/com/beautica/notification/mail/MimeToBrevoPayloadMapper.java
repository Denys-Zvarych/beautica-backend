package com.beautica.notification.mail;

import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a fully built {@link MimeMessage} into a {@link BrevoEmailRequest} (Phase 374, D7). Pure: no Spring
 * context, no I/O beyond reading the in-memory message.
 *
 * <ul>
 *   <li>{@code text/html} → {@code htmlContent}; {@code text/plain} → {@code textContent}. A text-only message also
 *       gets an HTML-escaped {@code htmlContent} ({@code <br>} per line): Brevo requires {@code htmlContent} when no
 *       {@code templateId} is sent (verified 2026-10-19).</li>
 *   <li>{@code Content-Disposition: attachment} parts → {@code attachment[{name, content(base64)}]}.</li>
 *   <li>{@code Reply-To} only when the header is present ({@link MimeMessage#getReplyTo()} falls back to From).</li>
 *   <li>Custom {@code X-*} headers → {@code headers}; standard headers are not allowed by Brevo.</li>
 * </ul>
 *
 * <p>Fails loudly, never drops content: any part with a {@code Content-ID} (inline CID image) or of an unsupported
 * type throws {@link BrevoPermanentMailException}. Exception messages carry a fixed reason token only.
 */
public final class MimeToBrevoPayloadMapper {

    static final int MAX_NAME_LENGTH = 70;
    private static final String CUSTOM_HEADER_PREFIX = "X-";
    private static final String CONTENT_ID = "Content-ID";
    private static final String REPLY_TO = "Reply-To";

    private MimeToBrevoPayloadMapper() {
    }

    /**
     * @param message           the message a caller handed to {@code JavaMailSender#send}
     * @param defaultSenderName display name used when {@code From} has none
     * @throws BrevoPermanentMailException when the message cannot be represented in the Brevo API
     */
    public static BrevoEmailRequest map(MimeMessage message, String defaultSenderName) {
        try {
            // Same step JavaMailSenderImpl performs before sending: materialises Content-Type headers of
            // multiparts built in memory by MimeMessageHelper.
            message.saveChanges();
            Bodies bodies = new Bodies();
            collect(message, bodies);
            return new BrevoEmailRequest(
                    sender(message, defaultSenderName),
                    recipients(message, Message.RecipientType.TO),
                    recipients(message, Message.RecipientType.CC),
                    recipients(message, Message.RecipientType.BCC),
                    replyTo(message),
                    message.getSubject(),
                    htmlContent(bodies),
                    bodies.text,
                    customHeaders(message),
                    bodies.attachments);
        } catch (MessagingException | IOException | ClassCastException e) {
            throw permanent("unconvertible-mime");
        }
    }

    private static void collect(Part part, Bodies bodies) throws MessagingException, IOException {
        if (part.getHeader(CONTENT_ID) != null) {
            throw permanent("inline-parts-unsupported");
        }
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            bodies.attachments.add(attachment(part));
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), bodies);
            }
        } else if (part.isMimeType("text/html")) {
            bodies.html = single(bodies.html, (String) part.getContent());
        } else if (part.isMimeType("text/plain")) {
            bodies.text = single(bodies.text, (String) part.getContent());
        } else {
            throw permanent("unsupported-part");
        }
    }

    private static String single(String existing, String value) {
        if (existing != null) {
            throw permanent("multiple-bodies");
        }
        return value;
    }

    private static BrevoEmailRequest.Attachment attachment(Part part) throws MessagingException, IOException {
        String rawName = part.getFileName();
        if (rawName == null || rawName.isBlank()) {
            throw permanent("attachment-without-name");
        }
        try (InputStream in = part.getInputStream()) {
            return new BrevoEmailRequest.Attachment(
                    stripLineBreaks(MimeUtility.decodeText(rawName)),
                    Base64.getEncoder().encodeToString(in.readAllBytes()));
        }
    }

    private static String htmlContent(Bodies bodies) {
        if (bodies.html != null) {
            return bodies.html;
        }
        if (bodies.text == null) {
            throw permanent("missing-body");
        }
        return HtmlUtils.htmlEscape(bodies.text).replace("\r\n", "\n").replace("\n", "<br>");
    }

    private static BrevoEmailRequest.Address sender(MimeMessage message, String defaultSenderName)
            throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0 || !(from[0] instanceof InternetAddress address)) {
            throw permanent("missing-sender");
        }
        String personal = address.getPersonal();
        return address(address.getAddress(), personal == null || personal.isBlank() ? defaultSenderName : personal);
    }

    private static List<BrevoEmailRequest.Address> recipients(MimeMessage message, Message.RecipientType type)
            throws MessagingException {
        Address[] addresses = message.getRecipients(type);
        if (addresses == null) {
            return Collections.emptyList();
        }
        List<BrevoEmailRequest.Address> result = new ArrayList<>(addresses.length);
        for (Address a : addresses) {
            if (!(a instanceof InternetAddress ia)) {
                throw permanent("unsupported-address");
            }
            result.add(address(ia.getAddress(), ia.getPersonal()));
        }
        return List.copyOf(result);
    }

    private static BrevoEmailRequest.Address replyTo(MimeMessage message) throws MessagingException {
        String header = message.getHeader(REPLY_TO, ",");
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            InternetAddress[] parsed = InternetAddress.parseHeader(header, false);
            if (parsed.length == 0) {
                return null;
            }
            String personal = parsed[0].getPersonal();
            return address(parsed[0].getAddress(), personal);
        } catch (AddressException e) {
            throw permanent("invalid-reply-to");
        }
    }

    private static Map<String, String> customHeaders(MimeMessage message) throws MessagingException {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<Header> all = message.getAllHeaders();
        while (all.hasMoreElements()) {
            Header h = all.nextElement();
            if (h.getName().regionMatches(true, 0, CUSTOM_HEADER_PREFIX, 0, CUSTOM_HEADER_PREFIX.length())) {
                headers.put(h.getName(), decode(h.getValue()));
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    private static BrevoEmailRequest.Address address(String email, String name) {
        String cleanName = name == null ? null : stripLineBreaks(name).strip();
        if (cleanName != null && cleanName.length() > MAX_NAME_LENGTH) {
            cleanName = cleanName.substring(0, MAX_NAME_LENGTH);
        }
        return new BrevoEmailRequest.Address(stripLineBreaks(email), cleanName);
    }

    private static String decode(String value) {
        try {
            return stripLineBreaks(MimeUtility.decodeText(value));
        } catch (UnsupportedEncodingException e) {
            return stripLineBreaks(value);
        }
    }

    /** Header-injection guard, mirroring {@code EmailNotificationService.buildMimeHelper}. */
    private static String stripLineBreaks(String value) {
        return value == null ? null : value.replaceAll("[\r\n]", "");
    }

    private static BrevoPermanentMailException permanent(String reason) {
        return new BrevoPermanentMailException("Brevo API conversion failed reason=" + reason);
    }

    /** Mutable accumulator for one traversal. */
    private static final class Bodies {
        private String html;
        private String text;
        private final List<BrevoEmailRequest.Attachment> attachments = new ArrayList<>();
    }
}
