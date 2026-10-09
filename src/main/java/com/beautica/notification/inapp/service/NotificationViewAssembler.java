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
public class NotificationViewAssembler {

    private final BookingRepository bookingRepository;
    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final ReviewRepository reviewRepository;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    /**
     * Everything the resolution needs that does NOT depend on who is looking: the live booking graph,
     * visits, invite subjects and review state of a set of rows, each fetched in one statement.
     */
    private record Hydrated(
            Map<UUID, Booking> bookingsById, Map<UUID, BookingVisit> visitsByAppointment,
            Map<UUID, User> subjectsById, Set<UUID> reviewedBookingIds) {
    }

    /**
     * The per-actor salon facts the visibility rules read. {@code adminSalonMemo} is lazily filled by
     * {@link #managesSalon}; the batch path pre-fills it so no admin pair costs its own statement.
     */
    private record ActorFacts(Set<UUID> ownedSalonIds, Map<UUID, Boolean> adminSalonMemo) {
    }

    /**
     * Public since phase 339: {@code InAppPushDispatcher} reuses the read API's resolution so the
     * push target and params are computed by the SAME code (never re-derived).
     */
    public List<NotificationResponse> assemble(List<InAppNotification> rows, UUID actorId, Role actorRole) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Hydrated hydrated = hydrate(rows);
        ActorFacts facts = new ActorFacts(
                actorRole == Role.SALON_OWNER
                        ? Set.copyOf(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                        : Set.of(),
                new HashMap<>());
        OffsetDateTime now = OffsetDateTime.now(clock);

        return rows.stream()
                .map(row -> resolveOne(row, actorId, actorRole, hydrated, facts, now))
                .toList();
    }

    /**
     * Phase 339 (audit cycle 2, perf P2): resolves rows of MANY recipients at once for the push drain.
     * The shared hydration (bookings, visits, subjects, review state) runs ONCE for the whole set, the
     * per-recipient salon facts are batched (one statement for all owners, one for all admins), and each
     * row is then resolved by the very same {@link #resolveOne} the single-actor {@link #assemble} uses
     * — with ITS recipient's id and role, so visibility is decided per recipient exactly as in the feed.
     * The statement count is therefore independent of the number of distinct recipients.
     *
     * @param rolesByRecipient role of every recipient present in {@code rows}; a row whose recipient is
     *                         absent is skipped
     * @return the resolved view keyed by feed-row id
     */
    public Map<UUID, NotificationResponse> assembleForRecipients(
            List<InAppNotification> rows, Map<UUID, Role> rolesByRecipient) {
        List<InAppNotification> known = rows.stream()
                .filter(row -> rolesByRecipient.containsKey(row.getRecipientUserId()))
                .toList();
        if (known.isEmpty()) {
            return Map.of();
        }
        Hydrated hydrated = hydrate(known);
        Map<UUID, ActorFacts> factsByRecipient = batchActorFacts(known, rolesByRecipient, hydrated);
        OffsetDateTime now = OffsetDateTime.now(clock);

        Map<UUID, NotificationResponse> views = new HashMap<>();
        for (InAppNotification row : known) {
            UUID recipientId = row.getRecipientUserId();
            views.put(row.getId(), resolveOne(row, recipientId, rolesByRecipient.get(recipientId),
                    hydrated, factsByRecipient.get(recipientId), now));
        }
        return views;
    }

    private Hydrated hydrate(List<InAppNotification> rows) {
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

        return new Hydrated(bookingsById, visitsByAppointment, subjectsById, reviewedBookingIds);
    }

