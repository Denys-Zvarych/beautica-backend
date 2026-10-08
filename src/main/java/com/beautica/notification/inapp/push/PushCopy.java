package com.beautica.notification.inapp.push;

import com.beautica.auth.Role;
import com.beautica.common.TimeZones;
import com.beautica.notification.inapp.dto.NotificationParams;
import com.beautica.notification.inapp.entity.InAppNotificationType;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;

/**
 * Ukrainian lock-screen copy for the Android push of one in-app feed row (phase 339, D5/D6).
 *
 * <p>Server-side because Android shows the {@code notification} block itself while the app is
 * killed, so the mobile ARB can never be consulted. Titles mirror
 * {@code beautica-mobile/lib/l10n/app_uk.arb} ({@code notificationTitle*}); bodies follow
 * {@code NotificationCopy.body} (provider leads with the client, client leads with the service), so
 * the feed row and the lock screen read the same.
 *
 * <p><b>Allowed in a body:</b> the counterpart display name, the first service name (+N), the Kyiv
 * start time, and the invitee name + role. <b>Never:</b> a booking note, a
 * phone, an e-mail — {@link NotificationParams} carries no field for any of them, and this class
 * reads nothing else.
 *
 * <p>{@link #title} is an exhaustive {@code switch} over {@link InAppNotificationType} with no
 * {@code default}, so adding an enum value fails compilation here until its copy is written.
 */
public final class PushCopy {

    /** Shown when the recipient lost access or any param the template needs is missing. */
    static final String GENERIC_BODY = "Відкрийте застосунок, щоб переглянути деталі";

    /**
     * Cap for every name that reaches a lock screen. Counterpart names can be guest-controlled
     * (an unauthenticated LINK booking sets the client name a provider sees) and the dispatcher
     * cannot tell a guest from a registered client, so the tighter cap applies to all names.
     */
    private static final int MAX_NAME_LENGTH = 40;

    private static final String[] WEEKDAYS = {"пн", "вт", "ср", "чт", "пт", "сб", "нд"};
    private static final String[] MONTHS_GENITIVE = {
        "січня", "лютого", "березня", "квітня", "травня", "червня",
        "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"
    };

