package com.beautica.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONE shared expected-content fixture for the phase 496 booking .ics (NTF-50). Tests take expected lines, MIME
 * values and file names from here, never a re-typed literal. Values are written out on purpose (not read from
 * {@code BookingIcsBuilder}), so an assertion cannot pass merely because it agrees with the renderer.
 *
 * <p>The {@code WEBSITE_*} lines are verbatim output of {@code beautica-website/src/scripts/booking/ics.ts}
 * {@code buildIcs} for {@link #PARITY_UID} / {@link #PARITY_SUMMARY} / {@link #PARITY_LOCATION}, start
 * {@code 2026-05-20T07:00:00Z}, 90 minutes, now {@code 2026-05-01T09:30:15.123Z} (generated 2026-11-14: esbuild the
 * module to ESM, call {@code buildIcs}, print {@code JSON.stringify} of the result).
 */
public final class BookingIcsExpectedContent {

    public static final String FILE_NAME = "beautica-booking.ics";
    public static final String SECOND_FILE_NAME = "beautica-booking-2.ics";
    public static final String CONTENT_TYPE_PREFIX = "text/calendar";
    public static final String CRLF = "\r\n";
    public static final String METHOD_REQUEST = "METHOD:REQUEST";
    public static final String METHOD_CANCEL = "METHOD:CANCEL";
    public static final String STATUS_CONFIRMED = "STATUS:CONFIRMED";
    public static final String STATUS_CANCELLED = "STATUS:CANCELLED";
    public static final String VALARM = "BEGIN:VALARM";
    public static final String ORGANIZER_PREFIX = "ORGANIZER;CN=Beautica:mailto:";

    /** SUMMARY joins «service · master · salon» with this separator. */
    public static final String SUMMARY_SEPARATOR = " · ";

    // ── hostile provider names (IT escaping) + their escaped TEXT ─────────────
    public static final String HOSTILE_SERVICE_NAME = "Гель; френч, дизайн";
    public static final String ESCAPED_HOSTILE_SERVICE_NAME = "Гель\\; френч\\, дизайн";
    public static final String HOSTILE_SALON_NAME = "Аврора, Київ;\r\nфіліал 2";
    public static final String ESCAPED_HOSTILE_SALON_NAME = "Аврора\\, Київ\\;\\nфіліал 2";

    // ── website parity input + golden output (ics.ts) ─────────────────────────
    public static final String PARITY_UID = "3f2a9c1e-0000-4000-8000-000000000001";
    public static final String PARITY_SUMMARY = "Манікюр; гель, дизайн \\ «Арт»\nдругий рядок\u0007 · Марія Левченко · "
            + "Салон Аврора Преміум на Хрещатику";
    public static final String PARITY_LOCATION = "вул. Хрещатик 10, кв. 5; вхід\r\nз двору";
    public static final List<String> WEBSITE_HEADER = List.of("BEGIN:VCALENDAR", "VERSION:2.0",
            "PRODID:-//Beautica//Booking//UK", "CALSCALE:GREGORIAN");
    public static final String WEBSITE_UID = "UID:3f2a9c1e-0000-4000-8000-000000000001@beautica.app";
    public static final String WEBSITE_DTSTAMP = "DTSTAMP:20260501T093015Z";
    public static final String WEBSITE_DTSTART = "DTSTART:20260520T070000Z";
    public static final String WEBSITE_DTEND = "DTEND:20260520T083000Z";
    public static final String WEBSITE_SUMMARY = "SUMMARY:Манікюр\\; гель\\, дизайн \\\\ «Арт»\\nдруги\r\n"
            + " й рядок · Марія Левченко · Салон Аврора П\r\n реміум на Хрещатику";
    public static final String WEBSITE_LOCATION = "LOCATION:вул. Хрещатик 10\\, кв. 5\\; вхід\\nз двору";
    public static final String WEBSITE_ALARM = "BEGIN:VALARM\r\nACTION:DISPLAY\r\n"
            + "DESCRIPTION:Манікюр\\; гель\\, дизайн \\\\ «Арт»\\nдру\r\n гий рядок · Марія Левченко · Салон Аврор\r\n"
            + " а Преміум на Хрещатику\r\nTRIGGER:-PT1H\r\nEND:VALARM";

    private BookingIcsExpectedContent() {
    }

    /** {@code UID:<bookingId>@beautica.app}. */
    public static String uidLine(UUID bookingId) {
        return "UID:" + bookingId + "@beautica.app";
    }

    /** RFC 5545 unfolding: CRLF followed by one space joins the lines. */
    public static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }

    /** The unfolded content lines. */
    public static List<String> lines(String ics) {
        return new ArrayList<>(List.of(unfold(ics).split("\r\n")));
    }

    /** The value of the first {@code NAME:} or {@code NAME;…:} property, unfolded. */
    public static Optional<String> property(String ics, String name) {
        return lines(ics).stream()
                .filter(l -> l.startsWith(name + ":") || l.startsWith(name + ";"))
                .map(l -> l.substring(l.indexOf(':') + 1))
                .findFirst();
    }

    /** {@code SEQUENCE} as a number. */
    public static long sequence(String ics) {
        return Long.parseLong(property(ics, "SEQUENCE").orElseThrow());
    }
}
