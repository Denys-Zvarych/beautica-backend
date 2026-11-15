package com.beautica.notification.email;

import com.beautica.booking.entity.Booking;
import com.beautica.common.format.DurationLabel;
import com.beautica.notification.service.BookingVisit;
import com.beautica.service.util.PriceDisplayFormatter;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Pure builder of the e-mail service rows (NTF-52) from the booking's FROZEN snapshots —
 * {@code priceAtBooking}, {@code priceMaxAtBooking}, {@code durationMinutesAtBooking} — never the
 * live catalogue. Times are Kyiv. Items keep {@link BookingVisit#items()} order.
 */
public final class ServiceLines {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final Locale UK = Locale.forLanguageTag("uk");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE, d MMMM", UK);
    private static final String EN_DASH = "–";

    private ServiceLines() {
    }

    public static List<ServiceLine> of(BookingVisit visit) {
        return visit.items().stream().map(ServiceLines::lineOf).toList();
    }

    public static ServiceLine of(Booking booking) {
        return lineOf(booking);
    }

    /** «пт, 21 листопада, 14:00–16:30» (Kyiv; first item's start to the visit's end), or {@code null} for one service. */
    public static String visitHeader(BookingVisit visit) {
        if (!visit.isMultiService()) {
            return null;
        }
        var start = visit.startsAt().atZoneSameInstant(KYIV);
        var end = visit.endsAt().atZoneSameInstant(KYIV);
        return start.format(DAY_FMT) + ", " + start.format(TIME_FMT) + EN_DASH + end.format(TIME_FMT);
    }

    private static ServiceLine lineOf(Booking booking) {
        return new ServiceLine(
                booking.getStartsAt().atZoneSameInstant(KYIV).format(TIME_FMT),
                booking.getMasterService().getServiceDefinition().getName(),
                DurationLabel.format(booking.getDurationMinutesAtBooking()),
                PriceDisplayFormatter.compact(booking.getPriceAtBooking(), booking.getPriceMaxAtBooking()));
    }
}
