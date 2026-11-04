package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
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
 * <pre>
 * Beautica: Ваш запис 26.10.2026 о 10:00 перенесено.
 *
 * Деталі або записатись знову {url}
 * </pre>
 *
 * <ul>
 *   <li><b>No provider-authored text</b>, on the phase 390 precedent
 *       ({@link GuestProviderCancellationSmsText}): no note, no service, master or salon name. The
 *       only variable parts are the NEW Kyiv date and time (and a platform-built link).</li>
 *   <li><b>Phase 435 (user decision 2026-11-04) — the «Деталі…» paragraph</b>, shared with the
 *       provider-cancellation SMS ({@link GuestProviderCancellationSmsText#withDetails}), linking the guest's
 *       own booking page ({@link GuestProviderCancellationSmsText#guestBookingPageLink}); appended in code
 *       after the configurable body and dropped whole when there is no link (a row created before V230, or a
 *       sealed token that does not open).</li>
 *   <li><b>Configurable copy</b> ({@code app.booking.sms.reschedule}, single source
 *       {@link BookingSmsProperties.Sms#getReschedule()}), validated here at bean creation so a bad
 *       override fails boot instead of a guest's message: exactly one {@code {date}} and one
 *       {@code {time}}, no other placeholder (an unmapped one would render literally), and at most
 *       {@link #MAX_BODY_UCS2_CHARS} chars rendered (two UCS-2 segments) for the body alone.
 *       The default body renders to 50 chars — one segment.</li>
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
    /** Two concatenated UCS-2 segments — the cap on the configurable body alone (pre-435 rule, unchanged). */
    static final int MAX_BODY_UCS2_CHARS = 134;

    private final String template;
    private final GuestProviderCancellationSmsText links;

    public GuestRescheduleSmsText(BookingSmsProperties properties, GuestProviderCancellationSmsText links) {
        this.template = validated(properties.getSms().getReschedule());
        this.links = links;
    }

    /**
     * Renders the SMS for the booking's NEW start, in Kyiv wall-clock time, plus the «Деталі…» paragraph when
     * the guest's own booking page can be linked.
     */
    public String render(Booking booking) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        String body = Placeholders.format(template, Map.of(
                DATE, GuestProviderCancellationSmsText.DATE_FMT.format(kyiv),
                TIME, GuestProviderCancellationSmsText.TIME_FMT.format(kyiv)));
        return GuestProviderCancellationSmsText.withDetails(body, links.guestBookingPageLink(booking));
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
        if (rendered > MAX_BODY_UCS2_CHARS) {
            throw new IllegalStateException("app.booking.sms.reschedule renders to " + rendered
                    + " chars; two UCS-2 segments are " + MAX_BODY_UCS2_CHARS);
        }
        return template;
    }
}
