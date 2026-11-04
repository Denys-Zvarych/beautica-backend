package com.beautica.booking.service;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.booking.domain.GuestConfirmationSmsText;
import com.beautica.booking.domain.GuestSmsProviderText;
import com.beautica.booking.domain.MasterBookability;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.StaffBookingCommand;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.booking.dto.StaffClientRef;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.Placeholders;
import com.beautica.common.util.UkrainianPhoneNormalizer;
import com.beautica.config.BookingSmsProperties;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 *       {@link BookingSlotAvailabilityGuard#assertStaffVisitStartsOnAvailableSlot}, the WHOLE-CHAIN
 *       (Phase 22.10/22.12) counterpart of the single-service guard, which runs the same
 *       {@code SlotCalculationService} effective-day oracle every other create path is proved
 *       against;</li>
 *   <li><b>double-book</b> — {@link BookingSlotLockGuard}, shared verbatim with
 *       {@code GuestBookingService};</li>
 *   <li><b>lead time / horizon</b> — {@code BookingStartsAtValidator#validateStaff}.</li>
 * </ul>
 *
 * <h2>Multi-service visits (Phase 22.11/22.12)</h2>
 * {@link StaffBookingCommand} carries an ORDERED {@code masterServiceIds} list, so a staff-created
 * visit is exactly the shape {@code AppointmentService#doCreateAppointment} produces: ONE
 * {@code appointments} header (born via {@link Appointment#staffAppointment}) plus N chained
 * {@code bookings} rows, every one linked by {@code appointment_id}. This is possible because V139
 * widened {@code chk_appointment_source} to admit {@code 'STAFF'} alongside {@code 'APP','LINK'} —
 * before that migration this class refused any multi-service create outright (a since-deleted
 * single-item collapse, Phase 22.11) rather than risk failing at the appointment insert.
 *
 * <p><b>N = 1 still creates a header — no size-based short-circuit</b> (locked decision, mirrors
 * {@code doCreateAppointment}'s own lack of one). One code path means a bug can only exist in one
 * place, and the two shapes — a legacy pre-track single-service row with {@code appointment_id}
 * NULL, and every visit created after this phase, header-linked — must BOTH keep working forever;
 * there is no backfill of the legacy shape (Phase 22.12 D2). {@code StaffBookingReadPathIT} proves
 * neither shape regresses the walk-in "never reviewable" rule.
 *
 * <p>The per-BOOKING rule is unchanged by any of this: cancel, reschedule, decline and feedback each
 * still touch exactly ONE {@code bookings} row, never a sibling, never the whole visit — the header
 * is a grouping label, not a second transition surface. See CLAUDE.md's locked
 * {@code project_completion_is_per_service} decision.
 *
 * <h2>Locked domain rules honoured here</h2>
 * Every booking in the visit is born {@code CONFIRMED} via {@code Booking#staffBooking} (track
 * 24.x: every booking is). There is no status-writing path of any kind — {@code AWAITING_CLOSURE}
 * stays read-time derived. {@code priceMaxAtBooking} is a per-item snapshot taken here and never
 * re-derived. "Now" comes only from the injected {@link Clock}.
 *
 * <h2>The walk-in confirmation SMS (Phase 22.7)</h2>
 * Added here, after commit, and <b>with no reference anywhere to the feature flag</b>. The gate
 * {@code app.booking.sms.enabled} is applied once, in {@code SmsConfig}, by choosing which
 * {@code SmsService} bean exists at all — so {@link BookingSmsDispatcher}, which this class hands the
 * message to, holds the real collaborator in production and a log-only {@code NoOpSmsService}
 * everywhere else and cannot tell the difference. An {@code if (properties.isEnabled())} here would
 * defeat that design and re-open the "the next call site forgets the gate" failure it exists to
 * close. See {@link #registerWalkInConfirmationSms}. Since the 2026-11-04 user decision the dispatcher delivers it
 * through {@code GuestMessageRouter}: Telegram when the phone is linked, SMS otherwise and on any Telegram failure.
 *
 * <p><b>Phase 412 — the cancel link.</b> The visit header carries a one-time guest cancel token
 * (reverses phases 168 / 171 / 254 D), and the SMS renders {@code {cancelUrl}} =
 * {@link GuestCancelUrlBuilder#build}: the walk-in cancels on the same public page and through the same
 * {@code POST /api/v1/book/cancel/{token}} visit path as a LINK guest ({@code GuestVisitCancellationService}).
 *
 * <p>Because the send now costs real money, the create is also SMS-spend throttled on two
 * independent axes: per staff actor by {@code staffBookingSmsBuckets} (the filter), and per
 * RECIPIENT by {@link #assertWalkInSmsBudgetForPhone} here. Neither subsumes the other — see that
 * method and {@link #MAX_WALK_INS_PER_PHONE_PER_WINDOW}.
 *
 * <h2>Not here</h2>
 * No OUTBOX notification (push/email/SMS beyond the walk-in confirmation SMS above). No outbox
 * enqueue is specified for a staff booking by 22.4, and inventing one would risk double-notifying a
 * track that has not decided its provider-side copy. Flagged rather than guessed.
 *
 * <p><b>Phase 333 exception — the in-app feed.</b> {@link #createStaffBooking} DOES write one
 * {@code BOOKING_CREATED} in-app feed row, for the performing master only (matrix row 1w,
 * user-decided 2026-09-28) — see {@link #registerInAppFeedNotification}. This is a SEPARATE seam
 * from the outbox untouched above (see {@code InAppNotificationService}'s class javadoc); it does not
 * revisit the "no outbox" decision this section otherwise still describes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffBookingService {


    private final MasterRepository masterRepository;
    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final VisitPlanner visitPlanner;
    private final BookingSmsDispatcher bookingSmsDispatcher;
    private final BookingSmsProperties smsProperties;
    private final Clock clock;
    /**
     * REUSE-FIRST (Phase 22.14): the visit-detail enrichment (master summary, Kyiv-zoned window,
     * price/duration totals, discovery labels) is owned by {@link AppointmentService#enrich}, which
     * this class calls rather than re-implementing. {@code enrich} is package-private specifically so
     * a second writer in this package can reuse it verbatim instead of duplicating the
     * {@code DiscoveryLocationResolver} lookup — see that method's Javadoc.
     */
    private final AppointmentService appointmentService;
    // Phase 333 — see InAppNotificationService's class javadoc for why this is a separate seam from
    // the (deliberately untouched, per this class's own "Not here" section) outbox.
    private final InAppNotificationService inAppNotificationService;
    /** Phase 412 D3 — the walk-in's {@code /book/cancel/{token}} link, the same page a LINK guest uses. */
    private final GuestCancelUrlBuilder guestCancelUrlBuilder;
    /** Phase 435 — seals the walk-in's visit token (V230) so later guest SMS can link the guest's own page. */
    private final GuestCancelTokenCipher guestCancelTokenCipher;

    /**
     * Per-recipient ceiling on walk-in confirmation SMS, mirroring {@code PhoneOtpService}'s
     * per-phone window — the second half of the SMS-spend defence whose first half is
     * {@code staffBookingSmsBuckets} in {@code RateLimitConfig} (SEC MEDIUM, 2026-08-18).
     *
     * <p>The bucket caps how fast ONE staff account can spend; this caps how much any ONE Ukrainian
     * number can be made to receive, no matter how many staff accounts, masters or salons are used
     * to do it. Without it a self-registered {@code INDEPENDENT_MASTER} with a 24/7 schedule could
     * loop create/cancel and push Beautica-branded copy — with an attacker-chosen {@code
     * serviceName} inside it — at a number that never consented.
     *
     * <p>Counted in the DATABASE rather than in a Caffeine bucket on purpose: the count must survive
     * a restart and hold across instances, and {@code bookings} already records exactly the fact
     * being limited. 5 per hour clears every legitimate shape (a client rebooked after a cancel, a
     * family sharing one number, a corrected time) by a wide margin while removing the loop.
     */
    static final long MAX_WALK_INS_PER_PHONE_PER_WINDOW = 5;
    static final Duration WALK_IN_PHONE_WINDOW = Duration.ofHours(1);

    /**
     * Creates one {@code CONFIRMED}, {@code STAFF}-sourced VISIT for an account-less walk-in
     * client: one {@code appointments} header plus N chained {@code bookings} rows, one per
     * ordered {@code masterServiceIds} entry (Phase 22.12). N = 1 still produces a header — see the
     * class Javadoc's "Multi-service visits" section; there is no size-based short-circuit.
     *
     * <p><b>Order is load-bearing</b>, and mirrors {@code BookingService#doCreateBooking}'s /
     * {@code AppointmentService#doCreateAppointment}'s: cheap non-DB guards, then the master, then
     * the chain resolution (both 404-shaped), then lead time, then schedule fit over the WHOLE
     * chain, and only then the per-master advisory lock — so a request that is off-schedule, or for
     * a service the master does not perform, never contends for the lock every other request
     * against that master is queued on.
     *
     * @param cmd     the fully-resolved command — every field of which may legitimately originate in
     *                the request body
     * @param actorId the authenticated staff user keying the booking in, persisted to
     *                {@code bookings.created_by_user_id} AND {@code appointments.created_by_user_id}
     *                (V137/V139) as the ONLY audit trail a staff visit carries. <b>A separate
     *                parameter on purpose</b> (security MEDIUM, 2026-08-18): while it sat on
     *                {@link StaffBookingCommand} beside the client-supplied values, 22.4's natural
     *                {@code request.toCommand()} mapping was one field away from letting a caller
     *                attribute a walk-in to a different staff user. The controller MUST source it
     *                from the security context.
     * @throws NotFoundException   404 — unknown/inactive master (or a master whose salon is closed),
     *                             or any {@code masterServiceIds} entry that is unknown, foreign or
     *                             inactive. Both are deliberately indistinguishable from each
     *                             other's message.
     * @throws ForbiddenException  403 — the master is outside the command's {@link StaffBookingScope}
     *                             (wrong salon, a {@code Self} scope naming a user other than
     *                             {@code actorId}, or not the self-booking master), or
     *                             {@code actorId} is absent
     * @throws BusinessException   400 — past {@code startsAt}, beyond the 180-day horizon, an empty
     *                             or over-cap {@code masterServiceIds} list, a chain whose total
     *                             duration exceeds
     *                             {@code SlotCalculationService#MAX_TOTAL_DURATION_MINUTES}, missing
     *                             walk-in name/surname/phone, a control character in either name, or
     *                             an unparseable phone;
     *                             409 — an off-schedule start, or a span that collides with an
     *                             existing {@code CONFIRMED} booking;
     *                             429 — this walk-in phone has already received
     *                             {@link #MAX_WALK_INS_PER_PHONE_PER_WINDOW} confirmations inside
     *                             {@link #WALK_IN_PHONE_WINDOW};
     *                             501 — an {@link StaffClientRef.ExistingClient} subject (Phase 22.3)
     */
    @Transactional
    public AppointmentDetailResponse createStaffBooking(StaffBookingCommand cmd, UUID actorId) {
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
        // priceMax snapshot per item, chained back-to-back — the same shared planner the APP and
        // LINK visit paths use, so a staff visit's frozen columns cannot drift from theirs. N = 1 is
        // NOT special-cased: a one-service list still returns a one-element chain here, exactly as
        // AppointmentService#doCreateAppointment never special-cases N = 1 either.
        List<VisitPlanner.PlannedItem> items =
                visitPlanner.planChainedItems(master, cmd.masterServiceIds(), cmd.startsAt());
        OffsetDateTime firstStart = items.get(0).startsAt();
        OffsetDateTime lastEnd = items.get(items.size() - 1).endsAt();

        // The new guarantee Phase 22.10 added: the FIRST start must be a real slot in the master's
        // resolved schedule for the WHOLE chained block — not a day-off, not a custom-hours gap, not
        // an off-grid minute, and not a chain whose tail runs past the working window even though
        // the first service alone would fit. `items`' assignments are handed through (in the SAME
        // order as masterServiceIds) so the gate does not re-issue the finder the planner just ran.
        // Whole-chain, never N per-item checks — see BookingSlotAvailabilityGuard's Javadoc for why
        // a per-item check would wrongly accept an overrunning chain.
        BookingSlotAvailabilityGuard.assertStaffVisitStartsOnAvailableSlot(
                slotCalculationService, master.getId(), cmd.masterServiceIds(),
                VisitPlanner.assignmentsOf(items), cmd.startsAt());

        StaffClientRef.Guest guest = requireWalkIn(cmd.client());
        // MUST precede the insert: chk_bookings_guest_phone_format (V89) enforces ^\+[0-9]{6,18}$ in
        // the DATABASE, so a staff-typed "050 123 45 67" is rejected as a 500-shaped constraint
        // violation, not stored. See UkrainianPhoneNormalizer.
        String guestPhone = UkrainianPhoneNormalizer.toE164(guest.phone());
        // AFTER normalisation, so "050 123 45 67" and "+380501234567" cannot be alternated to buy a
        // second budget; BEFORE the advisory lock, so a throttled request never contends for the
        // lock every other request against this master queues on.
        //
        // THIS CALL SITE MUST NOT MOVE BELOW lockMasterAndAssertFree (security MEDIUM, 2026-08-22).
        // The method now takes a per-PHONE advisory lock (salt 3) of its own, so the acquisition
        // order on this path is always phone → master (salt 0), mirroring the client(salt 1) →
        // master(salt 0) order BookingService uses. No path anywhere takes the master lock first, so
        // there is one global ordering and no acquisition cycle; swapping these two statements would
        // create one. That phone lock is this transaction's FIRST advisory lock and therefore fuses
        // the 3s lock_timeout the whole transaction then inherits.
        assertWalkInSmsBudgetForPhone(guestPhone);

        // Read-only enrichment work HOISTED ABOVE the per-master lock (perf LOW, 2026-08-22): the
        // discovery labels are two SELECTs of lock-irrelevant work, so they run before the window every
        // other request against this master queues on. Phase 435 dropped the service name from the
        // walk-in SMS, so the platform service-name lookup that used to sit here is gone.
        // Same locality rule AppointmentService#enrich applies (booked salon wins, else the master's
        // own user row), resolved through that class's own helper rather than a second copy here —
        // `master.getSalon()` IS the instance handed into every Booking.staffBooking(...) below, and
        // cityId/districtId are plain UUID columns on both entities, so this issues no extra load
        // beyond resolveLabels' own two taxonomy SELECTs.
        DiscoveryLabels labels =
                appointmentService.resolveVisitLabels(master.getSalon(), master.getUser());

        // ONE span check over [firstStart, lastEnd), not N per-item checks: the chained items are
        // contiguous by construction AT CREATE TIME (VisitPlanner#assertContiguous), so the union of
        // their intervals is exactly this span — identical reasoning and identical span to
        // AppointmentService#doCreateAppointment's overlap check. Holds only for THIS transaction; a
        // later per-item reschedule legally separates items with gaps, so this argument must never
        // be reused to justify a single span check on a read or reschedule path.
        BookingSlotLockGuard.lockMasterAndAssertFree(
                bookingRepository, master.getId(), firstStart, lastEnd);

        // Phase 412 D1 — the walk-in gets a one-time cancel token exactly like a LINK visit: ONE per visit, on the
        // header (only its hash is persisted); the child rows stay tokenless. A walk-in always has a phone
        // (requireStaffWalkInIdentity), so the token is always issued; with the SMS gate off it is never sent and
        // stays a harmless hash.
        GuestCancelToken cancelToken = GuestCancelToken.generate();
        Appointment appointment = Appointment.staffAppointment(
                master.getSalon(), guest.name(), guest.surname(), guestPhone, cancelToken, actorId);

        List<Booking> bookings = new ArrayList<>(items.size());
        for (VisitPlanner.PlannedItem item : items) {
            Booking booking = Booking.staffBooking(
                    master, item.masterService(), master.getSalon(),
                    item.startsAt(), item.endsAt(), item.price(), item.priceMax(),
                    item.duration(), item.buffer(),
                    guest.name(), guest.surname(), guestPhone,
                    actorId);
            // Set post-construction (D4, Phase 22.12): additive, not a new factory parameter — see
            // Booking#appointment's Javadoc. The window in which the entity is un-linked is purely
            // local and is never flushed in that state.
            booking.setAppointment(appointment);
            bookings.add(booking);
        }

        // Header saved first so the FK bookings.appointment_id resolves; saveOrConflict's list
        // overload does saveAll + one flush inside the SAME try/catch the single-row path uses, so a
        // no_overlapping_bookings violation on ANY item maps to the identical 409 — no partial visit
        // is ever persisted (atomic, one transaction).
        appointmentRepository.save(appointment);
        // Phase 435 — the header's reversible token copy (V230). Audit-fix: sealed once persist assigned the header id,
        // which is the AES-GCM associated data ("appointments:<id>"); written with the header at the flush below.
        appointment.attachSealedCancelToken(guestCancelTokenCipher.seal(
                cancelToken, GuestCancelTokenCipher.Row.appointment(appointment.getId())));
        List<Booking> saved = BookingSlotLockGuard.saveOrConflict(bookingRepository, bookings);

        // ONCE per visit, not once per item: all N rows share one master and one salon, so N calls
        // would evict the same two keys N times for nothing.
        registerSlotEviction(master.getId(), salonIdOf(saved.get(0)));
        registerInAppFeedNotification(appointment, saved.get(0), actorId);
        // Rendered NOW, inside the transaction, while `master` is still managed — the callback runs
        // after the persistence context closes, so touching a lazy association from there would be a
        // LazyInitializationException. EXACTLY ONE SMS for the whole visit (Phase 22.13); since phase
        // 435 it names no service at all, only the visit start and the master.
        registerWalkInConfirmationSms(
                guestPhone,
                buildWalkInConfirmationSms(saved.get(0), firstStart, lastEnd,
                        guestCancelUrlBuilder.build(cancelToken)));

        // REUSE-FIRST (Phase 22.14): the visit-detail enrichment is AppointmentService#enrich's job,
        // not a second mapper here. `saved` already carries everything `enrich` dereferences —
        // `master`/`master.getUser()` and every item's `masterService.serviceDefinition` were loaded
        // (with FETCH joins) by VisitPlanner/findByIdWithUserAndSalon earlier in THIS transaction and
        // are still managed, and `booking.getSalon()` is the SAME `master.getSalon()` instance handed
        // into every `Booking.staffBooking(...)` call above — so no extra SELECT is issued and no
        // `findByAppointmentIdWithGraph` re-fetch is needed. `saved` is already ordered ascending by
        // startsAt (it mirrors `items`' chained order), matching `enrich`'s documented precondition.
        //
        // The pre-resolved-labels overload (perf LOW, 2026-08-22) so `enrich`'s two taxonomy SELECTs
        // do not run inside the per-master lock window — the two-argument form still resolves them
        // itself and every other caller is unchanged.
        return appointmentService.enrich(appointment, saved, labels);
    }

    /**
     * Enforces {@link #MAX_WALK_INS_PER_PHONE_PER_WINDOW} walk-in VISITS per recipient per
     * {@link #WALK_IN_PHONE_WINDOW} — see those fields for the threat model and the sizing.
     *
     * <p>Counts VISITS, not rows (Phase 22.13): {@code registerWalkInConfirmationSms} fires once per
     * visit whatever its service count, so the budget it feeds must be denominated the same way —
     * see {@link BookingRepository#countStaffWalkInVisitsForPhoneSince} for the {@code coalesce}
     * reasoning.
     *
     * <p>A {@code 429}, matching {@code PhoneOtpService}'s per-phone verdict, and with a message
     * that names no number: the response must not confirm to a prober that a given phone has been
     * booked recently.
     *
     * <h4>Why the lock is the FIRST statement (security MEDIUM, 2026-08-22)</h4>
     * Count-then-insert is a classic TOCTOU: nothing in {@code bookings} locks the phone, so at
     * READ COMMITTED C concurrent creates naming the same number all read the identical pre-burst
     * count, all pass this check, and all insert — turning a 5/hour ceiling into ~{@code 5 + C}
     * messages at a number that never consented. Not reachable today (no committed profile sets
     * {@code app.booking.sms.enabled=true}) and fully live the moment that flag flips.
     * {@link BookingRepository#acquireWalkInPhoneLock} serialises same-phone creates so the second
     * caller counts the row the first committed. It is taken before the count, never after — a lock
     * acquired after the read would protect nothing.
     */
    private void assertWalkInSmsBudgetForPhone(String guestPhone) {
        // Serialises same-phone creates. The phone is ALREADY E.164-normalised at every call site
        // (UkrainianPhoneNormalizer.toE164 runs first), so "050 123 45 67" and "+380501234567" key
        // the SAME lock — a lock on the raw string would be inert against exactly the alternation
        // the normalisation exists to defeat.
        bookingRepository.acquireWalkInPhoneLock(guestPhone);
        // Phase 431: the walk-in verdict counts walk-in VISITS only — exactly the pre-431 budget and
        // query plan. Guest reschedule SMS (possibly another provider's) must never push a walk-in
        // create to 429 (user rule 2026-10-30); the reschedule side alone reads the combined count
        // (GuestRescheduleSmsBudget, BookingRepository#countPhoneSmsBudgetUsedSince).
        long recent = bookingRepository.countStaffWalkInVisitsForPhoneSince(
                guestPhone, clock.instant().minus(WALK_IN_PHONE_WINDOW));
        if (recent >= MAX_WALK_INS_PER_PHONE_PER_WINDOW) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many walk-in bookings for this phone — please try again later");
        }
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

    /**
     * Phase 333, matrix row 1w — a walk-in notifies ONLY the performing salon master, one item per
     * visit, and never when the master is the one who keyed the booking in ({@code actorId} is
     * excluded by {@link InAppNotificationService} itself). Owner/admins who did not act get
     * nothing (architect decision — see the class javadoc's "Phase 333 exception" paragraph): the
     * shared board already shows a colleague's walk-in, unlike the master's own read-only calendar,
     * which they may not be looking at.
     *
     * <p>Routed through {@link InAppNotificationService#notifyVisitEvent} — the SAME entry point
     * every other visit-level create uses ({@code AppointmentService#doCreateAppointment}, {@code
     * GuestBookingService#createGuestVisit}) — which itself restricts a {@code STAFF}-source
     * appointment to the master-only recipient set; this method does not duplicate that branch.
     *
     * <p>{@code firstItem} is {@code saved.get(0)} — already the fully managed, freshly-persisted
     * first chained booking (carrying the real {@code master}/{@code salon} instances this method
     * built, not proxies) — never reloaded (audit-fix cycle 1, finding 1).
     */
    private void registerInAppFeedNotification(Appointment appointment, Booking firstItem, UUID actorId) {
        inAppNotificationService.notifyVisitEvent(
                InAppNotificationType.BOOKING_CREATED, appointment.getId(), firstItem, actorId);
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
                salonCatalogCacheEvictor.evictCatalogOnly(salonId);
            }
        });
    }

    /**
     * Sends the walk-in client their confirmation, after commit (Phase 22.7).
     *
     * <p><b>Its own registration, not folded into {@link #registerSlotEviction}.</b> The two
     * side-effects are independent and neither may be able to suppress the other: a provider call
     * sharing a {@link Runnable} with cache eviction puts a network round trip in front of a
     * correctness-critical evict, and an eviction failure would silently skip the client's only
     * notification. Separate synchronizations, separate blast radii.
     *
     * <p><b>After commit</b> because an SMS is not retractable: sending inside the transaction
     * would tell a client about a booking a later rollback erases.
     *
     * <p><b>Not on the request thread</b> (backend-perf MEDIUM, 2026-08-18). An {@code afterCommit}
     * callback runs on the thread that committed — here the servlet thread, before the 201 is
     * written and while it still holds its pooled Hikari connection — so an inline
     * {@code smsService.send} put the whole Turbosms round trip (up to the 5 s read cap) into this
     * endpoint's p99. {@link BookingSmsDispatcher} takes the hand-off; the catch, the log discipline
     * and the "a provider outage must never fail a committed booking" guarantee all live there now,
     * one caller removed from any transaction. See that class for why the eviction-before-send
     * ordering and the never-fail-the-booking invariant are preserved.
     *
     * <p>Whether anything actually leaves the building is not this method's business — see the
     * class Javadoc.
     */
    private void registerWalkInConfirmationSms(String guestPhone, String smsText) {
        BookingAfterCommit.run(() -> bookingSmsDispatcher.dispatch(
                BookingSmsDispatcher.Kind.WALK_IN_CONFIRMATION, guestPhone, smsText));
    }

    /**
     * Renders the ONE walk-in confirmation SMS for the whole visit, in ONE pass over the template
     * (Phase 22.13).
     *
     * <p>{@link Placeholders#format} rather than chained {@link String#replace} for the reason
     * documented on {@code GuestBookingService#buildConfirmationSms}: sequential replacement
     * re-scans values it has already substituted. A single pass copies substituted values out
     * verbatim, so data can never become markup.
     *
     * <p>{@code date}/{@code time} render {@code startsAt} — the Kyiv civil values of the VISIT's own
     * start, {@code firstStart}, never a later item's. {@code endsAt} is still accepted and validated
     * here — the caller MUST pass {@code lastEnd} (the visit's own {@code [firstStart, lastEnd)} span),
     * never {@code items.get(0).endsAt()} — so a future template that surfaces the visit's finish time
     * cannot silently inherit a wrong value from this method's call sites.
     *
     * <p><b>Phase 435 — no service name.</b> The copy (user decision 2026-11-04) is date/time, master
     * and cancel link only, so no {@code serviceName} is substituted: the platform-name lookup and the
     * «та ще N послуг(и)» phrase that used to feed it are gone. This also closes the provider-text
     * channel the platform name existed to narrow (security LOW, 2026-08-19) — no service text of any
     * kind reaches a phone that never opted in.
     *
     * <p>{@code masterName} is narrowed by {@code @NoDigits} plus the no-control-character pattern on every
     * registration DTO, which still admits dots ({@code t.me/promo}). It is capped to
     * {@link GuestSmsProviderText#MAX_PERSON_NAME} chars and, since audit-fix cycle 2, DROPPED when it looks like a
     * URL: the SMS then has no «Майстер …» line (salon variant: «Салон {salonName}.»).
     *
     * <p>Phase 435 — a SALON walk-in uses the salon variant, which also names the salon (the salon the visit is
     * booked at, {@code master.getSalon()}). Audit-fix: the salon name goes through {@link GuestSmsProviderText} —
     * one line, ≤ 40 chars, and DROPPED (independent-master copy instead) when it looks like a URL/domain.
     */
    private String buildWalkInConfirmationSms(
            Booking first, OffsetDateTime startsAt, OffsetDateTime endsAt, String cancelUrl) {
        if (endsAt.isBefore(startsAt)) {
            // Defensive invariant, never client-triggered: VisitPlanner#assertContiguous already
            // guarantees lastEnd >= firstStart for any chain that reaches this method.
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Visit end precedes its own start");
        }
        // Phase 435 follow-up: the shared renderer (also the LINK SMS and the Telegram details). `first` is the visit's
        // first item: it starts at `startsAt` and carries the same `master` / `master.getSalon()` instances.
        BookingSmsProperties.Sms sms = smsProperties.getSms();
        return GuestConfirmationSmsText.render(
                sms.getWalkInConfirmationSalon(), sms.getWalkInConfirmation(), first, cancelUrl);
    }
}
