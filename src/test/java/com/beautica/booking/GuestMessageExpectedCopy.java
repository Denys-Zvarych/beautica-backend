package com.beautica.booking;

import com.beautica.common.TimeZones;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The ONE shared expected-copy fixture for guest messages (SMS and Telegram carry the same text, phase 408 D4).
 * Pre-push gate rule 4 (orchestrate.md Step 2.9): new or changed tests take expected guest copy from here, never a
 * re-typed literal, so a copy change breaks in one place. Values are written out (not read from the production
 * template) on purpose: an assertion against the renderer's own constant would pass whatever the renderer says.
 * The builders below therefore mirror the production DEFAULT templates ({@code BookingSmsProperties.Sms#reschedule},
 * {@code GuestProviderCancellationSmsText.BODY / DETAILS}) and the shared {@code dd.MM.yyyy} / {@code HH:mm} Kyiv
 * formats, so a production copy change fails here instead of silently passing.
 */
public final class GuestMessageExpectedCopy {

    /** First line of every guest / walk-in booking confirmation (and of the post-link Telegram details). */
    public static final String CONFIRMATION_HEADLINE = "Beautica: Ваш запис підтверджено!";

    /** The guest cancel short-link at the end of a confirmation: {@code /c/} + 16 base62 characters. */
    public static final String CANCEL_LINK_SUFFIX_REGEX = "/c/[0-9A-Za-z]{16}$";

    /**
     * Provider decline / cancellation body ({@code «Beautica: Ваш запис dd.MM.yyyy о HH:mm скасовано.»}), anchored at
     * the start; any «Деталі…» paragraph after it is not constrained. Use with {@code containsPattern}.
     */
    public static final String PROVIDER_CANCELLATION_BODY_REGEX =
            "^Beautica: Ваш запис \\d{2}\\.\\d{2}\\.\\d{4} о \\d{2}:\\d{2} скасовано\\.";

    /** Paragraph appended to reschedule / provider-cancellation copy when the guest's own page can be linked. */
    private static final String DETAILS_PARAGRAPH_PREFIX = "\n\nДеталі або записатись знову ";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private GuestMessageExpectedCopy() {
    }

    /** Provider reschedule with no linkable guest page: {@code «Beautica: Ваш запис перенесено на {date} о {time}.»}. */
    public static String reschedule(OffsetDateTime newStart) {
        var kyiv = newStart.atZoneSameInstant(TimeZones.KYIV);
        return "Beautica: Ваш запис перенесено на " + DATE_FMT.format(kyiv) + " о " + TIME_FMT.format(kyiv) + ".";
    }

    /** Provider reschedule plus the «Деталі або записатись знову {url}» paragraph. */
    public static String rescheduleWithDetails(OffsetDateTime newStart, String pageUrl) {
        return reschedule(newStart) + DETAILS_PARAGRAPH_PREFIX + pageUrl;
    }
}
