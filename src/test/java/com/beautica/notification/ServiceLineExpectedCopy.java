package com.beautica.notification;

import com.beautica.notification.email.ServiceLine;

import java.util.Arrays;
import java.util.List;

/**
 * The ONE shared expected-copy fixture for the phase 498 e-mail service lines (NTF-52). Every e-mail test takes the
 * line and visit-header strings from here, never a re-typed literal.
 *
 * <p>Values are written out on purpose (not read from {@code ServiceLines}): the fixture visit is a Kyiv 14:00 start,
 * 1 hour, 500 ₴ per service, and a two-service visit running 14:00–16:30 on Friday 21 November 2025.
 */
public final class ServiceLineExpectedCopy {

    public static final String TIME = "14:00";
    public static final String DURATION = "1 год";
    public static final String PRICE = "500 ₴";
    public static final String RANGE_PRICE = "500–700 ₴";
    /** The 30-minute / 350 ₴ booking of the receipt-e-mail ITs. */
    public static final String DURATION_HALF_HOUR = "30 хв";
    public static final String PRICE_350 = "350 ₴";
    /** The 350 ₴ booking of the receipt-e-mail ITs with a frozen ceiling of 700. */
    public static final String RANGE_PRICE_350 = "350–700 ₴";
    /** The multi-service visit header row. */
    public static final String VISIT_HEADER = "пт, 21 листопада, 14:00–16:30";
    /** The label of the visit header row. */
    public static final String VISIT_HEADER_LABEL = "Візит";

    private ServiceLineExpectedCopy() {
    }

    /** «14:00 {name} · 1 год · 500 ₴». */
    public static String text(String name) {
        return TIME + " " + name + " · " + DURATION + " · " + PRICE;
    }

    /** «{time} {name} · {duration} · {price}» for a line whose values differ from the default fixture. */
    public static String text(String time, String name, String duration, String price) {
        return time + " " + name + " · " + duration + " · " + price;
    }

    /** «{from}–{to} {name} · {duration} · {price}» — the single-booking .ics DESCRIPTION (phase 499, un-escaped). */
    public static String rangeText(String from, String to, String name, String duration, String price) {
        return timeRange(from, to) + " " + name + durationSegment(duration) + price;
    }

    /** «{day}, {from}–{to}» — e.g. {@code visitHeader("пт, 21 листопада", "14:00", "16:30")}. */
    public static String visitHeader(String day, String from, String to) {
        return day + ", " + timeRange(from, to);
    }

    /** «{from}–{to}» — the day-independent tail of the visit header. */
    public static String timeRange(String from, String to) {
        return from + "–" + to;
    }

    /** « · {duration} · » — the duration segment between a line's name and its price. */
    public static String durationSegment(String duration) {
        return " · " + duration + " · ";
    }

    /** The same line as the template context carries it. */
    public static ServiceLine line(String name) {
        return new ServiceLine(TIME, name, DURATION, PRICE);
    }

    public static List<ServiceLine> lines(String... names) {
        return Arrays.stream(names).map(ServiceLineExpectedCopy::line).toList();
    }
}
