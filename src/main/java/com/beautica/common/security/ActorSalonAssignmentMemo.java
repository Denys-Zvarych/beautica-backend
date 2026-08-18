package com.beautica.common.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A one-entry, request-lifetime memo of "which salon is this actor assigned to?"
 * ({@code users.salon_id}) — the value both halves of a {@code SALON_ADMIN} staff-booking request
 * need, and which was being read twice per request (perf MEDIUM, 2026-08-18).
 *
 * <h2>The duplication it removes</h2>
 * On {@code POST /api/v1/masters/&#123;masterId&#125;/bookings} with a {@code SALON_ADMIN} caller,
 * {@code userRepository.findSalonIdById(actorId)} was issued twice, byte-identically:
 * <ol>
 *   <li>{@code AuthorizationService#hasManagementAccess}'s admin arm, reached from the
 *       {@code @authz.canBookForMaster} SpEL gate, to answer "does the caller manage the target's
 *       salon?"; then</li>
 *   <li>{@code StaffBookingScopeResolver#administeredSalonId}, in the handler, to answer "which
 *       salon does the booking belong to?"</li>
 * </ol>
 * Same actor, same column, same request — one round-trip is enough.
 *
 * <h2>Why memoising THIS value is safe, when memoising the target {@code Master} is not</h2>
 * The two duplicate reads on this endpoint are not the same kind of duplication. The {@code Master}
 * is read twice on purpose and MUST stay that way (see {@code AuthorizationService#canBookForMaster}
 * and {@code StaffBookingService#createStaffBooking}): it is a managed entity that detaches between
 * the two reads, and the second read is a deliberate TOCTOU narrowing inside the inserting
 * transaction. This value has neither property:
 * <ul>
 *   <li>It is a {@code UUID} projection, never a managed entity, so there is no persistence-context
 *       detachment or lazy-initialisation hazard to reason about.</li>
 *   <li><b>No authorization decision is taken after the value could change within a request.</b>
 *       This is the invariant the memo actually rests on, and it is deliberately narrower than
 *       "the value cannot change" — which is FALSE. {@code SalonService#rotateAdmin} has no
 *       self-guard (unlike its sibling {@code removeAdmin}, which bans {@code actorId.equals(userId)}),
 *       and self-rotation is a SHIPPED, intentional flow: a {@code SALON_ADMIN} may move their own
 *       assignment to a sibling salon of the same owner — pinned by
 *       {@code SalonAdminRotationIntegrationTest#should_return200_when_adminRotatesOwnSalonToAnotherSalonOfSameOwner}.
 *       So a request CAN mutate its own caller's {@code users.salon_id}. It is harmless here only
 *       because that mutation is the terminal act of the rotation request: no authorization read
 *       follows it in the same request, and the staff-booking endpoint (this memo's only consumer)
 *       never writes {@code users.salon_id} at all. <b>Any future call site that reads this memo
 *       AFTER a write that could touch the caller's own row must not use it</b> — it would observe
 *       the pre-mutation value.</li>
 *   <li>It gates nothing that is written; it only selects which salon the write is attributed to,
 *       and the write itself is re-validated against committed state under the booking lock.</li>
 * </ul>
 *
 * <h2>Deliberately dumb</h2>
 * One key, one value, no eviction, no cross-request state, no {@code Map}. The state lives in the
 * current request's attributes, so "eviction" is the request ending — there is no TTL to tune and no
 * staleness window to reason about, which is exactly why this is NOT a {@code @Cacheable}
 * (Anti-Bug §F would then demand an eviction path on every write that could change the value —
 * including the self-rotation write noted above, whose effect a cross-request cache WOULD outlive;
 * a request-scoped memo cannot, because the request that performed the write is over).
 *
 * <h2>Why request ATTRIBUTES and not a {@code @RequestScope} bean</h2>
 * Both give the same per-request lifetime — Spring's own {@code RequestScope} is implemented over
 * these very attributes — but a {@code @RequestScope} bean is reached through a CGLIB proxy that
 * throws {@code IllegalStateException: No thread-bound request found} the moment it is dereferenced
 * off a servlet request thread. {@code AuthorizationService#hasManagementAccess} is <b>not</b>
 * exclusively request-bound: it is also reached by direct service calls (for example
 * {@code BookingService#completeBooking} invoked outside MVC, as several integration tests do with a
 * primed {@code SecurityContext} and no bound request). A pure optimisation must never be able to
 * turn a working call into a 500, so this degrades to a plain uncached read when no request is bound
 * instead of failing.
 *
 * <p>The caller passes the loader rather than this class owning a {@code UserRepository}: the memo
 * stays a pure value holder with zero data-access dependencies, and the repository call stays visible
 * at the site that needs it — so neither call site's tests lose their
 * {@code verify(userRepository)} assertions to an opaque indirection.
 *
 * <p><b>Not a security boundary.</b> The entry is keyed on the actor id, so a value memoised for
 * actor A can never be handed to actor B (a request has exactly one authenticated actor anyway); the
 * key check exists so the memo degrades to a plain read rather than lying if that ever stops being
 * true.
 */
@Component
public class ActorSalonAssignmentMemo {

    private static final String ATTRIBUTE = ActorSalonAssignmentMemo.class.getName();

    /** The single memoised entry: which actor was read, and what came back. */
    private record Entry(UUID actorId, Optional<UUID> salonId) {}

    /**
     * Returns the actor's assigned salon id, loading it at most once per request per actor.
     *
     * @param actorId the authenticated actor whose {@code users.salon_id} is wanted
     * @param loader  how to read it when this request has not read it yet — invoked at most once per
     *                request, or on every call when no request is bound to the thread
     * @return the assignment, empty when the actor has none (an admin with no salon)
     */
    public Optional<UUID> salonIdOf(UUID actorId, Supplier<Optional<UUID>> loader) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return loader.get();
        }
        if (request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Entry entry
                && Objects.equals(entry.actorId(), actorId)) {
            return entry.salonId();
        }
        Optional<UUID> loaded = loader.get();
        request.setAttribute(ATTRIBUTE, new Entry(actorId, loaded), RequestAttributes.SCOPE_REQUEST);
        return loaded;
    }
}
