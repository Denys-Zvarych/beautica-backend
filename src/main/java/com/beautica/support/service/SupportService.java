package com.beautica.support.service;

import com.beautica.config.SupportProperties;
import com.beautica.common.exception.BusinessException;
import com.beautica.notification.EmailService;
import com.beautica.support.dto.ContactSupportRequest;
import com.beautica.support.dto.ContactSupportResponse;
import com.beautica.support.dto.SupportAttachment;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Help / Contact-us business logic: validates the submission, materialises any
 * attachments, and hands the whole thing to {@link EmailService} for delivery to
 * the fixed support inbox. No persistence — this is a fire-and-email channel
 * (persistence is a deliberate v1 non-goal; see class-level note in the PR).
 *
 * <p>All caller-influenced inputs are validated HERE, defensively, independent of
 * any DTO/Bean-Validation layer and independent of the platform multipart caps:
 * attachment count, per-file size, total size, and content type (sniffed from the
 * actual bytes, never trusting {@code MultipartFile.getContentType()}).
 */
@Service
@RequiredArgsConstructor
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    /** Max number of attachments per submission. */
    static final int MAX_ATTACHMENTS = 5;
    /** Max bytes for any single attachment (5 MB). */
    static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    /** Max bytes summed across all attachments (5 MB). */
    static final long MAX_TOTAL_BYTES = 5L * 1024L * 1024L;
    /**
     * Minimum bytes needed to sniff a magic-byte signature. 16 bytes is required so the
     * WebP VP8 chunk fourCC at offset 12..15 can be asserted (a bare RIFF+WEBP container
     * header is only 12 bytes — see {@link #detectContentType(byte[])}).
     */
    private static final int MIN_SNIFF_BYTES = 16;

    private final SupportProperties supportProperties;
    private final EmailService emailService;

    /**
     * Validates and dispatches a contact-us message on behalf of the authenticated
     * user. Identity ({@code senderUserId} / {@code senderEmail}) is supplied by the
     * controller from the {@code SecurityContext}, never by the request body.
     *
     * @throws BusinessException 503 when the support inbox is unconfigured;
     *                           400 for any attachment-constraint violation
     */
    public ContactSupportResponse contact(
            UUID senderUserId,
            String senderEmail,
            ContactSupportRequest request,
            List<MultipartFile> attachments) {

        String supportEmail = requireConfiguredSupportInbox();
        List<SupportAttachment> validated = validateAttachments(attachments);

        emailService.sendSupportContactEmail(
                supportEmail,
                senderUserId.toString(),
                senderEmail,
                request.subject(),
                request.message(),
                validated);

        log.info("Support contact accepted from user {} with {} attachment(s)",
                senderUserId, validated.size());
        return ContactSupportResponse.accepted();
    }

    private String requireConfiguredSupportInbox() {
        String email = supportProperties.getEmail();
        if (!StringUtils.hasText(email)) {
            // Feature is unconfigured (blank SUPPORT_EMAIL): degrade cleanly with a
            // 503 business error instead of crashing the app or emailing "".
            log.warn("Support contact rejected — SUPPORT_EMAIL is not configured");
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Support channel is not configured");
        }
        return email;
    }

    private List<SupportAttachment> validateAttachments(List<MultipartFile> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "A maximum of " + MAX_ATTACHMENTS + " attachments is allowed");
        }

        List<SupportAttachment> result = new ArrayList<>(attachments.size());
        long declaredTotal = 0;
        for (MultipartFile file : attachments) {
            // Fail-fast: reject an over-cap batch against the DECLARED sizes BEFORE reading any
            // bytes, so a 5-file × 5MB request is rejected without materialising 25 MB. The
            // declared size is honoured by Tomcat's multipart cap, so it is a safe pre-filter;
            // the streamed length is still re-verified per file inside readBoundedBytes.
            declaredTotal += Math.max(0, file == null ? 0 : file.getSize());
            if (declaredTotal > MAX_TOTAL_BYTES) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "Total attachment size exceeds the 5 MB limit");
            }
        }

        long totalBytes = 0;
        for (MultipartFile file : attachments) {
            SupportAttachment attachment = toAttachment(file);
            totalBytes += attachment.bytes().length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "Total attachment size exceeds the 5 MB limit");
            }
            result.add(attachment);
        }
        return result;
    }

    private SupportAttachment toAttachment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Attachment must not be empty");
        }
        // Fail-fast on the declared size before streaming a single byte.
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Attachment exceeds the 5 MB limit");
        }

        // Single-pass read: stream the file ONCE through a bounded reader capped at
        // MAX_FILE_BYTES. The returned bytes are reused for both magic-byte sniffing and
        // the outgoing attachment — no getSize()/getBytes()/length triple-touch. The cap
        // re-verifies the actual streamed length (getSize() is only a declared value and
        // can diverge from the content).
        byte[] bytes = readBoundedBytes(file);

        String detectedType = detectContentType(bytes);
        String filename = sanitizeFilename(file.getOriginalFilename(), detectedType);
        return new SupportAttachment(filename, detectedType, bytes);
    }

    /**
     * Reads the whole attachment ONCE, enforcing {@link #MAX_FILE_BYTES} as it streams so an
     * over-cap file (whose declared size lied) is rejected without buffering the excess.
     * Reading stops as soon as one byte past the cap is observed.
     *
     * @throws BusinessException 400 when the stream exceeds the per-file cap or cannot be read
     */
    private byte[] readBoundedBytes(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            // Cap + 1 so we can distinguish "exactly at cap" from "over cap" in one read.
            byte[] capped = in.readNBytes((int) MAX_FILE_BYTES + 1);
            if (capped.length > MAX_FILE_BYTES) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Attachment exceeds the 5 MB limit");
            }
            return capped;
        } catch (IOException ex) {
            log.warn("Failed to read support attachment: {}", ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Failed to read attachment");
        }
    }

    /**
     * Allow-list content-type detection by magic bytes. Never trusts the
     * client-declared {@code Content-Type} — a spoofed header cannot smuggle an
     * executable or HTML payload past this check.
     *
     * @throws BusinessException 400 when the content is not an allow-listed type
     */
    private String detectContentType(byte[] b) {
        if (b.length < MIN_SNIFF_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Attachment is too small or corrupt");
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        // WebP: 'RIFF' (0..3) + 'WEBP' (8..11) + a valid VP8 chunk fourCC (12..15).
        // The RIFF+WEBP container alone is NOT sufficient — a non-image RIFF (.wav/.avi)
        // renamed could otherwise sniff as image/webp. Require the chunk fourCC at 12..15
        // to be one of the three real WebP variants: 'VP8 ' (lossy), 'VP8L' (lossless),
        // 'VP8X' (extended).
        if (b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50
                && isWebpVp8Chunk(b)) {
            return "image/webp";
        }
        // PDF: '%PDF'
        if (b[0] == 0x25 && b[1] == 0x50 && b[2] == 0x44 && b[3] == 0x46) {
            return "application/pdf";
        }
        throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "Unsupported attachment type — JPEG, PNG, WebP, and PDF are accepted");
    }

    /**
     * Asserts the WebP VP8 chunk fourCC at bytes 12..15 is one of {@code "VP8 "},
     * {@code "VP8L"}, {@code "VP8X"}. Caller guarantees {@code b.length >= 16}.
     */
    private static boolean isWebpVp8Chunk(byte[] b) {
        // All three start with 'V','P','8' (0x56 0x50 0x38); the 4th byte differs.
        if (b[12] != 0x56 || b[13] != 0x50 || b[14] != 0x38) {
            return false;
        }
        int fourth = b[15] & 0xFF;
        return fourth == 0x20  // ' ' → "VP8 " (lossy)
                || fourth == 0x4C  // 'L' → "VP8L" (lossless)
                || fourth == 0x58; // 'X' → "VP8X" (extended)
    }

    private static final Map<String, String> TYPE_TO_EXT = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "application/pdf", ".pdf");

    /**
     * Builds a header-safe display filename. The client name is reduced to a bare,
     * path-stripped, alnum/dash/underscore token; the extension is forced from the
     * detected (trusted) type so a {@code .exe} label can't ride on a JPEG.
     */
    private String sanitizeFilename(String original, String detectedType) {
        String ext = TYPE_TO_EXT.getOrDefault(detectedType, "");
        String base = StringUtils.getFilename(original); // strips any path
        if (base == null || base.isBlank()) {
            return "attachment" + ext;
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String safe = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        if (safe.isBlank()) {
            safe = "attachment";
        }
        return safe + ext;
    }
}
