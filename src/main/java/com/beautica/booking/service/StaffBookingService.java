package com.beautica.booking.service;

import com.beautica.booking.domain.MasterBookability;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.StaffBookingCommand;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.booking.dto.StaffClientRef;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.UkrainianPhoneNormalizer;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Validates and persists a <b>staff-created booking</b> — the walk-in / phone-in appointment a
 * {@code SALON_OWNER}, {@code SALON_ADMIN} or {@code INDEPENDENT_MASTER} keys in on behalf of a
 * master (Phase 22.2).
 *
 * <h2>What this class owns, and what it deliberately does not</h2>
 * It owns validation and persistence. It owns <b>no</b> HTTP surface and <b>no</b> authorization:
 * Phase 22.4 adds the endpoint, the role gate and the {@code canBookForMaster} predicate. The one
 * authorization-shaped line here — {@link #assertMasterInScope} — is defence in depth behind that
 * gate, not a substitute for it. It is <b>never</b> skipped: {@link StaffBookingScope} is sealed over
 * exactly the two modes 22.4 authorizes, so every command names its authority and every arm of the
 * exhaustive switch asserts something (security MEDIUM, 2026-08-18 — the nullable {@code salonId}
 * this replaced left {@code masterId} wholly unconstrained whenever it was null).
 *
 * <h2>Nothing is re-implemented</h2>
 * Every rule this phase must enforce already had exactly one home, and each is called rather than
 * copied:
 * <ul>
 *   <li><b>master bookability</b> — {@link MasterBookability#isBookable} over
 *       {@code MasterRepository#findByIdWithUserAndSalon}, the same pair
 *       {@code BookingService#doCreateBooking} uses (and the same deliberately-indistinct 404);</li>
 *   <li><b>service eligibility + price/duration/buffer snapshot</b> — {@link VisitPlanner}, the
 *       component the APP and LINK visit paths already share. Its master-scoped, {@code isActive}-
 *       filtered assignment lookup IS the "does this master perform this service?" proof, and its
 *       {@code priceOverride ?? basePrice} / {@code durationOverride ?? baseDuration} /
 *       {@code bufferMinutesAfter} / {@code BookingPriceRange#resolveCeiling} arithmetic is the
 *       snapshot rule, so a staff booking's frozen columns cannot drift from a client booking's;</li>
 *   <li><b>working hours / day-off / gap containment</b> —
 *       {@link BookingSlotAvailabilityGuard#assertStaffStartsOnAvailableSlot}, which runs the same
 *       {@code SlotCalculationService} effective-day oracle every other create path is proved
 *       against;</li>
 *   <li><b>double-book</b> — {@link BookingSlotLockGuard}, shared verbatim with
 *       {@code GuestBookingService};</li>
 *   <li><b>lead time / horizon</b> — {@code BookingStartsAtValidator#validateStaff}.</li>
 * </ul>
 *
 * <h2>Single-service, and why (the {@code appointments} CHECK)</h2>
 * {@link StaffBookingCommand} carries a scalar {@code masterServiceId}, so a staff booking is always
 * ONE {@code bookings} row with {@code appointment_id} NULL. That is a decision, not an omission: a
 * multi-service visit persists an {@code appointments} header, and
 * {@code chk_appointment_source} (V124:54 — note the SINGULAR table name, the phase doc calls it
 * {@code chk_appointments_source}) still admits only {@code ('APP','LINK')}. V137 widened the
 * {@code bookings} constraint alone, deliberately. A multi-service staff visit would therefore fail
 * at the appointment insert, so it is refused before any write ({@link #onlyItem}) and no migration
 * is introduced for a capability nothing offers — the approved mobile design is a single-service
 * wizard. Widening {@code chk_appointment_source} is the FIRST thing a future multi-service staff
 * track must ship; {@code StaffBookingIT} pins the constraint's current shape so that day goes red
 * loudly instead of at runtime.
 *
 * <h2>Locked domain rules honoured here</h2>
 * The booking is born {@code CONFIRMED} via {@code Booking#staffBooking} (track 24.x: every booking
 * is). There is no status-writing path of any kind — {@code AWAITING_CLOSURE} stays read-time
 * derived. Exactly ONE booking row is written, never a sibling. {@code priceMaxAtBooking} is a
 * snapshot taken here and never re-derived. "Now" comes only from the injected {@link Clock}.
 *
 * <h2>Not here</h2>
 * No SMS and no notification. The walk-in confirmation SMS is Phase 22.7 (shipped disabled), and
 * the phase doc's step list for this service ends at "return {@code BookingResponse}" — no outbox
 * enqueue is specified for a staff booking, and inventing one now would risk double-notifying when
 * 22.7 lands. Flagged rather than guessed.
 */
@Service
@RequiredArgsConstructor
public class StaffBookingService {

    private final MasterRepository masterRepository;
    private final BookingRepository bookingRepository;
    private final SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final VisitPlanner visitPlanner;
    private final Clock clock;

    /**
     * Creates one {@code CONFIRMED}, {@code STAFF}-sourced booking for an account-less walk-in
     * client.
     *
     * <p><b>Order is load-bearing</b>, and mirrors {@code BookingService#doCreateBooking}'s:
     * cheap non-DB guards, then the master, then the assignment (both 404-shaped), then lead time,
     * then schedule fit, and only then the per-master advisory lock — so a request that is
     * off-schedule, or for a service the master does not perform, never contends for the lock every
     * other request against that master is queued on.
     *
     * @param cmd     the fully-resolved command — every field of which may legitimately originate in
     *                the request body
     * @param actorId the authenticated staff user keying the booking in, persisted to
     *                {@code bookings.created_by_user_id} (V137) as the ONLY audit trail a staff
     *                booking carries. <b>A separate parameter on purpose</b> (security MEDIUM,
     *                2026-08-18): while it sat on {@link StaffBookingCommand} beside the
     *                client-supplied values, 22.4's natural {@code request.toCommand()} mapping was
     *                one field away from letting a caller attribute a walk-in to a different staff
     *                user. The controller MUST source it from the security context.
     * @throws NotFoundException   404 — unknown/inactive master (or a master whose salon is closed),
     *                             or a {@code masterServiceId} that is unknown, foreign or inactive.
     *                             Both are deliberately indistinguishable from each other's message.
     * @throws ForbiddenException  403 — the master is outside the command's {@link StaffBookingScope}
     *                             (wrong salon, a {@code Self} scope naming a user other than
     *                             {@code actorId}, or not the self-booking master), or
     *                             {@code actorId} is absent
     * @throws BusinessException   400 — past {@code startsAt}, beyond the 180-day horizon, missing
     *                             walk-in name/surname/phone, a control character in either name, or
     *                             an unparseable phone;
     *                             409 — off-schedule start, or a window that collides with an
     *                             existing {@code CONFIRMED} booking;
     *                             501 — an {@link StaffClientRef.ExistingClient} subject (Phase 22.3)
     */
    @Transactional
    public BookingResponse createStaffBooking(StaffBookingCommand cmd, UUID actorId) {
        // §B shape: a missing principal is a 403, never a 500 from a null slipping into the insert
        // and tripping the created_by_user_id NOT NULL at flush.
        if (actorId == null) {
            throw new ForbiddenException("Invalid authentication context");
        }
        // findByIdWithUserAndSalon (not a salon-only variant): the Self scope arm below reads
        // master.getUser().getId(), so the `user` JOIN FETCH is load-bearing, not surplus — see the
        // class-level note under "Nothing is re-implemented".
        //
        // THIS IS THE SECOND READ OF THE SAME ROW, AND IT IS DELIBERATE (LOW, security + perf,
        // accepted 2026-08-18). AuthorizationService#canBookForMaster already loaded this master by
        // the same PK in the @PreAuthorize gate. Do not "optimise" the pair away:
        //   * Passing the gate's instance in as a `preloaded` argument saves nothing.
        //     open-in-view is false and StaffBookingController is not @Transactional, so that
        //     instance is DETACHED by the time this method's persistence context opens; using it
        //     would need a merge(), which reissues this exact SELECT. Net zero, plus a merge. This
        //     is NOT the VisitPlanner `preloaded` pattern used a few lines below — that one hands
        //     entities down WITHIN one transaction, where they stay managed.
        //   * This read is a TOCTOU NARROWING, not waste: MasterBookability and master.getSalon()
        //     are re-evaluated against committed state INSIDE the transaction that inserts. Reusing
        //     the gate's snapshot would widen the race to span the whole authz step, so a master
        //     deactivated (or a salon closed) between gate and insert would still get a booking.
        //   * Nor may the gate move in here: that forfeits the before-handler ordering which makes
        //     the 404 below unreachable over HTTP for an unauthorized caller — this phase's core
        //     property. See AuthorizationService#canBookForMaster's "Accepted costs" javadoc.
        Master master = masterRepository.findByIdWithUserAndSalon(cmd.masterId())
                .filter(MasterBookability::isBookable)
                .orElseThrow(() -> new NotFoundException("Master not found or inactive"));
        assertMasterInScope(master, cmd.scope(), actorId);

        // STAFF floor: "now" is bookable, the past is not. Deliberately NOT the shared ≥15-min guard.
        BookingStartsAtValidator.validateStaff(cmd.startsAt(), clock);

        // Service eligibility (404 for unknown/foreign/inactive) AND the price/duration/buffer/
        // priceMax snapshot, both from the shared planner — see the class Javadoc.
        VisitPlanner.PlannedItem item = onlyItem(
                visitPlanner.planChainedItems(master, List.of(cmd.masterServiceId()), cmd.startsAt()));

        // The new guarantee this phase adds: the start must be a real slot in the master's resolved
        // schedule — not a day-off, not a custom-hours gap, not an off-grid minute. `item`'s
        // assignment is handed through so the gate does not re-issue the finder the planner just ran.
        // The gate asks an EXISTENCE question (perf LOW, 2026-08-18): the client path amortises a
        // materialised whole-day slot list through the `available-slots` cache, but the staff list is
        // uncached, so building the whole day's AvailableSlotResponse list to answer one boolean was
        // paid on every single create with zero reuse. (Sizing: SLOT_STEP is 30 minutes and a day's
        // work intervals are disjoint, so a Kyiv day holds at most 48 grid positions; a realistic
        // 9-12h working day yields 17-24 objects, each holding two ZonedDateTimes.)
        BookingSlotAvailabilityGuard.assertStaffStartsOnAvailableSlot(
                slotCalculationService, master.getId(), item.masterService().getId(),
                item.masterService(), cmd.startsAt());

        StaffClientRef.Guest guest = requireWalkIn(cmd.client());
        // MUST precede the insert: chk_bookings_guest_phone_format (V89) enforces ^\+[0-9]{6,18}$ in
        // the DATABASE, so a staff-typed "050 123 45 67" is rejected as a 500-shaped constraint
        // violation, not stored. See UkrainianPhoneNormalizer.
        String guestPhone = UkrainianPhoneNormalizer.toE164(guest.phone());

        BookingSlotLockGuard.lockMasterAndAssertFree(
                bookingRepository, master.getId(), item.startsAt(), item.endsAt());

        Booking saved = BookingSlotLockGuard.saveOrConflict(bookingRepository, Booking.staffBooking(
                master, item.masterService(), master.getSalon(),
                item.startsAt(), item.endsAt(), item.price(), item.priceMax(),
                item.duration(), item.buffer(),
                guest.name(), guest.surname(), guestPhone,
                actorId));

        registerSlotEviction(master.getId(), salonIdOf(saved));
        return BookingResponse.from(saved, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * Defence in depth behind Phase 22.4's authz gate: the target master must fall inside the
     * authority the command was created under.
     *
     * <p><b>Exhaustive, and every arm asserts</b> (security MEDIUM, 2026-08-18). This used to take a
     * nullable {@code UUID salonId} and {@code return} early on {@code null}, which meant an
     * independent-master command bought no scoping at all: {@code masterId} was unconstrained and the
     * service would happily book any master in any salon. {@link StaffBookingScope} makes the two
     * modes distinct types, so there is no "absent" state to return early on and the compiler names
     * this site if a third mode is ever added.
     *
     * <ul>
     *   <li>{@link StaffBookingScope.InSalon} — the master's salon must be that salon. Unchanged
     *       behaviour, including the {@code salon == null} rejection: a salon-scoped caller may not
     *       reach a salon-less independent master.</li>
     *   <li>{@link StaffBookingScope.Self} — the named user must be {@code actorId} AND must be the
     *       master's own {@code User}. This is 22.4's
     *       {@code target.getUser().getId().equals(callerId)} branch restated in the domain layer,
     *       and it is why {@code findByIdWithUserAndSalon} keeps its {@code user} JOIN FETCH.</li>
     * </ul>
     *
     * <h2>Why only {@code Self} is cross-checked against {@code actorId} (security MEDIUM, 2026-08-18)</h2>
     * The asymmetry is deliberate, not an oversight. {@link StaffBookingScope} is a component of
     * {@link StaffBookingCommand} — the record whose every OTHER field may legitimately come from the
     * request body — so 22.4's natural {@code request.toCommand()} mapping is one field away from
     * making the scope body-sourced. {@code Self} names a <b>user id</b>, and this service already
     * holds the only TRUSTED identity in the transaction ({@code actorId}, sourced from the security
     * context). Comparing the two costs nothing and no DB read, and it makes a body-sourced
     * {@code Self} inert whatever 22.4 does: the attacker can only ever name themselves, and the
     * following {@code master.getUser()} comparison then confines them to their own calendar.
     *
     * <p>{@code InSalon} gets NO equivalent treatment on purpose. Its claim is "the caller manages
     * this salon", which cannot be settled from {@code actorId} alone — it needs a salon-membership /
     * ownership lookup, and that lookup is 22.4's {@code @authz.canBookForMaster}, deliberately not
     * duplicated here (Anti-Bug §D: never run the same authz check in two layers, and never issue a
     * second round-trip for a verdict the gate already reached). So the salon arm's trust boundary
     * lives in the controller; this arm only proves the master belongs to the salon the gate cleared.
     *
     * <p>All failures are a {@code 403} with a message that reveals nothing about the master beyond
     * "not yours" — probing must not distinguish "in another salon" from "does not exist", which the
     * preceding indistinct 404 already ensures, nor "wrong actor" from "wrong master".
     */
    private static void assertMasterInScope(Master master, StaffBookingScope scope, UUID actorId) {
        switch (scope) {
            case StaffBookingScope.InSalon inSalon -> {
                Salon salon = master.getSalon();
                if (salon == null || !inSalon.salonId().equals(salon.getId())) {
                    throw new ForbiddenException("Master does not belong to this salon");
                }
            }
            case StaffBookingScope.Self self -> {
                // Trusted-vs-untrusted first: a Self scope may only ever name the acting user.
                if (!self.masterUserId().equals(actorId)) {
                    throw new ForbiddenException("Master does not belong to this account");
                }
                User user = master.getUser();
                if (user == null || !self.masterUserId().equals(user.getId())) {
                    throw new ForbiddenException("Master does not belong to this account");
                }
            }
        }
    }

    /**
     * Asserts the planned visit is exactly one service — the single-service invariant the
     * {@code chk_appointment_source} CHECK forces on this track (see the class Javadoc).
     *
     * <p>Unreachable today because {@link StaffBookingCommand} carries a scalar
     * {@code masterServiceId}, which is the point: the invariant is enforced by the TYPE, and this
     * is the assertion that catches the day someone widens the command to a list without shipping
     * the migration first. A clear 400 beats a constraint violation surfacing as a 500.
     */
    private static VisitPlanner.PlannedItem onlyItem(List<VisitPlanner.PlannedItem> items) {
        if (items.size() != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "A staff booking must contain exactly one service");
        }
        return items.get(0);
    }

    /**
     * Narrows the sealed client reference to the only variant this phase persists.
     *
     * <p>An exhaustive pattern switch with no {@code default}: when Phase 22.3 lights up
     * {@link StaffClientRef.ExistingClient} it replaces this arm, and if a third variant is ever
     * added the compiler names this site rather than letting it fall through.
     */
    private static StaffClientRef.Guest requireWalkIn(StaffClientRef client) {
        return switch (client) {
            case StaffClientRef.Guest guest -> guest;
            case StaffClientRef.ExistingClient ignored -> throw new BusinessException(
                    HttpStatus.NOT_IMPLEMENTED, "Booking an existing client is not supported yet");
        };
    }

    private static UUID salonIdOf(Booking booking) {
        Salon salon = booking.getSalon();
        return salon != null ? salon.getId() : null;
    }

    /**
     * A new {@code CONFIRMED} booking changes this master's occupancy, so the availability caches
     * must go — <b>by master</b>, never per {@code (master, date, service)} key: the booked window
     * bounds the slots offered for EVERY service the master performs that day, not only the booked
     * one (Anti-Bug §F2). A flipped bookability verdict can also add or remove a service from the
     * salon catalogue, so that is evicted too; an independent master owns no catalogue entry.
     *
     * <p>Runs after commit ({@link BookingAfterCommit}) so a parallel reader cannot repopulate the
     * cache with pre-write data. Identical to {@code BookingService#registerSlotEviction} and
     * {@code GuestBookingService#registerAfterCommit}'s eviction half.
     */
    private void registerSlotEviction(UUID masterId, UUID salonId) {
        BookingAfterCommit.run(() -> {
            slotCalculationService.evictMasterAvailabilityCaches(masterId);
            if (salonId != null) {
                salonCatalogCacheEvictor.evict(salonId);
            }
        });
    }
}
