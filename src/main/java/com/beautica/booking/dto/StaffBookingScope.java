package com.beautica.booking.dto;

import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * <b>The authority context a staff booking is created under</b> — the half of
 * {@link StaffBookingCommand} that answers "on whose behalf may this caller book?".
 *
 * <h2>Why a sealed type and not a nullable {@code salonId} (security MEDIUM, 2026-08-18)</h2>
 * The field this replaces was a nullable {@code UUID salonId}, and {@code null} meant "independent
 * master, no salon". {@code StaffBookingService} implemented that by <b>returning early</b> from its
 * scoping check — so a {@code null} salon bought not "the independent-master rule" but <b>no rule at
 * all</b>: {@code masterId} was left wholly unconstrained and an independent-master command could
 * name any master in any salon. Only the {@code salonId != null} branch was ever scoped, and the
 * unit suite pinned only the {@code salonId == null && master.salon == null} shape, so the
 * {@code salonId == null && master.salon != null} hole passed silently.
 *
 * <p>The two modes are now <b>unrepresentable as one another</b>. There is no third state, no
 * absent state, and no arm of the exhaustive switch in
 * {@code StaffBookingService#assertMasterInScope} that skips scoping — the compiler, not a
 * reviewer, is what guarantees a command names its authority.
 *
 * <h2>Mirrors Phase 22.4's authorization matrix exactly</h2>
 * {@code AuthorizationService#canBookForMaster} (phase-171, amendment A3) splits on the SAME two
 * shapes: a {@code SALON_OWNER}/{@code SALON_ADMIN} may book any master in the salon they manage,
 * while an {@code INDEPENDENT_MASTER} may book <em>only themselves</em>
 * ({@code target.user.id == callerId}, no salon scoping). This type is the domain-layer restatement
 * of that split, so 22.4's gate and this service's defence-in-depth check cannot drift into
 * disagreeing about what "in scope" means.
 *
 * <p><b>Not an authorization substitute.</b> 22.4 still owns the {@code @PreAuthorize} role gate and
 * the {@code canBookForMaster} predicate; this is the second line behind it, and it is the line that
 * makes "unscoped" impossible to express rather than merely discouraged.
 */
public sealed interface StaffBookingScope {

    /**
     * A salon-scoped staff booking: the target master must belong to {@code salonId}.
     *
     * <p><b>{@code salonId} is the CALLER's salon — never the target master's.</b> The only
     * sanctioned producer is {@code StaffBookingScopeResolver} (Phase 22.4), which reads it out of a
     * query keyed on the authenticated actor and nothing else: {@code salons.owner_id = :actorId} for
     * a {@code SALON_OWNER}, {@code users.salon_id} for a {@code SALON_ADMIN}. It is never taken from
     * a client-supplied path variable or body field (phase-171 amendment A2 removed
     * {@code &#123;salonId&#125;} from the route for exactly that reason), and it is never derived
     * from the target master — the single-salon owner and admin branches do not read the master at
     * all.
     *
     * <p><b>Why deriving it from the master would be a vulnerability, not a shortcut.</b>
     * {@code StaffBookingService#assertMasterInScope}'s {@code InSalon} arm is
     * {@code master.getSalon().getId().equals(inSalon.salonId())}. Populate {@code salonId} from
     * {@code master.getSalon()} and that arm compares the master's salon <em>against itself</em> — a
     * tautology that can never fail, collapsing 100% of the salon path's defence in depth onto the
     * {@code @PreAuthorize} annotation alone. An earlier revision of this javadoc described the value
     * as "derived off the target master and re-verified", which is precisely the shape that is
     * forbidden; {@code StaffBookingScopeResolverTest} pins the correct provenance both by value and
     * by {@code verifyNoInteractions(masterRepository)}.
     *
     * <p>The one branch that touches the master — a multi-salon owner, who has no
     * {@code &#123;salonId&#125;} in the route to disambiguate with — uses it only as a
     * <em>selector among salons already proven to be the caller's</em>
     * ({@code MasterRepository#findSalonIdByIdAndSalonOwnerId} carries the ownership predicate inside
     * the query, and the result is re-checked against the actor-keyed set).
     *
     * <p>Note the asymmetry with {@link Self}, and that it is deliberate: 22.2 cross-checks
     * {@code Self} against the trusted {@code actorId} itself, so a body-sourced {@code Self} is
     * inert; {@code InSalon}'s claim — "the caller manages this salon" — needs a membership lookup
     * that can only live in the resolver.
     */
    record InSalon(UUID salonId) implements StaffBookingScope {
        public InSalon {
            salonId = required(salonId, "salonId");
        }
    }

    /**
     * A self-scoped staff booking: the target master's own {@code User} must be {@code masterUserId}.
     *
     * <p>The {@code INDEPENDENT_MASTER} case. An independent master has no salon, so there is nothing
     * to scope by — but "no salon" must not degrade to "no check": the identity assertion
     * {@code master.getUser().getId().equals(masterUserId)} is what confines the command to the one
     * master the caller IS. {@code masterUserId} comes from the security context in 22.4, never from
     * the request body.
     */
    record Self(UUID masterUserId) implements StaffBookingScope {
        public Self {
            masterUserId = required(masterUserId, "masterUserId");
        }
    }

    /**
     * A {@code 400}, never an {@link NullPointerException} — an NPE escaping a service reaches
     * {@code GlobalExceptionHandler}'s catch-all and surfaces as a 500 with a full ERROR stack trace
     * (security LOW, 2026-08-18). Same choice, same reason, as {@link StaffBookingCommand} and
     * {@link StaffClientRef.Guest}.
     */
    private static UUID required(UUID value, String field) {
        if (value == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }
}
