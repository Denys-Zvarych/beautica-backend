package com.beautica.notification.service;

import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hydrates the sibling booking rows of a multi-service visit for the notification path.
 *
 * <p>Lives beside {@link NotificationOutboxDrainWorker} — the one component that already owns
 * booking hydration for dispatch — rather than inside {@link NotificationService}, which stays a
 * pure transport facade with no repository of its own. The resulting {@link BookingVisit} is
 * threaded through every channel, so provider e-mail, client e-mail and push can never describe
 * different service sets.
 *
 * <p><b>One query per DRAIN, not per entry.</b> {@link #hydrate(Collection)} resolves every visit
 * in the claimed batch in a single statement; {@link #resolve(Booking, Map)} is then pure and
 * issues nothing. This shape is required, not merely nice: the earlier per-entry resolver was
 * called from the drain worker's phase 2, which is contractually connection-free, and it ran twice
 * per created visit (a visit enqueues both {@code NEW_BOOKING} and {@code STATUS_CHANGED} against
 * the same lead booking) — up to 50 connection check-outs interleaved with ~25 s SMTP calls.
 *
 * <p><b>Correction to an earlier claim.</b> The previous javadoc asserted that "a single-service
 * booking (the overwhelming majority) costs ZERO queries" because its {@code appointment_id} FK is
 * null. That is FALSE in production: the mobile client posts EVERY booking — one service or ten —
 * to {@code POST /appointments} ({@code booking_notifier.dart}), so {@code appointment != null} is
 * the norm and a one-service visit was paying the same per-entry query as a ten-service one. The
 * genuinely query-free case is narrow: guest LINK bookings and the legacy {@code POST /bookings}
 * path. Batching is what actually bounds the cost — one statement per drain regardless of mix.
 *
 * <p><b>Detached-proxy safety.</b> This runs in the drain worker's phase 2, which is
 * {@code Propagation.NOT_SUPPORTED} with {@code open-in-view: false} — the booking is detached and
 * {@code booking.getAppointment()} is an uninitialised LAZY proxy. Only {@code getId()} is called on
 * the proxy, which Hibernate serves off the stored identifier without a statement and without
 * initialisation. The header STATUS is read only off the rows returned by
 * {@link BookingRepository#findByAppointmentIdsWithGraph}, which {@code JOIN FETCH}es the
 * appointment — never off the caller's proxy. Do not widen proxy access to any other property.
 *
 * <p>The SAME rule governs the sibling rows this class hydrates: that query fetches only
 * {@code appointment}, {@code masterService} and {@code serviceDefinition}, so an item's
 * {@code master} and {@code client} are detached uninitialised proxies. The tenancy filter reads
 * their {@code getId()} and nothing else. {@code MultiServiceNotificationIT#should_takeNoFurtherStatement_when_theResolverReadsSiblingPartyIdsOffDetachedProxies}
 * pins that this costs zero statements and throws no {@code LazyInitializationException}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingVisitResolver {

    private final BookingRepository bookingRepository;

    /**
     * Loads the chained rows of every visit the given lead bookings belong to, in ONE query, keyed
     * by appointment id. Bookings with no appointment contribute nothing and cost nothing.
     *
     * @param leads the lead bookings of the outbox entries about to be dispatched
     * @return appointment id → its booking rows; empty when no lead belongs to a visit
     */
    public Map<UUID, List<Booking>> hydrate(Collection<Booking> leads) {
        Set<UUID> appointmentIds = new LinkedHashSet<>();
        for (Booking lead : leads) {
            Appointment appointment = lead.getAppointment();
            if (appointment != null) {
                appointmentIds.add(appointment.getId());
            }
        }
        if (appointmentIds.isEmpty()) {
            return Map.of();
        }
        return bookingRepository.findByAppointmentIdsWithGraph(List.copyOf(appointmentIds)).stream()
                .collect(Collectors.groupingBy(b -> b.getAppointment().getId()));
    }

    /**
     * Builds the {@link BookingVisit} for one outbox entry from the already-hydrated batch — never
     * queries.
     *
     * <p><b>Two filters are applied to the sibling rows, and both are load-bearing.</b>
     *
     * <p>1. <i>Status.</i> Only items sharing {@code lead}'s status belong to the event being
     * notified. Without this, a {@code STATUS_CHANGED} row that retries after an SMTP failure — or
     * that drains after a per-item decline landed in the same window — would mail the client a
     * CONFIRMED list still naming a service that has since been cancelled. It is also what
     * distinguishes a per-item decline (siblings still {@code CONFIRMED}, so the visit collapses to
     * the one declined service) from a whole-visit decline (every item {@code DECLINED}).
     *
     * <p>2. <i>Tenancy — on BOTH parties.</i> Any item whose master OR client differs from the
     * lead's is dropped with a warning. Nothing at DB level requires rows sharing an
     * {@code appointment_id} to share a {@code master_id} or a {@code client_id} (V125 adds only the
     * FK + index), and today nothing writes such a row — no code path reassigns
     * {@code Booking.master} or {@code Booking.client} after creation. But this resolver is the first
     * component that would turn such a mis-wiring into a cross-tenant disclosure: another party's
     * service names rendered into a mail body. The CLIENT leg is the one that matters most, because
     * it is the direction the mail actually travels — {@code sendVisitDeclinedEmail} and
     * {@code sendBookingConfirmedEmail} are addressed to {@code lead.getClient().getEmail()}, so a
     * foreign row chained under the SAME master but a DIFFERENT client would render that client's
     * service names into this client's inbox. Guest visits carry a null {@code client} on every row
     * and must keep working, hence the null-tolerant comparison. Both legs read only the
     * IDENTIFIER — never a property that would initialise the detached proxy (see the
     * detached-proxy note on this class).
     *
     * <p>If either filter empties the list, the visit degrades to {@link BookingVisit#single} on the
     * lead — a notification still goes out rather than dead-lettering.
     *
     * @param lead     the booking an outbox entry is keyed to — becomes {@link BookingVisit#lead()}
     * @param siblings the {@link #hydrate(Collection)} result for the batch this lead came from
     */
    public BookingVisit resolve(Booking lead, Map<UUID, List<Booking>> siblings) {
        Appointment appointment = lead.getAppointment();
        if (appointment == null) {
            return BookingVisit.single(lead);
        }
        UUID appointmentId = appointment.getId();
        List<Booking> items = siblings.get(appointmentId);
        if (items == null || items.isEmpty()) {
            // Defensive: an appointment header with no surviving chained rows cannot be produced by
            // any create path (both persist header + items in one transaction). Degrading to the
            // lead booking keeps the notification going out rather than dead-lettering the entry.
            log.warn("Appointment {} resolved to zero booking items — notifying on the lead booking only",
                    appointmentId);
            return BookingVisit.single(lead);
        }
        List<Booking> scoped = items.stream()
                .filter(item -> item.getStatus() == lead.getStatus())
                .filter(item -> belongsToSameParties(item, lead, appointmentId))
                .toList();
        if (scoped.isEmpty()) {
            return BookingVisit.single(lead);
        }
        return BookingVisit.of(lead, scoped, items.get(0).getAppointment().getStatus());
    }

    private static boolean belongsToSameParties(Booking item, Booking lead, UUID appointmentId) {
        if (!item.getMaster().getId().equals(lead.getMaster().getId())) {
            log.warn("Appointment {} chains booking {} owned by a DIFFERENT master — excluded from the "
                    + "notification to prevent cross-tenant disclosure", appointmentId, item.getId());
            return false;
        }
        if (!Objects.equals(clientIdOf(item), clientIdOf(lead))) {
            log.warn("Appointment {} chains booking {} owned by a DIFFERENT client — excluded from the "
                    + "notification to prevent cross-tenant disclosure", appointmentId, item.getId());
            return false;
        }
        return true;
    }

    /**
     * The item's client IDENTIFIER, or {@code null} for a guest (LINK) visit — {@code client_id} is
     * nullable since V89. Reads {@code getId()} off the uninitialised LAZY proxy, which Hibernate
     * serves off the stored FK without a statement; {@code findByAppointmentIdsWithGraph}
     * deliberately does not fetch {@code b.client}, so dereferencing any OTHER property here would
     * throw {@code LazyInitializationException} on the detached row.
     */
    private static UUID clientIdOf(Booking booking) {
        User client = booking.getClient();
        return client == null ? null : client.getId();
    }
}
