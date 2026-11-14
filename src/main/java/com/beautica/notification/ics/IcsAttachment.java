package com.beautica.notification.ics;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Phase 496 (NTF-50) — one rendered .ics to attach to an e-mail. The MIME part is
 * {@code text/calendar; charset=UTF-8; method=<METHOD>}, file {@value #FILE_NAME} (a second event of the same mail
 * gets {@code beautica-booking-2.ics}, and so on).
 *
 * @param content the calendar object from {@link BookingIcsBuilder#build}
 * @param method  its iTIP method (repeated in the Content-Type, as RFC 6047 asks)
 */
public record IcsAttachment(String content, IcsMethod method) {

    public static final String FILE_NAME = "beautica-booking.ics";

    public IcsAttachment {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(method, "method");
    }

    public static IcsAttachment of(BookingIcs ics, IcsMethod method) {
        return new IcsAttachment(BookingIcsBuilder.build(ics, method), method);
    }

    /** The MIME Content-Type of the part. */
    public String contentType() {
        return "text/calendar; charset=UTF-8; method=" + method.name();
    }

    /** UTF-8 bytes of {@link #content}. */
    public byte[] bytes() {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /** {@value #FILE_NAME} for the first attachment, {@code beautica-booking-<n>.ics} for the n-th (1-based). */
    public static String fileName(int index) {
        return index <= 1 ? FILE_NAME : "beautica-booking-" + index + ".ics";
    }
}
