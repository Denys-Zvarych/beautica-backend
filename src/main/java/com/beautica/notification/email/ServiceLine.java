package com.beautica.notification.email;

/**
 * One service row of a booking e-mail: start time, name, duration and frozen price (NTF-52).
 * Built only by {@link ServiceLines}; the template renders {@link #text()} through {@code th:text}.
 */
public record ServiceLine(String time, String name, String durationLabel, String priceLabel) {

    private static final String SEPARATOR = " · ";

    /** «14:00 Манікюр · 1 год · 500 ₴». */
    public String text() {
        return time + " " + name + SEPARATOR + durationLabel + SEPARATOR + priceLabel;
    }
}
