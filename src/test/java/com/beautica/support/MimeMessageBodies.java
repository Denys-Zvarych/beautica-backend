package com.beautica.support;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared test helper: the rendered HTML body of a {@link MimeMessage} captured from a mocked
 * {@code JavaMailSender}, for ITs that render e-mails for real and assert on their content.
 */
public final class MimeMessageBodies {

    private MimeMessageBodies() {
    }

    /**
     * The decoded {@code text/html} body of a (possibly nested) multipart message. JavaMail resolves
     * the transfer encoding and charset for us, so the Cyrillic copy comes back as plain text —
     * which is exactly what makes "does the body name every service?" assertable at all.
     */
    public static String htmlBodyOf(MimeMessage message) throws Exception {
        // MimeMessageHelper sets the multipart CONTENT but the matching Content-Type HEADER is only
        // written by saveChanges(); production gets that for free inside JavaMailSenderImpl#doSend.
        // Without it every isMimeType() check below reads the "text/plain" default and the body
        // comes back empty — a silently green test asserting on "".
        message.saveChanges();
        String html = htmlBodyOf((Part) message);
        assertThat(html)
                .as("no text/html part was extracted — the MIME shape changed, every content "
                        + "assertion below would be vacuously true")
                .isNotBlank();
        return html;
    }

    private static String htmlBodyOf(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder collected = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                collected.append(htmlBodyOf(multipart.getBodyPart(i)));
            }
            return collected.toString();
        }
        return "";
    }

    /**
     * Phase 496 — every {@code Content-Disposition: attachment} part of the message, in MIME order, at any depth
     * (the .ics files of a booking e-mail).
     */
    public static java.util.List<Part> attachmentsOf(MimeMessage message) throws Exception {
        message.saveChanges();
        java.util.List<Part> found = new java.util.ArrayList<>();
        collectAttachments(message, found);
        return found;
    }

    private static void collectAttachments(Part part, java.util.List<Part> found) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            found.add(part);
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectAttachments(multipart.getBodyPart(i), found);
            }
        }
    }

    /** The decoded UTF-8 text of an attachment part. */
    public static String textOf(Part part) throws Exception {
        try (java.io.InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