    /**
     * One owner statement + one admin statement for the whole recipient set (each skipped when no
     * recipient has that role). Admin memos are pre-filled for every salon the rows touch, so
     * {@link #managesSalon} never falls through to a per-pair {@code adminBelongsToSalon} call.
     */
    private Map<UUID, ActorFacts> batchActorFacts(
            List<InAppNotification> rows, Map<UUID, Role> rolesByRecipient, Hydrated hydrated) {
        Set<UUID> recipients = rows.stream().map(InAppNotification::getRecipientUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> owners = recipientsWithRole(recipients, rolesByRecipient, Role.SALON_OWNER);
        Set<UUID> admins = recipientsWithRole(recipients, rolesByRecipient, Role.SALON_ADMIN);

        Map<UUID, Set<UUID>> ownedByOwner = owners.isEmpty() ? Map.of()
                : salonRepository.findOwnedActiveSalonPairs(owners).stream()
                        .collect(Collectors.groupingBy(SalonRepository.OwnerSalonPair::getOwnerId,
                                Collectors.mapping(SalonRepository.OwnerSalonPair::getSalonId, Collectors.toSet())));
        Map<UUID, Set<UUID>> assignedByAdmin = admins.isEmpty() ? Map.of()
                : userRepository.findSalonAssignments(admins, Role.SALON_ADMIN).stream()
                        .collect(Collectors.groupingBy(UserRepository.UserSalonPair::getUserId,
                                Collectors.mapping(UserRepository.UserSalonPair::getSalonId, Collectors.toSet())));
        Set<UUID> touchedSalonIds = admins.isEmpty() ? Set.of() : touchedSalonIds(rows, hydrated);

        Map<UUID, ActorFacts> facts = new HashMap<>();
        for (UUID recipientId : recipients) {
            Map<UUID, Boolean> adminMemo = new HashMap<>();
            if (admins.contains(recipientId)) {
                Set<UUID> assigned = assignedByAdmin.getOrDefault(recipientId, Set.of());
                touchedSalonIds.forEach(salonId -> adminMemo.put(salonId, assigned.contains(salonId)));
            }
            facts.put(recipientId, new ActorFacts(ownedByOwner.getOrDefault(recipientId, Set.of()), adminMemo));
        }
        return facts;
    }

    private static Set<UUID> recipientsWithRole(Set<UUID> recipients, Map<UUID, Role> roles, Role role) {
        return recipients.stream().filter(id -> roles.get(id) == role)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Every salon id {@link #managesSalon} could be asked about for these rows. */
    private static Set<UUID> touchedSalonIds(List<InAppNotification> rows, Hydrated hydrated) {
        Set<UUID> salonIds = new LinkedHashSet<>();
        rows.forEach(row -> {
            if (row.getSalonId() != null) {
                salonIds.add(row.getSalonId());
            }
        });
        hydrated.bookingsById().values().forEach(booking -> {
            if (booking.getSalon() != null) {
                salonIds.add(booking.getSalon().getId());
            }
        });
        return salonIds;
    }

    private NotificationResponse resolveOne(
            InAppNotification row, UUID actorId, Role actorRole, Hydrated hydrated, ActorFacts facts,
            OffsetDateTime now) {

        if (row.getType() == InAppNotificationType.INVITE_ACCEPTED) {
            return resolveInviteAccepted(row, actorId, actorRole, hydrated.subjectsById(), facts);
        }
        return resolveBookingEvent(row, actorId, actorRole, hydrated, facts, now);
    }

    // ── booking/visit-keyed events (every type except INVITE_ACCEPTED) ─────────────────────────

    private NotificationResponse resolveBookingEvent(
            InAppNotification row, UUID actorId, Role actorRole, Hydrated hydrated, ActorFacts facts,
            OffsetDateTime now) {

        UUID resolvedBookingId = row.getBookingId();
        BookingVisit visit = row.getAppointmentId() != null
                ? hydrated.visitsByAppointment().get(row.getAppointmentId()) : null;
        if (visit != null) {
            resolvedBookingId = visit.items().get(0).getId();
        }
        Booking booking = resolvedBookingId != null ? hydrated.bookingsById().get(resolvedBookingId) : null;

        if (booking == null || !isVisible(booking, actorId, actorRole, facts)) {
            return new NotificationResponse(row.getId(), row.getType(), row.getCreatedAt(),
                    row.getReadAt() != null, NotificationTarget.none(row.getSalonId()), null);
        }

        TargetKind kind = TargetKind.BOOKING;
        if (row.getType() == InAppNotificationType.REVIEW_REQUESTED) {
            boolean reviewable = BookingClosureRule.isReviewEligible(booking.getStatus(), booking.getEndsAt(), now)
                    && !hydrated.reviewedBookingIds().contains(booking.getId());
            kind = reviewable ? TargetKind.BOOKING_REVIEW : TargetKind.BOOKING;
        }

        NotificationTarget target = new NotificationTarget(
                kind, booking.getId(), row.getAppointmentId(), booking.getSalon() != null ? booking.getSalon().getId() : null);
        NotificationParams params = buildBookingParams(row.getType(), booking, visit, actorId, actorRole);
        return new NotificationResponse(
                row.getId(), row.getType(), row.getCreatedAt(), row.getReadAt() != null, target, params);
    }

    private NotificationParams buildBookingParams(
            InAppNotificationType type, Booking booking, BookingVisit visit, UUID actorId, Role actorRole) {
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
                performingMasterName(type, master, salon, actorId, actorRole, isSingleMaster(visit)),
                null,
                null);
    }

    /**
     * Salon-side recipients (owner/admin) of a salon booking-created/cancelled-by-client/rescheduled
     * event learn which master performs it — unless they ARE that master (the owner can be). Reads
     * only the already-hydrated master/user graph (loaded for {@link #isVisible}); no query.
     */
    static String performingMasterName(
            InAppNotificationType type, Master master, Salon salon, UUID actorId, Role actorRole,
            boolean singleMaster) {
        boolean eligibleType = type == InAppNotificationType.BOOKING_CREATED
                || type == InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT
                || type == InAppNotificationType.BOOKING_RESCHEDULED;
        boolean salonStaff = actorRole == Role.SALON_OWNER || actorRole == Role.SALON_ADMIN;
        if (salon == null || !eligibleType || !salonStaff || !singleMaster) {
            return null;
        }
        User masterUser = master.getUser();
        if (masterUser != null && masterUser.getId().equals(actorId)) {
            return null;
        }
        return masterDisplayName(master);
    }

    /**
     * Defensive: visits are single-master by construction, but the params are built from the
     * visit's first item — if items ever span masters, no single name is correct, so omit it.
     */
    static boolean isSingleMaster(BookingVisit visit) {
        return visit == null || visit.items().stream()
                .map(b -> b.getMaster().getId()).distinct().count() <= 1;
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
    private boolean isVisible(Booking booking, UUID actorId, Role actorRole, ActorFacts facts) {
        if (actorRole == Role.SALON_ADMIN) {
            // canViewBooking/isViewAuthorized has no SALON_ADMIN branch at all (see this class's
            // javadoc) — the write path deliberately sends admins these rows, so this class grants
            // authority on its own, via the SAME "still assigned to this salon" rule INVITE_ACCEPTED
            // uses (managesSalon), rather than a second copy of it.
            return booking.getSalon() != null
                    && managesSalon(actorRole, booking.getSalon().getId(), facts, actorId);
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
            case SALON_OWNER -> salon != null && managesSalon(actorRole, salon.getId(), facts, actorId);
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
    private boolean managesSalon(Role actorRole, UUID salonId, ActorFacts facts, UUID actorId) {
        return switch (actorRole) {
            case SALON_OWNER -> facts.ownedSalonIds().contains(salonId);
            case SALON_ADMIN -> facts.adminSalonMemo().computeIfAbsent(
                    salonId, sid -> authorizationService.adminBelongsToSalon(actorId, sid));
            default -> false;
        };
    }

    // ── INVITE_ACCEPTED ──────────────────────────────────────────────────────────────────────

    private NotificationResponse resolveInviteAccepted(
            InAppNotification row, UUID actorId, Role actorRole, Map<UUID, User> subjectsById,
            ActorFacts facts) {

        boolean stillManages = row.getSalonId() != null
                && managesSalon(actorRole, row.getSalonId(), facts, actorId);

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
                null, null, 0, null, null, null,
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
