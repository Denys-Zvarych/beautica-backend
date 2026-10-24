package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.common.TimeZones;
import com.beautica.common.util.Placeholders;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 390 — the one SMS a GUEST (LINK, no account) gets when the PROVIDER side cancels their
 * booking: a provider decline (single booking, one service line, or a whole visit), a salon closure,
 * or a master removal. The copy is fixed:
 *
 * <pre>Beautica: Ваш запис 26.10.2026 о 10:00 скасовано. Записатися знову: beautica.app/book/{slug}</pre>
 *
 * <ul>
 *   <li><b>No provider-authored text.</b> No provider note, no service name, no display name — the
 *       only variable parts are the Kyiv date/time and a platform-generated slug. A provider
 *       therefore cannot put anything into a Beautica-branded SMS. The slug is derived from a name
 *       (an independent master's transliterated name, or the salon's), so a master slug is only
 *       ever used for an {@code INDEPENDENT_MASTER}, whose page is already public by their choice.</li>
 *   <li><b>Length.</b> The body alone is 49 UCS-2 chars (one segment). The rebook link is appended
 *       only when the whole message still fits {@link #MAX_UCS2_CHARS} (two concatenated
 *       segments); otherwise it is dropped and the body goes alone.</li>
 *   <li><b>The link is dropped</b> when booking-link sharing is switched off
 *       ({@code app.booking-links.share-enabled=false}: the website pages are not live), when the
 *       owner of the slug is inactive or has no slug, and always for a salon closure.</li>
 * </ul>
 *
 * <p>Date/time are the same {@code dd.MM.yyyy} / {@code HH:mm} Kyiv wall-clock formats as every
 * other guest booking SMS. The rendered text is never logged.
 */
@Component
public class GuestProviderCancellationSmsText {

    /** Two concatenated UCS-2 segments (2 × 67 chars after the UDH). */
    static final int MAX_UCS2_CHARS = 134;

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    static final String BODY = "Beautica: Ваш запис {date} о {time} скасовано.";
    static final String REBOOK = " Записатися знову: {url}";

    private static final String HTTPS = "https://";
    private static final String MASTER_PATH = "/book/";
    private static final String SALON_PATH = "/book/s/";

    /** The V86/V200 slug shape; anything else is never put into an SMS. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*[a-z0-9]$");

    private final boolean shareEnabled;
    private final String webHost;

    public GuestProviderCancellationSmsText(BookingLinksProperties bookingLinksProperties) {
        this.shareEnabled = bookingLinksProperties.isShareEnabled();
        this.webHost = bareHost(bookingLinksProperties.getWebBaseUrl());
    }

    /**
     * Provider decline. Rebook link: an INDEPENDENT master's own page when they have one (the link
     * the guest most likely booked through), else the salon's page. A salon master or a salon owner
     * never gets a master link (locked booking-link access rule: a salon master never shares a link,
     * an owner shares only the salon's), which also keeps their transliterated name out of the SMS.
     *
     * @param target   the declined booking (or the visit's lead) — its master/salon are read
     * @param startsAt the cancelled appointment's start (the visit start for a whole-visit decline)
     */
    public String forDecline(Booking target, OffsetDateTime startsAt) {
        Optional<String> link = masterLink(target.getMaster()).or(() -> salonLink(target.getSalon()));
        return render(startsAt, link);
    }

    /** Master removal: the master is gone, so only the salon's own page can be offered. */
    public String forMasterRemoved(Booking lead, OffsetDateTime startsAt) {
        return render(startsAt, salonLink(lead.getSalon()));
    }

    /** Salon closure: there is nothing left to rebook with, so never a link. */
    public String forSalonClosed(OffsetDateTime startsAt) {
        return render(startsAt, Optional.empty());
    }

    String render(OffsetDateTime startsAt, Optional<String> rebookUrl) {
        OffsetDateTime kyiv = startsAt.atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        String body = Placeholders.format(BODY, Map.of(
                "date", DATE_FMT.format(kyiv),
                "time", TIME_FMT.format(kyiv)));
        return rebookUrl
                .map(url -> body + Placeholders.format(REBOOK, Map.of("url", url)))
                .filter(text -> text.length() <= MAX_UCS2_CHARS)
                .orElse(body);
    }

    private Optional<String> masterLink(Master master) {
        if (master == null || master.getMasterType() != MasterType.INDEPENDENT_MASTER || !master.isActive()) {
            return Optional.empty();
        }
        return link(MASTER_PATH, master.getBookingSlug());
    }

    private Optional<String> salonLink(Salon salon) {
        if (salon == null || !salon.isActive()) {
            return Optional.empty();
        }
        return link(SALON_PATH, salon.getBookingSlug());
    }

    private Optional<String> link(String path, String slug) {
        if (!shareEnabled || slug == null || !SLUG.matcher(slug).matches()) {
            return Optional.empty();
        }
        return Optional.of(webHost + path + slug);
    }

    /** {@code https://beautica.app} → {@code beautica.app}; the origin is validated at boot. */
    private static String bareHost(String webBaseUrl) {
        String host = webBaseUrl.startsWith(HTTPS) ? webBaseUrl.substring(HTTPS.length()) : webBaseUrl;
        return host.toLowerCase(Locale.ROOT);
    }
}
