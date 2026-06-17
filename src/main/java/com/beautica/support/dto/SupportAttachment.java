package com.beautica.support.dto;

/**
 * In-memory representation of a single validated contact-us attachment, ready to
 * be embedded into the outgoing email.
 *
 * <p>This is the transport boundary between the support feature and the
 * notification layer: {@code SupportService} validates each {@code MultipartFile}
 * and materialises it into this record so {@code EmailService} never depends on
 * the web/multipart types. The {@code filename} is already sanitized by the
 * service before this record is constructed.
 *
 * @param filename    sanitized, header-safe file name shown in the email
 * @param contentType validated MIME type (one of the allow-listed types)
 * @param bytes       raw file content (bounded to ≤ 5 MB per file by the service)
 */
public record SupportAttachment(String filename, String contentType, byte[] bytes) {
}
