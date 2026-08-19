package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.ActorSalonAssignmentMemo;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Derives the {@link StaffBookingScope} a staff booking is created under <b>from the authenticated
 * caller</b> — Phase 22.4's half of the contract 22.2's {@code assertMasterInScope} depends on.
 *
 * <h2>The one rule this class exists to enforce</h2>
 * {@code backend-security} cleared 22.2 conditional on this, and called it "the single line between
 * 98/100 and a HIGH":
 *
 * <blockquote>
 * {@code InSalon.salonId} MUST come from the CALLER's managed salon — <b>never</b> from the target
 * master.
 * </blockquote>
 *
 * <p>The reason is mechanical. {@code StaffBookingService#assertMasterInScope}'s {@code InSalon} arm
 * is {@code master.getSalon().getId().equals(inSalon.salonId())}. Populate {@code salonId} from
 * {@code master.getSalon()} and that arm compares the master's salon <em>against itself</em>: a
 * tautology that can never fail, whereupon 100% of the salon path's trust collapses onto the
 * {@code @PreAuthorize} annotation alone. Note the asymmetry with the {@code Self} arm, and that it
 * is deliberate: 22.2 cross-checks {@code Self} against the trusted {@code actorId} itself
 * (making a body-sourced {@code Self} inert no matter what this phase does), but {@code InSalon}'s
 * claim — "the caller manages this salon" — needs a membership lookup that can only live here.
 *
 * <p>So: <b>every value this class emits is read out of a query keyed on the actor</b>
 * ({@code salons.owner_id = :actorId} / {@code users.salon_id} for the actor's own row), and on the
 * two common shapes ({@code SALON_ADMIN}, and a {@code SALON_OWNER} with one salon) the target
 * master is <b>not consulted at all</b> — {@link MasterRepository} is not even touched. That is what
 * {@code StaffBookingScopeResolverTest} pins, and what goes red the moment someone "simplifies" this
 * to {@code master.getSalon()}.
 *
 * <h2>The multi-salon owner, and why it is still not a tautology</h2>
 * An owner may own several salons ({@code SalonRepository#findIdsByOwnerIdAndIsActiveTrue} exists
 * for exactly that reason), and the route carries no {@code {salonId}} to disambiguate with
 * (amendment A2). Only then is the master used, and only as a <em>selector among salons already
 * proven to be the caller's</em>: {@link MasterRepository#findSalonIdByIdAndSalonOwnerId} carries
 * the {@code s.owner.id = :ownerId} predicate INSIDE the query, so it cannot return a salon the
 * actor does not own, and the result is additionally required to be a member of the actor-keyed set.
 * A foreign master therefore yields no candidate and a 403 — the same outcome the single-salon path
 * reaches without any master read.
 *
 * <h2>Not a second copy of the authorization gate</h2>
 * {@code @authz.canBookForMaster} (the {@code @PreAuthorize} predicate) has already run and is what
 * makes an unknown master a 403 rather than an existence oracle. This class runs after it and
 * produces a <em>value</em> the SpEL gate cannot return, which is why the second lookup exists at
 * all (Anti-Bug §D forbids duplicating a verdict, not producing a datum). Its rejections are
 * therefore only reachable for a caller the gate already cleared — a defence-in-depth floor, not the
 * gate.
 *
 * <p>Every rejection is the same bare {@code 403 "Access denied"}: which of the reasons fired must
 * not be distinguishable from the outside.
 */
@Component
@RequiredArgsConstructor
public class StaffBookingScopeResolver {

    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final MasterRepository masterRepository;
    /**
     * Shared with {@code AuthorizationService} — see {@link #administeredSalonId} and
     * {@link ActorSalonAssignmentMemo}.
     */
    private final ActorSalonAssignmentMemo actorSalonAssignmentMemo;

    /**
     * Resolves the authority the caller may create this booking under.
     *
     * <p>Splits on the caller's ROLE, which is the only thing that decides <em>which kind</em> of
     * authority a caller can hold. (The complementary question — may this caller reach THIS master —
     * splits on the target's {@code masterType} and belongs to
     * {@code AuthorizationService#canBookForMaster}, per amendment A3.)
     *
     * @param auth     the authenticated caller; both the id and the role are read from it, never
     *                 from the request
     * @param masterId the target master, used only by the multi-salon owner branch and only as a
     *                 selector among the caller's own salons — see the class Javadoc
     * @throws ForbiddenException 403 — the caller holds no role that can create a staff booking, or
     *                            manages no salon the booking could belong to
     */
    public StaffBookingScope resolve(Authentication auth, UUID masterId) {
        UUID actorId = AuthenticationUtils.userId(auth);
        Role actorRole = AuthenticationUtils.role(auth);
        return switch (actorRole) {
            // An independent master has no salon, so there is nothing to scope by — the identity
            // itself is the scope. actorId, never a body field and never the target master's user:
            // 22.2 then compares it to master.getUser().getId(), which is what confines the caller
            // to their own calendar.
            case INDEPENDENT_MASTER -> new StaffBookingScope.Self(actorId);
            case SALON_OWNER -> new StaffBookingScope.InSalon(ownedSalonId(actorId, masterId));
            case SALON_ADMIN -> new StaffBookingScope.InSalon(administeredSalonId(actorId));
            // A SALON_MASTER's calendar is read-only and a CLIENT has no provider surface at all.
            // Unreachable behind the controller's hasAnyRole gate; kept because the switch is
            // exhaustive over Role and a silent default would be the wrong failure mode.
            case SALON_MASTER, CLIENT -> throw new ForbiddenException("Access denied");
        };
    }

    /**
     * The salon a {@code SALON_OWNER} caller books under.
     *
     * <p>Sourced from {@link SalonRepository#findIdsByOwnerIdAndIsActiveTrue} — an ownership lookup
     * keyed on the actor and nothing else. For the single-salon owner (the overwhelmingly common
     * shape) this returns without reading the master at all, which is the property the falsification
     * test asserts.
     *
     * <p><b>Why this is not collapsed into the gate's query</b> (perf LOW, 2026-08-18). This query is
     * a strict SUPERSET of the one {@code AuthorizationService#canBookForMaster} already ran for a
     * {@code SALON_OWNER}: that gate asks {@code salonRepository.existsByIdAndOwnerId(salonId,
     * ownerId)}, and {@code salonId ∈ ownedActiveIds} implies exactly that — so in principle the gate
     * could be answered from this result and one round-trip dropped.
     *
     * <p><b>That SUPERSET relation has a precondition, and it is not local to either query.</b>
     * {@code findIdsByOwnerIdAndIsActiveTrue} carries {@code s.isActive = true}
     * ({@code SalonRepository:70}); {@code existsByIdAndOwnerId} carries no activity predicate at
     * all. Taken on their own the containment runs the OTHER way — an owner's DEACTIVATED salon
     * satisfies {@code existsByIdAndOwnerId} but is absent from {@code ownedActiveIds}, making this
     * set a strict SUBSET and any substitution a silent allow→deny flip on that owner's closed
     * salons. The superset direction holds only because {@code MasterBookability#isBookable}
     * ({@code MasterBookability:106}) has ALREADY rejected every master whose salon is inactive,
     * upstream of the gate's {@code .map(...)} — so by the time {@code existsByIdAndOwnerId} is
     * asked, {@code salon.isActive()} is known true and the two sets coincide on the reachable
     * inputs. Anyone reusing this reasoning must carry that precondition with it.
     *
     * <p>It is deliberately NOT collapsed,
     * because doing so requires running this resolver BEFORE the {@code @PreAuthorize} gate and
     * deriving the verdict from it. That inverts the SpEL-first ordering the phase depends on: method
     * security must run before the handler so an unknown/inactive/closed-salon master is a uniform 403
     * and 22.2's indistinct 404 is unreachable over HTTP. The duplicate is the cost of not having an
     * existence oracle, and it is one indexed lookup.
     *
     * <p>(The {@code SALON_ADMIN} branch's duplicate — see {@link #administeredSalonId} — was NOT
     * accepted: that one is memoised, under the invariant {@link ActorSalonAssignmentMemo} states.
     * The asymmetry is deliberate; the difference is ordering, not cost.)
     */
    private UUID ownedSalonId(UUID ownerId, UUID masterId) {
        List<UUID> ownedSalonIds = salonRepository.findIdsByOwnerIdAndIsActiveTrue(ownerId);
        if (ownedSalonIds.isEmpty()) {
            throw new ForbiddenException("Access denied");
        }
        if (ownedSalonIds.size() == 1) {
            return ownedSalonIds.get(0);
        }
        // Multi-salon owner only. The query is itself owner-scoped, and the result is re-checked
        // against the actor-keyed set — see the class Javadoc.
        return masterRepository.findSalonIdByIdAndSalonOwnerId(masterId, ownerId)
                .filter(ownedSalonIds::contains)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
    }

    /**
     * The salon a {@code SALON_ADMIN} caller books under: their own assignment, read off
     * {@code users.salon_id} — the same column {@code AuthorizationService#hasManagementAccess}
     * consults for an admin. Unambiguous by construction (an admin is assigned to exactly one
     * salon), so this branch never reads the target master.
     *
     * <p><b>Shares one read with the gate</b> (perf MEDIUM, 2026-08-18). The
     * {@code @authz.canBookForMaster} SpEL predicate has already issued the byte-identical
     * {@code findSalonIdById(actorId)} for this same request, so both sites go through
     * {@link ActorSalonAssignmentMemo} and the projection runs once.
     *
     * <p><b>The safety argument is NOT restated here — read
     * {@link ActorSalonAssignmentMemo}'s "Why memoising THIS value is safe" section.</b> An earlier
     * revision of this comment justified the memo by calling the value "an immutable request-lifetime
     * scalar". <b>That invariant is false</b>, and it is exactly the sentence a future reader copies
     * to a call site where it does not hold — which is how the claim got here in the first place.
     * {@code SalonService#rotateAdmin} has no self-guard (its sibling {@code removeAdmin} does), and
     * self-rotation is a shipped flow, so a request CAN change its own caller's
     * {@code users.salon_id}. The memo rests on the strictly narrower invariant <em>"no
     * authorization read follows a write that could touch the caller's own row"</em>, and this
     * branch qualifies only because the staff-booking endpoint never writes that column. Any new
     * consumer must re-check that invariant against its own request, not against the word
     * "immutable".
     *
     * <p>Contrast the target {@code Master}, which is read twice ON PURPOSE and must not be
     * deduplicated the same way (see {@code StaffBookingService#createStaffBooking}).
     */
    private UUID administeredSalonId(UUID adminId) {
        return actorSalonAssignmentMemo
                .salonIdOf(adminId, () -> userRepository.findSalonIdById(adminId))
                .orElseThrow(() -> new ForbiddenException("Access denied"));
    }
}
