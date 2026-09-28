package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.notification.inapp.dto.NotificationParams;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.NotificationTarget;
import com.beautica.notification.inapp.dto.TargetKind;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.service.BookingVisit;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves one PAGE of {@link InAppNotification} rows into {@link NotificationResponse}s
 * (phase 334) — params and target are computed fresh from LIVE rows the recipient is already
 * allowed to see, never stored on the feed row itself (phase 332/333 store ids only).
 *
 * <p><b>Bounded query count, never per-row.</b> Every lookup below is a single batched
 * statement over the whole page (or, for the salon-visibility checks, at most once per DISTINCT
 * salon the page touches — almost always one, since a page belongs to ONE recipient with ONE
 * fixed role). A CLIENT or MASTER/INDEPENDENT_MASTER recipient's page costs at most: the feed
 * page fetch (done by the caller) + one batched sibling-booking fetch (only if the page has a
 * visit event) + one batched full-booking-graph fetch (only if the page has any booking/visit
 * event) — never growing with page SIZE. A SALON_OWNER page adds one fixed {@code
 * findIdsByOwnerIdAndIsActiveTrue} call; a SALON_ADMIN page adds at most one
 * {@code adminBelongsToSalon} call per distinct salon (memoized). An {@code INVITE_ACCEPTED} row
 * adds one batched subject-user fetch; a {@code REVIEW_REQUESTED} row adds one batched
 * reviewed-booking-ids fetch. None of these scale with row COUNT within a page.
 *
 * <p><b>Visibility reuses {@code AuthorizationService#isViewAuthorized} — the pure predicate
 * extracted from {@code canViewBooking} (audit-fix cycle 1, phase 334 finding 2) — rather than a
 * hand-written copy of its CLIENT/SALON_MASTER/INDEPENDENT_MASTER/SALON_OWNER decision table.</b>
 * It is NOT called per row, unlike {@code canViewBooking} itself: every fact the predicate needs
 * (client id, performing master's user id + active flag, salon owner id) is read off the
 * already-batch-loaded {@code Booking} entity graph, so reuse costs no extra statement.
 *
 * <p>{@code canViewBooking} structurally excludes {@code SALON_ADMIN} from single-booking view
 * authority (see {@code AuthorizationService#isAuthorizedToManageBooking}'s javadoc) — which would
 * make every booking-typed notification of an admin's OWN salon inert, even though the write path
 * (phase 333, {@code InAppRecipientResolver#providerSet}) explicitly sends admins these rows. This
 * class therefore grants {@code SALON_ADMIN} visibility itself, via {@link #managesSalon} — a live
 * "still assigned to this salon" check, batched at most once per distinct salon per page — rather
 * than broadening the shared predicate (which every other {@code canViewBooking} caller would then
 * inherit).
 *
 * <p><b>The feed also applies extra, feed-specific liveness conditions ON TOP of the shared
 * predicate's result</b> for the {@code SALON_MASTER}/{@code INDEPENDENT_MASTER} and
 * {@code SALON_OWNER} arms — "was this recipient a legitimate party, and are they STILL one".
 * {@code isViewAuthorized} itself does not require the performing master to still be active on its
 * {@code INDEPENDENT_MASTER} branch, nor the salon to still be active on its
 * {@code SALON_OWNER}-matching branch (see that method's javadoc for why — those liveness rules
 * are {@code canViewBooking}-specific and must not change for its other callers). This class adds
 * both checks explicitly at the call site: see {@link #isVisible}.
 */
@Component
@RequiredArgsConstructor
class NotificationViewAssembler {

    private final BookingRepository bookingRepository;
    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final ReviewRepository reviewRepository;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    List<NotificationResponse> assemble(List<InAppNotification> rows, UUID actorId, Role actorRole) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<UUID> appointmentIds = idsOf(rows, InAppNotification::getAppointmentId);
        Map<UUID, BookingVisit> visitsByAppointment = appointmentIds.isEmpty()
                ? Map.of()
                : groupIntoVisits(bookingRepository.findByAppointmentIdsWithGraph(List.copyOf(appointmentIds)));

        Set<UUID> bookingIdsToHydrate = new LinkedHashSet<>(idsOf(rows, InAppNotification::getBookingId));
        visitsByAppointment.values().forEach(visit -> bookingIdsToHydrate.add(visit.items().get(0).getId()));
        Map<UUID, Booking> bookingsById = bookingIdsToHydrate.isEmpty()
                ? Map.of()
                : bookingRepository.findAllByIdsWithGraph(List.copyOf(bookingIdsToHydrate)).stream()
                        .collect(Collectors.toMap(Booking::getId, Function.identity()));

        Set<UUID> subjectUserIds = idsOf(rows, InAppNotification::getSubjectUserId);
        Map<UUID, User> subjectsById = subjectUserIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(subjectUserIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        Set<UUID> reviewRequestedBookingIds = rows.stream()
                .filter(r -> r.getType() == InAppNotificationType.REVIEW_REQUESTED && r.getBookingId() != null)
                .map(InAppNotification::getBookingId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> reviewedBookingIds = reviewRequestedBookingIds.isEmpty()
                ? Set.of()
                : Set.copyOf(reviewRepository.findReviewedBookingIds(List.copyOf(reviewRequestedBookingIds)));

        Set<UUID> ownedSalonIds = actorRole == Role.SALON_OWNER
                ? Set.copyOf(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                : Set.of();
        Map<UUID, Boolean> adminSalonMemo = new HashMap<>();

        OffsetDateTime now = OffsetDateTime.now(clock);

        return rows.stream()
                .map(row -> resolveOne(row, actorId, actorRole, bookingsById, visitsByAppointment, subjectsById,
                        reviewedBookingIds, ownedSalonIds, adminSalonMemo, now))
                .toList();
    }

    private NotificationResponse resolveOne(
            InAppNotification row, UUID actorId, Role actorRole,
            Map<UUID, Booking> bookingsById, Map<UUID, BookingVisit> visitsByAppointment,
            Map<UUID, User> subjectsById, Set<UUID> reviewedBookingIds, Set<UUID> ownedSalonIds,
            Map<UUID, Boolean> adminSalonMemo, OffsetDateTime now) {

        if (row.getType() == InAppNotificationType.INVITE_ACCEPTED) {
            return resolveInviteAccepted(row, actorId, actorRole, subjectsById, ownedSalonIds, adminSalonMemo);
        }
        return resolveBookingEvent(row, actorId, actorRole, bookingsById, visitsByAppointment,
                reviewedBookingIds, ownedSalonIds, adminSalonMemo, now);
    }

    // ── booking/visit-keyed events (every type except INVITE_ACCEPTED) ─────────────────────────

    private NotificationResponse resolveBookingEvent(
            InAppNotification row, UUID actorId, Role actorRole,
            Map<UUID, Booking> bookingsById, Map<UUID, BookingVisit> visitsByAppointment,
            Set<UUID> reviewedBookingIds, Set<UUID> ownedSalonIds, Map<UUID, Boolean> adminSalonMemo,
            OffsetDateTime now) {

        UUID resolvedBookingId = row.getBookingId();
        BookingVisit visit = row.getAppointmentId() != null ? visitsByAppointment.get(row.getAppointmentId()) : null;
        if (visit != null) {
            resolvedBookingId = visit.items().get(0).getId();
        }
        Booking booking = resolvedBookingId != null ? bookingsById.get(resolvedBookingId) : null;

        if (booking == null || !isVisible(booking, actorId, actorRole, ownedSalonIds, adminSalonMemo)) {
            return new NotificationResponse(row.getId(), row.getType(), row.getCreatedAt(),
                    row.getReadAt() != null, NotificationTarget.none(row.getSalonId()), null);
        }

        TargetKind kind = TargetKind.BOOKING;
        if (row.getType() == InAppNotificationType.REVIEW_REQUESTED) {
            boolean reviewable = BookingClosureRule.isReviewEligible(booking.getStatus(), booking.getEndsAt(), now)
                    && !reviewedBookingIds.contains(booking.getId());
            kind = reviewable ? TargetKind.BOOKING_REVIEW : TargetKind.BOOKING;
        }

        NotificationTarget target = new NotificationTarget(
                kind, booking.getId(), row.getAppointmentId(), booking.getSalon() != null ? booking.getSalon().getId() : null);
        NotificationParams params = buildBookingParams(booking, visit, actorRole);
        return new NotificationResponse(
                row.getId(), row.getType(), row.getCreatedAt(), row.getReadAt() != null, target, params);
    }

    private NotificationParams buildBookingParams(Booking booking, BookingVisit visit, Role actorRole) {
        Master master = booking.getMaster();
        Salon salon = booking.getSalon();
        User client = booking.getClient();

        String counterpartName = isProviderRole(actorRole)
                ? clientDisplayName(booking, client)
                : (salon != null ? salon.getName() : masterDisplayName(master));

        String serviceName = visit != null ? visit.leadServiceName()
                : booking.getMasterService().getServiceDefinition().getName();
        int serviceCount = visit != null ? visit.size() : 1;

        return new NotificationParams(
                counterpartName,
                serviceName,
                serviceCount,
                booking.getStartsAt().toInstant(),
                salon != null ? salon.getName() : null,
                null,
                null);
    }

    private static String clientDisplayName(Booking booking, User client) {
        if (client != null) {
            return joinName(client.getFirstName(), client.getLastName());
        }
        // Guest (LINK) booking, or a self-deleted client — Booking#detachClient already replaced
        // guestName/guestSurname with the sentinel «Видалений клієнт» for the latter case.
        return joinName(booking.getGuestName(), booking.getGuestSurname());
    }

    private static String masterDisplayName(Master master) {
        return joinName(master.displayFirstName(), master.displayLastName());
    }

    private static String joinName(String first, String last) {
        if (first == null) return last;
        if (last == null) return first;
        return first + " " + last;
    }

    private static boolean isProviderRole(Role role) {
        return role == Role.SALON_OWNER || role == Role.SALON_ADMIN
                || role == Role.SALON_MASTER || role == Role.INDEPENDENT_MASTER;
    }

    /**
     * "Was this recipient a legitimate party at write time, and are they still one" — see this
     * class's javadoc for the split between the shared {@code isViewAuthorized} predicate and the
     * extra liveness conditions applied here.
     */
    private boolean isVisible(
            Booking booking, UUID actorId, Role actorRole, Set<UUID> ownedSalonIds,
            Map<UUID, Boolean> adminSalonMemo) {
        if (actorRole == Role.SALON_ADMIN) {
            // canViewBooking/isViewAuthorized has no SALON_ADMIN branch at all (see this class's
            // javadoc) — the write path deliberately sends admins these rows, so this class grants
            // authority on its own, via the SAME "still assigned to this salon" rule INVITE_ACCEPTED
            // uses (managesSalon), rather than a second copy of it.
            return booking.getSalon() != null
                    && managesSalon(actorRole, booking.getSalon().getId(), ownedSalonIds, adminSalonMemo, actorId);
        }

        Master master = booking.getMaster();
        Salon salon = booking.getSalon();
        UUID clientId = booking.getClient() != null ? booking.getClient().getId() : null;
        UUID masterUserId = master.getUser() != null ? master.getUser().getId() : null;
        UUID salonOwnerId = salon != null && salon.getOwner() != null ? salon.getOwner().getId() : null;

        boolean baseAuthorized = authorizationService.isViewAuthorized(
                actorRole, actorId, clientId, masterUserId, master.isActive(), salonOwnerId);
        if (!baseAuthorized) {
            return false;
        }

        // Extra, feed-specific liveness conditions ON TOP of the shared predicate — canViewBooking's
        // other callers must not inherit these narrowings (see AuthorizationService#isViewAuthorized
        // javadoc). CLIENT is already fully decided above; SALON_ADMIN never reaches this line.
        return switch (actorRole) {
            case SALON_MASTER, INDEPENDENT_MASTER -> master.isActive();
            case SALON_OWNER -> salon != null && managesSalon(actorRole, salon.getId(), ownedSalonIds, adminSalonMemo, actorId);
            default -> true;
        };
    }

    /**
     * "Does {@code actorId}, as {@code actorRole}, currently manage {@code salonId}" — the single
     * copy of the rule both {@link #isVisible}'s {@code SALON_ADMIN} arm and
     * {@link #resolveInviteAccepted}'s "still manages" check apply, so the same fact ({@code
     * ownedSalonIds} membership for an owner, a memoized {@code adminBelongsToSalon} call for an
     * admin) is never re-derived a third way. Mirrors {@code AuthorizationService#hasManagementAccess}
     * in spirit (owner-by-ownership, admin-by-assignment) but reads from this page's already-batched
     * facts instead of issuing its own DB round trip per call.
     */
    private boolean managesSalon(
            Role actorRole, UUID salonId, Set<UUID> ownedSalonIds, Map<UUID, Boolean> adminSalonMemo, UUID actorId) {
        return switch (actorRole) {
            case SALON_OWNER -> ownedSalonIds.contains(salonId);
            case SALON_ADMIN -> adminSalonMemo.computeIfAbsent(
                    salonId, sid -> authorizationService.adminBelongsToSalon(actorId, sid));
            default -> false;
        };
    }

    // ── INVITE_ACCEPTED ──────────────────────────────────────────────────────────────────────

    private NotificationResponse resolveInviteAccepted(
            InAppNotification row, UUID actorId, Role actorRole, Map<UUID, User> subjectsById,
            Set<UUID> ownedSalonIds, Map<UUID, Boolean> adminSalonMemo) {

        boolean stillManages = row.getSalonId() != null
                && managesSalon(actorRole, row.getSalonId(), ownedSalonIds, adminSalonMemo, actorId);

        NotificationTarget target = stillManages
                ? new NotificationTarget(TargetKind.SALON_TEAM, null, null, row.getSalonId())
                : NotificationTarget.none(row.getSalonId());

        // Audit-fix cycle 1, finding 1 (HIGH): params must be null whenever the recipient no
        // longer manages the salon — a lost-access recipient must not still see the new teammate's
        // name/role, even though the subject row itself was fetched for the whole page. Gated on
        // stillManages FIRST, subject-lookup second, so a removed admin gets params=null regardless
        // of whether subjectsById happens to contain the row.
        User subject = stillManages && row.getSubjectUserId() != null
                ? subjectsById.get(row.getSubjectUserId()) : null;
        NotificationParams params = subject == null ? null : new NotificationParams(
                null, null, 0, null, null,
                joinName(subject.getFirstName(), subject.getLastName()), subject.getRole());

        return new NotificationResponse(
                row.getId(), row.getType(), row.getCreatedAt(), row.getReadAt() != null, target, params);
    }

    // ── batching helpers ─────────────────────────────────────────────────────────────────────

    private static Set<UUID> idsOf(List<InAppNotification> rows, Function<InAppNotification, UUID> extractor) {
        return rows.stream().map(extractor).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Groups the flat sibling-booking rows {@code findByAppointmentIdsWithGraph} returns into one
     * {@link BookingVisit} per appointment — reuses {@link BookingVisit#of}, the SAME visit-order
     * sort (startsAt, then id) the notification write path and the outbox email path both rely
     * on, so "the visit's earliest booking" can never be computed two different ways.
     */
    private static Map<UUID, BookingVisit> groupIntoVisits(List<Booking> siblingRows) {
        Map<UUID, List<Booking>> byAppointment = siblingRows.stream()
                .collect(Collectors.groupingBy(b -> b.getAppointment().getId()));
        Map<UUID, BookingVisit> result = new HashMap<>();
        byAppointment.forEach((appointmentId, items) -> result.put(appointmentId, BookingVisit.of(items.get(0), items)));
        return result;
    }
}
