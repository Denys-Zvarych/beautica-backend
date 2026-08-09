package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The notification-side view of ONE visit: the booking row an outbox entry is keyed to, plus every
 * chained booking row that belongs to the same {@code Appointment} and shares its status.
 *
 * <p><b>Why this exists.</b> A multi-service visit is 1 {@code Appointment} + N {@code Booking}
 * rows, and exactly ONE outbox row is enqueued for the whole visit, keyed to the first chained
 * booking (see {@code AppointmentService} / {@code GuestBookingService}). Nothing used to hydrate
 * the siblings, so every channel — provider e-mail, client e-mail, push, SMS — described only the
 * lead service and silently dropped the rest. This value object is the single place the item list
 * is resolved, so the channels cannot drift from one another.
 *
 * <p><b>Single-service invariant.</b> A booking with no appointment yields {@link #single(Booking)}
 * — {@code items} is exactly {@code [lead]}, {@link #isMultiService()} is {@code false}, and every
 * derived value collapses to the same scalar the pre-visit code read straight off the booking. That
 * is what keeps the legacy single-service notification output unchanged.
 *
 * <p><b>Ordering.</b> {@code items} is sorted ONCE, here, by {@code startsAt} then by {@code id} as
 * a tiebreaker — never by repository iteration order — so the rendered service list is stable and
 * testable. {@code Stream.toList()} already returns an unmodifiable list, so that single sort is
 * also the single defensive copy; the previous "sort in the factory, then {@code List.copyOf} in
 * the compact constructor" pair copied the list twice for no added safety.
 *
 * <p><b>Derived values are computed on demand, never at construction.</b> Each of
 * {@link #serviceNames()}, {@link #endsAt()} and {@link #totalDurationMinutes()} is read at most
 * once per notification, and each is needed by only SOME channels — the push and SMS paths render
 * no service list and no duration at all. Precomputing them would make the dominant single-service
 * path pay for values nobody reads, and would force every construction site (including test
 * fixtures) to have every association populated up front. What WAS wasteful is fixed:
 * {@link #leadServiceName()} no longer builds the whole name list to read element 0.
 *
 * <p><b>{@link #lead()} and its twin inside {@link #items()} are DIFFERENT instances, hydrated by
 * different queries.</b> On the drain path the lead comes from {@code findAllByIdsWithGraph} and the
 * items from {@code findByAppointmentIdsWithGraph} — two statements, two sessions, both detached by
 * the time a channel sees them. So the lead row appears twice with different graphs loaded: only
 * {@link #lead()} has {@code client}, {@code master} and {@code salon} fetched, while the copy in
 * {@link #items()} carries them as uninitialised proxies. Read every recipient, note and party
 * PROPERTY off {@link #lead()}; from an item, read only service names, scalars and party
 * IDENTIFIERS. Touching {@code items().get(i).getClient().getEmail()} compiles, passes any unit test
 * built from mocks, and throws {@code LazyInitializationException} in production.
 *
 * <p><b>Notes are deliberately absent.</b> No accessor on this type exposes {@code clientComment},
 * {@code clientCancellationNote} or {@code providerComment} — booking notes are never logged and
 * never enter the outbox payload (locked track-25 rule). Callers that legitimately render a note
 * (e.g. the decline e-mail) read it off {@link #lead()} explicitly, as they always did.
 */
public final class BookingVisit {

    private static final Comparator<Booking> VISIT_ORDER =
            Comparator.comparing(Booking::getStartsAt).thenComparing(Booking::getId);

    private final Booking lead;
    private final List<Booking> items;
    private final BookingStatus appointmentStatus;

    private BookingVisit(Booking lead, List<Booking> items, BookingStatus appointmentStatus) {
        this.lead = lead;
        this.items = items;
        this.appointmentStatus = appointmentStatus;
    }

    /** A legacy single-service booking: the visit is the booking itself, with no visit header. */
    public static BookingVisit single(Booking booking) {
        Objects.requireNonNull(booking, "lead must not be null");
        return new BookingVisit(booking, List.of(booking), null);
    }

    /**
     * A multi-service visit whose header status is unknown to the caller — every consumer that
     * branches on {@link #appointmentStatus()} therefore treats it as a per-ITEM event. Used by
     * unit tests and by any caller that has the item rows but not the {@code Appointment} header.
     */
    public static BookingVisit of(Booking lead, List<Booking> items) {
        return of(lead, items, null);
    }

    /**
     * A multi-service visit with its {@code Appointment} header status — see
     * {@link #appointmentStatus()} for what consumers do with it. {@code items} is re-sorted into
     * {@link #VISIT_ORDER} defensively.
     */
    public static BookingVisit of(Booking lead, List<Booking> items, BookingStatus appointmentStatus) {
        Objects.requireNonNull(lead, "lead must not be null");
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A visit must carry at least one booking item");
        }
        return new BookingVisit(lead, items.stream().sorted(VISIT_ORDER).toList(), appointmentStatus);
    }

    /**
     * The booking the outbox entry points at — the fully-hydrated row every recipient, status and
     * note is resolved from. Always present in {@link #items()}.
     */
    public Booking lead() {
        return lead;
    }

    /** Every booking row of the visit that shares {@link #lead()}'s status, ordered; never empty. */
    public List<Booking> items() {
        return items;
    }

    /**
     * The status of the {@code Appointment} HEADER this visit belongs to, or {@code null} when the
     * booking has no appointment (a legacy single-service booking) or the caller could not resolve
     * the header.
     *
     * <p>This is the discriminator between a WHOLE-VISIT transition and a PER-ITEM one, which the
     * item rows alone cannot express: {@code AppointmentTransitionService#declineAppointment}
     * terminates the header and every item, whereas {@code #declineAppointmentItem} declines one
     * item and leaves the header {@code CONFIRMED}. A {@code null} header is treated as per-item
     * — the conservative default, which names only the lead service.
     */
    public BookingStatus appointmentStatus() {
        return appointmentStatus;
    }

    public int size() {
        return items.size();
    }

    public boolean isMultiService() {
        return items.size() > 1;
    }

    /**
     * Service names in visit order — one entry per chained booking, duplicates retained.
     *
     * <p>{@code service_definitions.name} is {@code NOT NULL} and every query feeding a visit
     * fetch-joins it, so the former null-to-empty mapping was unreachable and is gone (Q9).
     */
    public List<String> serviceNames() {
        return items.stream()
                .map(BookingVisit::serviceNameOf)
                .toList();
    }

    /**
     * The first service performed — the one name that fits a length-capped channel (SMS, push).
     *
     * <p>Reads {@code items.get(0)} directly. It used to call {@link #serviceNames()} and take
     * element 0, building and discarding an N-element list to read one string — on the push and SMS
     * paths, which never render the full list at all.
     */
    public String leadServiceName() {
        return serviceNameOf(items.get(0));
    }

    private static String serviceNameOf(Booking booking) {
        return booking.getMasterService().getServiceDefinition().getName();
    }

    /**
     * Value equality over the three fields — this is a value object, and it was a {@code record}
     * until the double-copy/derived-value cleanup made a class the better fit. Keeping structural
     * equality means callers (and the {@code eq(visit)} argument matchers that pin which visit each
     * channel was handed) behave exactly as they did before that change — component-wise, on the
     * same three fields the record exposed.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof BookingVisit that
                && Objects.equals(lead, that.lead)
                && Objects.equals(items, that.items)
                && appointmentStatus == that.appointmentStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lead, items, appointmentStatus);
    }

    @Override
    public String toString() {
        // Never renders the items themselves: a Booking's toString would carry client PII and, for
        // some entities, note fields that must never reach a log (locked track-25 rule).
        return "BookingVisit[items=" + items.size() + ", appointmentStatus=" + appointmentStatus + "]";
    }

    /** When the visit begins: the earliest item's start. */
    public OffsetDateTime startsAt() {
        return items.get(0).getStartsAt();
    }

    /**
     * When the visit ends: the LATEST item end, taken as a max rather than as "the last element's
     * end". Ordering is by {@code startsAt}, so after a per-item reschedule the last-starting item
     * is not necessarily the last-ending one.
     */
    public OffsetDateTime endsAt() {
        return items.stream()
                .map(Booking::getEndsAt)
                .max(OffsetDateTime::compareTo)
                .orElseThrow();
    }

    /**
     * Sum of the frozen per-item service durations, buffers excluded — the same meaning
     * {@code GuestBookingResponse.durationMinutes} and {@code AppointmentDetailResponse
     * .totalDurationMinutes} already carry, so the e-mail cannot disagree with the API.
     */
    public int totalDurationMinutes() {
        return items.stream().mapToInt(Booking::getDurationMinutesAtBooking).sum();
    }
}