    /**
     * Control (Cc) and format (Cf: bidi, zero-width, BOM, soft hyphen) characters — except U+200D
     * (ZWJ), which joins multi-codepoint emoji; U+FE0F (VS16) is Mn, so Cf stripping never touches it.
     */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\u200D]]");

    /** Unicode-aware whitespace: ASCII {@code \s}, NBSP and every Z* separator (incl. U+2028/2029), NEL. */
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s\\u0085]+");

    /** Dot-like separators; after NFKC the full-width/half-width forms fold to {@code .}/{@code 。}. */
    private static final String DOT = "[.。．｡]";

    /**
     * {@link #DOT} plus the middle dots U+00B7 and U+2027 (NFKC leaves both alone), which render like a
     * dot, so {@code evil·com} / {@code evil‧com} must not dodge the filter. Used ONLY for the tight
     * shape (no whitespace before the dot): a SPACED middle dot is a legitimate separator
     * ({@code Анна · Марія}) and must not wipe a real name, so the spaced shape keeps plain {@link #DOT}.
     * Documented trade-off: a name glued with a middle dot and no spaces ({@code Анна·Марія}) is removed.
     */
    private static final String TIGHT_DOT = "[.。．｡·‧]";

    /** Raw-input cap (code points) applied BEFORE NFKC/regex work, bounding the cost of a hostile name. */
    private static final int MAX_RAW_LENGTH = 255;

    /** One letter or digit of any script. */
    private static final String ALNUM = "[\\p{L}\\p{N}]";

    /**
     * URL-like tokens a stranger could plant in a name — a GENERIC shape rule, not a TLD list, so
     * {@code evil.app}, {@code bit.ly}, {@code x.shop}, {@code сайт.укр} and {@code evil。com} all fall.
     * The whole whitespace-delimited token is removed so surrounding punctuation
     * ({@code (evil.com)}) goes with it. Stripped shapes:
     * <ol>
     *   <li>anything with {@code ://}, or a {@code www.} host;</li>
     *   <li><b>tight</b>: a letter/digit, a dot-like char, then 2+ letters (any script) with nothing in
     *       between — {@code evil.app}, {@code x.shop}, {@code О.Коваленко};</li>
     *   <li><b>space before the dot</b>: {@code evil . com}, {@code evil .com} (whitespace is already
     *       collapsed to single spaces when this runs);</li>
     *   <li>a dotted IPv4 quad.</li>
     * </ol>
     * <b>Survives on purpose:</b> a dot FOLLOWED by a space — an initial or abbreviation
     * ({@code Олена К.}, {@code Олена К. Коваленко}, {@code St. John}) — and a trailing initial
     * ({@code Олена К.}: nothing after the dot). A dot needs 2+ letters directly after it, or a space
     * BEFORE it, to count as a host; hyphens and apostrophes ({@code Анна-Марія}, {@code O'Neil})
     * never matter. Documented trade-off: an initial glued to a surname without a space
     * ({@code О.Коваленко}) reads as a host and is removed — the body then falls back to the generic text
     * if nothing else is left.
     */
    private static final Pattern URL_LIKE = Pattern.compile(
            "\\S*(?://|\\bwww\\.)\\S*"
                    + "|\\S*" + ALNUM + TIGHT_DOT + "\\p{L}{2,}\\S*"
                    + "|\\S*" + ALNUM + "\\s+" + DOT + "\\s*\\p{L}{2,}\\S*"
                    + "|\\S*\\d{1,3}(?:\\.\\d{1,3}){3}\\S*",
            Pattern.CASE_INSENSITIVE);

    private PushCopy() {
    }

    public static String title(InAppNotificationType type, boolean clientRecipient) {
        return switch (type) {
            case BOOKING_CREATED -> "Новий запис";
            case BOOKING_CANCELLED_BY_CLIENT -> "Клієнт скасував запис";
            case BOOKING_DECLINED -> clientRecipient ? "Ваш запис скасовано" : "Запис у вашому дні скасовано";
            case BOOKING_NOT_COMPLETED -> "Візит позначено як непроведений";
            case BOOKING_RESCHEDULED -> clientRecipient ? "Ваш запис перенесено" : "Запис перенесено";
            case REVIEW_REQUESTED -> "Як пройшов візит? Залиште відгук";
            case BOOKING_CANCELLED_SALON_CLOSED -> "Салон закрито, запис скасовано";
            case BOOKING_CANCELLED_MASTER_REMOVED -> "Майстер більше не приймає, запис скасовано";
            case REVIEW_RECEIVED -> "Новий відгук";
            case INVITE_ACCEPTED -> "Новий учасник команди";
        };
    }

    /**
     * One body line from the params the READ API resolved for this recipient. {@code params == null}
     * (the recipient lost access / the referent is gone) or any missing field the template needs
     * yields {@link #GENERIC_BODY} — never a half-filled sentence, never a name.
     */
    public static String body(InAppNotificationType type, boolean clientRecipient, NotificationParams params) {
        if (params == null) {
            return GENERIC_BODY;
        }
        if (type == InAppNotificationType.INVITE_ACCEPTED) {
            return inviteBody(params);
        }
        String counterpart = sanitize(params.counterpartName());
        String service = sanitize(params.serviceName());
        Instant startsAt = params.startsAt();
        if (counterpart == null || service == null || startsAt == null) {
            return GENERIC_BODY;
        }
        String serviceLabel = params.serviceCount() > 1 ? service + " +" + (params.serviceCount() - 1) : service;
        String when = whenLabel(startsAt);
        boolean rescheduled = type == InAppNotificationType.BOOKING_RESCHEDULED;
        String time = rescheduled ? "новий час: " + when : when;
        if (clientRecipient) {
            return serviceLabel + ", " + time + " — " + counterpart;
        }
        String line = counterpart + " — " + serviceLabel + ", " + time;
        String master = sanitize(params.masterName());
        return master == null ? line : line + " · майстер " + master;
    }

    private static String inviteBody(NotificationParams params) {
        String subject = sanitize(params.subjectName());
        if (subject == null) {
            return GENERIC_BODY;
        }
        String role = params.subjectRole() == Role.SALON_ADMIN ? "адміністратор" : "майстер";
        return subject + " тепер у команді — " + role;
    }

    /** «пт, 3 жовтня, 14:30» in Europe/Kyiv — never the server or device zone. */
    static String whenLabel(Instant startsAt) {
        ZonedDateTime kyiv = startsAt.atZone(TimeZones.KYIV);
        return "%s, %d %s, %02d:%02d".formatted(
                WEEKDAYS[kyiv.getDayOfWeek().getValue() - 1],
                kyiv.getDayOfMonth(),
                MONTHS_GENITIVE[kyiv.getMonthValue() - 1],
                kyiv.getHour(),
                kyiv.getMinute());
    }

    /**
     * NFKC-folds (so full-width {@code ｗｗｗ．evil．com} cannot dodge the URL filter), turns every
     * Unicode whitespace into one space, strips control/format characters, removes URL-like tokens,
     * collapses whitespace and caps length; null when nothing visible is left.
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String bounded = raw.codePointCount(0, raw.length()) <= MAX_RAW_LENGTH
                ? raw
                : raw.substring(0, raw.offsetByCodePoints(0, MAX_RAW_LENGTH));
        String folded = Normalizer.normalize(bounded, Normalizer.Form.NFKC);
        String spaced = WHITESPACE.matcher(folded).replaceAll(" ");
        String visible = INVISIBLE.matcher(spaced).replaceAll("");
        String noUrls = URL_LIKE.matcher(visible).replaceAll("");
        String clean = WHITESPACE.matcher(noUrls).replaceAll(" ").trim();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.codePointCount(0, clean.length()) <= MAX_NAME_LENGTH) {
            return clean;
        }
        return clean.substring(0, clean.offsetByCodePoints(0, MAX_NAME_LENGTH)) + "…";
    }
}
