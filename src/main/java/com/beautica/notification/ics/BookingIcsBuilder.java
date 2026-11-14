package com.beautica.notification.ics;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 496 (NTF-50) — the ONE .ics (RFC 5545 / iTIP RFC 5546) renderer for booking e-mails. A port of the website's
 * {@code beautica-website/src/scripts/booking/ics.ts}: same PRODID, UID scheme ({@code <bookingId>@beautica.app}),
 * UTC {@code …Z} stamps, TEXT escaping, 75-octet folding (never splitting a code point), CRLF line ends and the
 * one-hour {@code VALARM} — plus the iTIP fields an e-mail invitation needs ({@code METHOD}, {@code SEQUENCE},
 * {@code ORGANIZER}, {@code ATTENDEE}, {@code STATUS}). Pure and stateless.
 *
 * <p>No DESCRIPTION: the website's carries the guest cancel link, which a registered client does not have; no note
 * and no phone ever reaches the event (NTF-03). The alarm rides only on {@link IcsMethod#REQUEST} — a CANCEL removes
 * the event, it has nothing to remind about.
 */
public final class BookingIcsBuilder {

    static final String PRODID = "-//Beautica//Booking//UK";
    static final String UID_DOMAIN = "@beautica.app";
    static final String CRLF = "\r\n";
    private static final int MAX_LINE_OCTETS = 75;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern LINE_BREAKS = Pattern.compile("\r\n|\r|\n| | |\u0085");
    /** ics.ts: {@code [\x00-\x08\x0b-\x1f\x7f-\x84\x86-\x9f]} — other control characters (incl. C1) are dropped. */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\x84\\x86-\\x9f]");
    /** A parameter value is quoted, so DQUOTE (and every control character) cannot appear inside it. */
    private static final Pattern PARAM_FORBIDDEN = Pattern.compile("[\"\\x00-\\x1f\\x7f-\\x9f  ]");
    /** A mailto value is a URI: whitespace and controls are dropped. */
    private static final Pattern ADDRESS_FORBIDDEN = Pattern.compile("[\\s\\x00-\\x1f\\x7f-\\x9f]");

    private BookingIcsBuilder() {
    }

    /** The whole calendar object, every content line folded and CRLF-terminated. */
    public static String build(BookingIcs ics, IcsMethod method) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:" + PRODID);
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:" + method.name());
        lines.add("BEGIN:VEVENT");
        lines.add("UID:" + ics.bookingId() + UID_DOMAIN);
        lines.add("SEQUENCE:" + ics.sequence());
        lines.add("DTSTAMP:" + stamp(ics.dtStamp()));
        lines.add("DTSTART:" + stamp(ics.startsAt()));
        lines.add("DTEND:" + stamp(ics.endsAt()));
        lines.add("SUMMARY:" + escape(ics.summary()));
        if (ics.location() != null && !ics.location().isBlank()) {
            lines.add("LOCATION:" + escape(ics.location()));
        }
        lines.add("ORGANIZER;CN=Beautica:mailto:" + address(ics.organizerEmail()));
        lines.add("ATTENDEE;CN=\"" + paramValue(ics.attendeeName()) + "\";RSVP=FALSE:mailto:"
                + address(ics.attendeeEmail()));
        lines.add("STATUS:" + method.eventStatus());
        if (method == IcsMethod.REQUEST) {
            lines.add("BEGIN:VALARM");
            lines.add("ACTION:DISPLAY");
            lines.add("DESCRIPTION:" + escape(ics.summary()));
            lines.add("TRIGGER:-PT1H");
            lines.add("END:VALARM");
        }
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");
        return lines.stream().map(BookingIcsBuilder::fold).collect(Collectors.joining(CRLF)) + CRLF;
    }

    /** ics.ts {@code esc}: backslash, then any line break → {@code \n}, controls dropped, then {@code ;} and {@code ,}. */
    static String escape(String value) {
        String s = value.replace("\\", "\\\\");
        s = LINE_BREAKS.matcher(s).replaceAll("\\\\n");
        s = CONTROL_CHARS.matcher(s).replaceAll("");
        return s.replace(";", "\\;").replace(",", "\\,");
    }

    /** ics.ts {@code stamp}: UTC, whole seconds, {@code yyyyMMdd'T'HHmmss'Z'}. */
    static String stamp(Instant instant) {
        return STAMP.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * ics.ts {@code fold}: a line longer than 75 UTF-8 octets continues on CRLF + one space; a code point is never
     * split (a lone surrogate counts as 3 octets, as in the website).
     */
    static String fold(String line) {
        StringBuilder out = new StringBuilder(line.length() + 8);
        int used = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int octets = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (used + octets > MAX_LINE_OCTETS) {
                out.append(CRLF).append(' ');
                used = 1;
            }
            out.appendCodePoint(cp);
            used += octets;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String paramValue(String value) {
        return PARAM_FORBIDDEN.matcher(value).replaceAll("");
    }

    private static String address(String value) {
        return ADDRESS_FORBIDDEN.matcher(value).replaceAll("");
    }
}
