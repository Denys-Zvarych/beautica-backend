package com.beautica.notification.service;

import com.beautica.common.TimeZones;
import com.beautica.common.util.Placeholders;
import com.beautica.config.BookingSmsProperties;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 431 — the one SMS an account-less guest (a LINK guest or a STAFF walk-in) gets when the
 * PROVIDER moves their booking, or one service of their visit, to a new time:
 *
 * <pre>Beautica: Ваш запис перенесено на 26.10.2026 о 10:00.</pre>
 *
 * <ul>
 *   <li><b>No provider-authored text</b>, on the phase 390 precedent
 *       ({@link GuestProviderCancellationSmsText}): no note, no service, master or salon name. The
 *       only variable parts are the NEW Kyiv date and time. No rebook link either — the booking still
 *       stands, so there is nothing to rebook.</li>
 *   <li><b>Configurable copy</b> ({@code app.booking.sms.reschedule}, single source
 *       {@link BookingSmsProperties.Sms#getReschedule()}), validated here at bean creation so a bad
 *       override fails boot instead of a guest's message: exactly one {@code {date}} and one
 *       {@code {time}}, no other placeholder (an unmapped one would render literally), and at most
 *       {@link GuestProviderCancellationSmsText#MAX_UCS2_CHARS} chars rendered (two UCS-2 segments).
 *       The default renders to 53 chars — one segment.</li>
 * </ul>
 *
 * <p>Same {@code dd.MM.yyyy} / {@code HH:mm} Kyiv formats as every other guest booking SMS. The
 * rendered text is never logged.
 */
@Component
public class GuestRescheduleSmsText {

    static final String DATE = "date";
    static final String TIME = "time";

    /** Same token shape {@link Placeholders} substitutes. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final int RENDERED_DATE_LENGTH = 10;
    private static final int RENDERED_TIME_LENGTH = 5;

    private final String template;

    public GuestRescheduleSmsText(BookingSmsProperties properties) {
        this.template = validated(properties.getSms().getReschedule());
    }

    /** Renders the SMS for the booking's NEW start, in Kyiv wall-clock time. */
    public String render(OffsetDateTime newStartsAt) {
        OffsetDateTime kyiv = newStartsAt.atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return Placeholders.format(template, Map.of(
                DATE, GuestProviderCancellationSmsText.DATE_FMT.format(kyiv),
                TIME, GuestProviderCancellationSmsText.TIME_FMT.format(kyiv)));
    }

    static String validated(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("app.booking.sms.reschedule must not be blank");
        }
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        if (placeholders.size() != 2 || !placeholders.contains(DATE) || !placeholders.contains(TIME)) {
            throw new IllegalStateException(
                    "app.booking.sms.reschedule must contain {date} and {time} exactly once and no other placeholder");
        }
        int rendered = template.length() - (DATE.length() + 2) - (TIME.length() + 2)
                + RENDERED_DATE_LENGTH + RENDERED_TIME_LENGTH;
        if (rendered > GuestProviderCancellationSmsText.MAX_UCS2_CHARS) {
            throw new IllegalStateException("app.booking.sms.reschedule renders to " + rendered
                    + " chars; two UCS-2 segments are " + GuestProviderCancellationSmsText.MAX_UCS2_CHARS);
        }
        return template;
    }
}
