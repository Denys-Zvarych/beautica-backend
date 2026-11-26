package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * B539 (SVC-59) — an uploaded category icon broke a sanitiser rule. Surfaces as {@code 400} with
 * {@code {"success":false,"data":{"code":"ICON_INVALID","reason":"FORBIDDEN_ELEMENT"},"message":"…"}}; the portal
 * (W049) maps {@link Reason} to its own copy and never reads {@code message}. The message is fixed, never caller input,
 * and stack-trace capture is suppressed (flow control).
 */
public class CategoryIconInvalidException extends BusinessException {

    public static final String ERROR_CODE = "ICON_INVALID";

    /** Why the upload was rejected (the sanitiser rejects, never strips). Echoed under {@code data.reason}. */
    public enum Reason {
        /** More than 16 KiB of UTF-8. */
        TOO_LARGE,
        /** Not strict UTF-8, not well-formed XML, a DOCTYPE / entity, or the root is not an SVG-namespace {@code <svg>}. */
        NOT_SVG,
        /** The root {@code viewBox} is missing or not exactly {@code 0 0 24 24}. */
        BAD_VIEWBOX,
        /** An element outside the allow-list, a processing instruction, or text / CDATA content. */
        FORBIDDEN_ELEMENT,
        /** An attribute outside the allow-list ({@code on*}, {@code style}, {@code class}, …) or a malformed value. */
        FORBIDDEN_ATTRIBUTE,
        /** A {@code fill} / {@code stroke} other than {@code currentColor} or {@code none}. */
        FORBIDDEN_COLOUR,
        /** An {@code href} / {@code xlink:href}, or a value carrying {@code url(}, {@code data:} or a URL scheme. */
        EXTERNAL_REF
    }

    private final Reason reason;

    public CategoryIconInvalidException(Reason reason) {
        super(HttpStatus.BAD_REQUEST, "Category icon rejected");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
