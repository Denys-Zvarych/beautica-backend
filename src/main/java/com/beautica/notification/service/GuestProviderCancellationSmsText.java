package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.service.GuestBookingPageLinks;
import com.beautica.common.TimeZones;
import com.beautica.common.util.Placeholders;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 390 — the one SMS a GUEST (LINK or STAFF walk-in, no account) gets when the PROVIDER side
 * cancels their booking: a provider decline (single booking, one service line, or a whole visit), a
 * salon closure, or a master removal. The copy is fixed (phase 435, user decision 2026-11-04):
 *
 * <pre>
 * Beautica: Ваш запис 26.10.2026 о 10:00 скасовано.
 *
 * Деталі або записатись знову {url}
 * </pre>
 *
 * <ul>
 *   <li><b>No provider-authored text.</b> No provider note, no service name, no display name — the
 *       only variable parts are the Kyiv date/time (and a platform-built link). A
 *       provider therefore cannot put anything into a Beautica-branded SMS.</li>
 *   <li><b>{@code {url}} = the guest's OWN booking page</b> ({@code {FRONTEND_BASE_URL}/book/cancel/{token}}),
 *       by user decision 2026-11-04 — explicitly NOT the master/salon rebook page (the phase 390 rebook link
 *       is removed). Rebuilt from the sealed token (V230, {@link GuestBookingPageLinks}); the public page serves
 *       a read-only view once the booking is no longer {@code CONFIRMED}. When no link can be built (a row
 *       created before V230, an undecryptable value) the whole «Деталі…» paragraph is dropped — the body goes
 *       alone (49 chars, one UCS-2 segment).</li>
 *   <li><b>Length.</b> The paragraph is appended only while the whole text fits {@link #MAX_UCS2_CHARS}
 *       (two concatenated UCS-2 segments): a {@code /book/cancel/{uuid}} link on {@code https://beautica.app}
 *       renders to 49 + 30 + 69 = 148, so the paragraph is kept only when the configured origin is short
 *       enough — see {@link #withDetails}.</li>
 * </ul>
 *
 * <p>Date/time are the same {@code dd.MM.yyyy} / {@code HH:mm} Kyiv wall-clock formats as every
 * other guest booking SMS. {@link GuestRescheduleSmsText} reuses the same paragraph and link source.
 * The rendered text is never logged.
 */
@Component
public class GuestProviderCancellationSmsText {

    /**
     * Three concatenated UCS-2 segments (3 × 67 chars after the UDH). Phase 435 raised it from two (134): the
     * mandated paragraph label (30) plus a full {@code https://…/book/cancel/{uuid}} link (69 on beautica.app)
     * cannot fit two segments after the 49-char body.
     */
    static final int MAX_UCS2_CHARS = 201;

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    static final String BODY = "Beautica: Ваш запис {date} о {time} скасовано.";
    /** Phase 435 — the guest-page paragraph shared with {@link GuestRescheduleSmsText}. */
    static final String DETAILS = "\n\nДеталі або записатись знову {url}";

    private final GuestBookingPageLinks pageLinks;

    public GuestProviderCancellationSmsText(GuestBookingPageLinks pageLinks) {
        this.pageLinks = pageLinks;
    }

    /** Provider decline (single booking, one service line, or a whole visit). */
    public String forDecline(Booking target, OffsetDateTime startsAt) {
        return render(startsAt, guestBookingPageLink(target));
    }

    /** Master removal. */
    public String forMasterRemoved(Booking lead, OffsetDateTime startsAt) {
        return render(startsAt, guestBookingPageLink(lead));
    }

    /** Salon closure, without a link (kept for callers that have no booking). */
    public String forSalonClosed(OffsetDateTime startsAt) {
        return render(startsAt, Optional.empty());
    }

    /** Salon closure, linking the guest's own (now read-only) booking page — phase 435. */
    public String forSalonClosed(Booking lead, OffsetDateTime startsAt) {
        return render(startsAt, guestBookingPageLink(lead));
    }

    /**
     * The guest's own booking page ({@code /book/cancel/{token}}) from the sealed token (V230) — shared by this SMS
     * and the reschedule SMS. Empty when it cannot be built; see {@link GuestBookingPageLinks}.
     */
    Optional<String> guestBookingPageLink(Booking booking) {
        return pageLinks.forBooking(booking);
    }

    /** {@code body} plus the «Деталі…» paragraph when a link exists and the whole text fits; else the body. */
    static String withDetails(String body, Optional<String> url) {
        return url
                .map(link -> body + Placeholders.format(DETAILS, Map.of("url", link)))
                .filter(text -> text.length() <= MAX_UCS2_CHARS)
                .orElse(body);
    }

    String render(OffsetDateTime startsAt, Optional<String> url) {
        OffsetDateTime kyiv = startsAt.atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        String body = Placeholders.format(BODY, Map.of(
                "date", DATE_FMT.format(kyiv),
                "time", TIME_FMT.format(kyiv)));
        return withDetails(body, url);
    }
}
