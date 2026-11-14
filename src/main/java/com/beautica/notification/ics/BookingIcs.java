package com.beautica.notification.ics;

import com.beautica.booking.domain.GuestVisitAddress;
import com.beautica.booking.entity.Booking;
import com.beautica.master.entity.Master;
import com.beautica.user.User;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 496 (NTF-50) — the values of ONE booking's calendar event, the input of {@link BookingIcsBuilder}. Built from
 * a booking by {@link #of}; carries no note and no phone (NTF-03) — {@link Booking}'s comment and phone fields are
 * never read here.
 *
 * @param bookingId     the booking id; the event UID is {@code <bookingId>@beautica.app} (the website's scheme)
 * @param startsAt      event start (rendered in UTC)
 * @param endsAt        event end (rendered in UTC)
 * @param summary       «service · master[ · salon]»
 * @param location      the visit address, {@code null} when there is none
 * @param sequence      RFC 5545 SEQUENCE — {@link #sequenceOf(Instant)} of the booking's {@code updatedAt}
 * @param dtStamp       when this .ics was produced
 * @param organizerEmail the platform's sending address
 * @param attendeeName  the client's display name
 * @param attendeeEmail the client's e-mail
 */
public record BookingIcs(
        UUID bookingId,
        Instant startsAt,
        Instant endsAt,
        String summary,
        @Nullable String location,
        long sequence,
        Instant dtStamp,
        String organizerEmail,
        String attendeeName,
        String attendeeEmail
) {

    /** SEQUENCE counts seconds from this instant (D2): monotonic per change, fits 32 bits until 2094. */
    static final Instant SEQUENCE_EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private static final String SUMMARY_SEPARATOR = " · ";

    public BookingIcs {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(dtStamp, "dtStamp");
        Objects.requireNonNull(organizerEmail, "organizerEmail");
        Objects.requireNonNull(attendeeName, "attendeeName");
        Objects.requireNonNull(attendeeEmail, "attendeeEmail");
    }

    /**
     * The event of {@code booking} for its registered client. The booking must be loaded with client, master (+ user),
     * salon and service definition (every notification graph is).
     */
    public static BookingIcs of(Booking booking, String organizerEmail, Instant dtStamp) {
        User client = Objects.requireNonNull(booking.getClient(), "a booking .ics needs a registered client");
        return new BookingIcs(
                booking.getId(),
                booking.getStartsAt().toInstant(),
                booking.getEndsAt().toInstant(),
                summaryOf(booking),
                GuestVisitAddress.of(booking),
                sequenceOf(booking.getUpdatedAt()),
                dtStamp,
                organizerEmail,
                joinNonBlank(" ", client.getFirstName(), client.getLastName()),
                client.getEmail());
    }

    /** Seconds since {@link #SEQUENCE_EPOCH}; {@code 0} for a missing or earlier timestamp. */
    public static long sequenceOf(@Nullable Instant updatedAt) {
        if (updatedAt == null || updatedAt.isBefore(SEQUENCE_EPOCH)) {
            return 0;
        }
        return updatedAt.getEpochSecond() - SEQUENCE_EPOCH.getEpochSecond();
    }

    private static String summaryOf(Booking booking) {
        Master master = booking.getMaster();
        String service = booking.getMasterService().getServiceDefinition().getName();
        String masterName = joinNonBlank(" ", master.displayFirstName(), master.displayLastName());
        String salonName = booking.getSalon() == null ? null : booking.getSalon().getName();
        return joinNonBlank(SUMMARY_SEPARATOR, service, masterName, salonName);
    }

    private static String joinNonBlank(String separator, String... parts) {
        return Stream.of(parts)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.joining(separator));
    }
}
