package com.beautica.review.invite.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 400 D2 — a privacy-policy version as the guest booking stores it ({@code GuestBookingRequest}: the policy's
 * effective date, optional {@code .N} revision, e.g. {@code 2026-10-20} or {@code 2026-10-20.2}). Ordered by date,
 * then revision (none = 0). Plain string comparison is wrong once a revision reaches two digits.
 *
 * @param date     the effective date
 * @param revision the {@code .N} suffix, 0 when absent
 */
record PolicyVersion(LocalDate date, int revision) implements Comparable<PolicyVersion> {

    private static final Pattern FORMAT = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:\\.(\\d{1,3}))?$");
    private static final Comparator<PolicyVersion> ORDER =
            Comparator.comparing(PolicyVersion::date).thenComparingInt(PolicyVersion::revision);

    /** Empty for {@code null}, blank or any value that is not a valid version — never throws. */
    static Optional<PolicyVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            int revision = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            return Optional.of(new PolicyVersion(LocalDate.parse(m.group(1)), revision));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    boolean isAtLeast(PolicyVersion minimum) {
        return compareTo(minimum) >= 0;
    }

    @Override
    public int compareTo(PolicyVersion other) {
        return ORDER.compare(this, other);
    }
}
