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

    /**
     * B520 (NTF-59) — the staff invite SMS ({@code StaffInviteSmsText}): this fixture is also the expected copy for that
     * SMS, the one non-guest SMS outside OTP, so every SMS copy lives in one place. {@code salonName} is the name as it
     * should appear (already cut, when a test exercises the three-segment cut).
     */
    public static String staffInvite(String salonName, String inviteUrl) {
        return "Beautica: «" + salonName + "» запрошує вас до команди. Приєднатися: " + inviteUrl;
    }

    /**
     * B520 audit-fix — the staff invite SMS when nothing safe is left of the salon name (it was only URLs / domains,
     * which never go out under the trusted sender).
     */
    public static String staffInviteWithoutSalonName(String inviteUrl) {
        return "Beautica: вас запрошують до команди салону. Приєднатися: " + inviteUrl;
    }

    /** B520 — three concatenated UCS-2 segments, the staff invite SMS ceiling. */
    public static final int STAFF_INVITE_MAX_CHARS = 201;

    /** Provider reschedule with no linkable guest page: {@code «Beautica: Ваш запис перенесено на {date} о {time}.»}. */
    public static String reschedule(OffsetDateTime newStart) {
        var kyiv = newStart.atZoneSameInstant(TimeZones.KYIV);
        return "Beautica: Ваш запис перенесено на " + DATE_FMT.format(kyiv) + " о " + TIME_FMT.format(kyiv) + ".";
    }

    /** Provider decline / cancellation of the service starting at {@code start}, with the guest's own page linked. */
    public static String providerCancellationWithDetails(OffsetDateTime start, String pageUrl) {
        var kyiv = start.atZoneSameInstant(TimeZones.KYIV);
        return "Beautica: Ваш запис " + DATE_FMT.format(kyiv) + " о " + TIME_FMT.format(kyiv) + " скасовано."
                + DETAILS_PARAGRAPH_PREFIX + pageUrl;
    }

    /** Provider reschedule plus the «Деталі або записатись знову {url}» paragraph. */
    public static String rescheduleWithDetails(OffsetDateTime newStart, String pageUrl) {
        return reschedule(newStart) + DETAILS_PARAGRAPH_PREFIX + pageUrl;
    }
}
