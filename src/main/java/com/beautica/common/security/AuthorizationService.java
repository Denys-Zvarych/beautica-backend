package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingCompletionAccess;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingViewAccess;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final BookingRepository bookingRepository;

    /**
     * Returns true when actorId has management access to the given salon.
     * Grants access to SALON_OWNER (by ownership) and SALON_ADMIN (by salon assignment).
     *
     * Use for: update, invite, schedule management operations.
     * Do NOT use for: delete/deactivate or admin-invite operations — those must also
     * check hasRole('SALON_OWNER') at the call site (e.g., @PreAuthorize annotation).
     */
    public boolean hasManagementAccess(UUID salonId, UUID actorId) {
        if (salonId == null) return false;
        // Read the role from the SecurityContext (already resolved by JwtAuthenticationFilter)
        // instead of issuing a userRepository.findById round-trip. For SALON_OWNER actors this
        // eliminates a wasted DB call — the ownership check goes straight to the repository query.
        // SALON_ADMIN still calls userRepository.findById inside the 3-arg overload because the
        // admin's assigned salonId is stored on the User record and cannot be derived from the JWT.
        Role actorRole = roleFromCurrentAuthentication();
        return hasManagementAccess(salonId, actorId, actorRole);
    }

    public boolean isOwnerOf(UUID salonId, UUID actorId) {
        if (salonId == null) return false;
        return salonRepository.existsByIdAndOwnerId(salonId, actorId);
    }

    /**
     * Role-aware fast path: if the JWT-derived role cannot possibly grant salon management
     * access (i.e. it is not SALON_OWNER or SALON_ADMIN), return false immediately without
     * any DB round-trip. Only SALON_OWNER and SALON_ADMIN proceed to the ownership query.
     */
    public boolean canManageSalon(Authentication auth, UUID salonId) {
        if (salonId == null) return false;
        boolean mayManage = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_OWNER")
                        || a.getAuthority().equals("ROLE_SALON_ADMIN"));
        if (!mayManage) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return hasManagementAccess(salonId, actorId, actorRole);
    }

    /**
     * Role-aware fast path (symmetry with {@link #canManageMasterSchedule}): the
     * {@code SALON_MASTER} (read-only) and {@code CLIENT} roles can never manage a master,
     * so they are rejected immediately without the {@code findByIdWithUserAndSalon}
     * DB round-trip (the role is read from the JWT-derived authorities, not the database).
     *
     * <p>{@code INDEPENDENT_MASTER} is intentionally NOT short-circuited: an independent
     * master manages their OWN master row (e.g. self-deactivation via
     * {@code DELETE /masters/{masterId}}), which the {@code INDEPENDENT_MASTER} branch below
     * authorizes by matching {@code m.getUser().getId()} to the actor. Early-rejecting it
     * would break that legitimate self-management path.
     */
    public boolean canManageMaster(Authentication auth, UUID masterId) {
        boolean cannotManage = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotManage) {
            return false;
        }
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return masterRepository.findByIdWithUserAndSalon(masterId).map(m -> {
            if (m.getMasterType() == MasterType.INDEPENDENT_MASTER) {
                return m.getUser().getId().equals(actorId);
            }
            // SALON_OWNER-type master: authorized via primary salon ownership.
            // Non-INDEPENDENT branch covers BOTH SALON_MASTER (invited) and SALON_OWNER
            // (owner-operated) masters: authority derives from salon management access.
            // Explicit SALON_OWNER case prevents silent fallthrough if new MasterType values are added.
            if (m.getMasterType() == MasterType.SALON_OWNER) {
                return m.getSalon() != null
                        && m.getSalon().getOwner() != null
                        && m.getSalon().getOwner().getId().equals(actorId);
            }
            // Remaining types (SALON_MASTER): authorize via salon management access.
            return m.getSalon() != null && hasManagementAccess(m.getSalon().getId(), actorId, actorRole);
        }).orElse(false);
    }

    /**
     * Role-aware fast path: CLIENT and SALON_MASTER roles can never manage a schedule,
     * so return false immediately without any DB round-trip. Only SALON_OWNER, SALON_ADMIN,
     * and INDEPENDENT_MASTER proceed to the master ownership query.
     */
    public boolean canManageMasterSchedule(Authentication auth, UUID masterId) {
        boolean cannotManage = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotManage) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return masterRepository.findByIdWithUserAndSalon(masterId).map(m -> {
            if (m.getMasterType() == MasterType.INDEPENDENT_MASTER) {
                return m.getUser().getId().equals(actorId);
            }
            // Non-INDEPENDENT branch covers BOTH SALON_MASTER (invited) and SALON_OWNER
            // (owner-operated) masters: authority derives from salon management access.
            // Explicit SALON_OWNER case prevents silent fallthrough if new MasterType values are added.
            if (m.getMasterType() == MasterType.SALON_OWNER) {
                return m.getSalon() != null
                        && m.getSalon().getOwner() != null
                        && m.getSalon().getOwner().getId().equals(actorId);
            }
            // Remaining types (SALON_MASTER): authorize via salon management access.
            return m.getSalon() != null && hasManagementAccess(m.getSalon().getId(), actorId, actorRole);
        }).orElse(false);
    }

    /**
     * Read predicate for the master-schedule endpoints (Phase 15.5 / OQ-2 — RESOLVED).
     *
     * <p>Returns true for the <b>owning master</b> — including a {@code SALON_MASTER} reading
     * <b>his own</b> schedule (read-only role) — and for the master's {@code SALON_OWNER} /
     * {@code SALON_ADMIN}. Returns false for {@code CLIENT} (no schedule-read path; clients see
     * bookable slots only via the public {@code /slots} endpoint) and for any foreign master.
     *
     * <p>Role fast path: {@code CLIENT} can never read a schedule, so it is rejected immediately
     * without a DB round-trip. All other roles proceed to the single master ownership lookup
     * (one read-side DB hit is acceptable for a SpEL read predicate — Anti-Bug §D).
     */
    public boolean canReadMasterSchedule(Authentication auth, UUID masterId) {
        boolean isClient = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CLIENT"));
        if (isClient) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return masterRepository.findByIdWithUserAndSalon(masterId).map(m -> {
            // The owning master (INDEPENDENT_MASTER or any salon-bound master, incl. SALON_MASTER
            // reading his own id) is always granted read access to his own schedule.
            if (m.getUser() != null && m.getUser().getId().equals(actorId)) {
                return true;
            }
            // Otherwise the actor must be the master's SALON_OWNER / SALON_ADMIN.
            return m.getSalon() != null && hasManagementAccess(m.getSalon().getId(), actorId, actorRole);
        }).orElse(false);
    }

    public void enforceCanManageSalon(UUID actorId, Salon salon) {
        if (!hasManagementAccess(salon.getId(), actorId)) {
            throw new ForbiddenException("Access denied");
        }
    }

    /**
     * Service-layer (defense-in-depth) ownership guard for a {@link ServiceDefinition}
     * mutation, the actorId-accepting twin of {@link #canManageServiceDefinition}.
     *
     * <p>Reuses the same {@code findOwnerUserId} projection the SpEL {@code @PreAuthorize}
     * gate uses, so no entity is loaded twice and no new query is introduced. A missing
     * definition is treated as access-denied (403), consistent with the SpEL variant which
     * returns {@code false} for an unknown id (anti-bug §B/§D — never leak existence via
     * a distinct status).
     *
     * @throws ForbiddenException if the actor is not the owner of the definition's parent
     */
    public void enforceCanManageServiceDefinition(UUID actorId, UUID serviceDefId) {
        boolean allowed = serviceRepository.findOwnerUserId(serviceDefId)
                .map(ownerUserId -> ownerUserId.equals(actorId))
                .orElse(false);
        if (!allowed) {
            throw new ForbiddenException("Access denied");
        }
    }

    public void enforceCanManageMaster(UUID actorId, Master master) {
        boolean allowed;
        if (master.getMasterType() == MasterType.INDEPENDENT_MASTER) {
            allowed = master.getUser().getId().equals(actorId);
        } else if (master.getMasterType() == MasterType.SALON_OWNER) {
            // Non-INDEPENDENT branch covers BOTH SALON_MASTER (invited) and SALON_OWNER
            // (owner-operated) masters: authority derives from salon management access.
            // Explicit SALON_OWNER case prevents silent fallthrough if new MasterType values are added.
            allowed = master.getSalon() != null
                    && master.getSalon().getOwner() != null
                    && master.getSalon().getOwner().getId().equals(actorId);
        } else {
            // Remaining types (SALON_MASTER): authorize via salon management access.
            allowed = master.getSalon() != null && hasManagementAccess(master.getSalon().getId(), actorId);
        }
        if (!allowed) {
            throw new ForbiddenException("Access denied");
        }
    }

    public void enforceCanManageMasterSchedule(UUID actorId, Master master) {
        boolean allowed;
        if (master.getMasterType() == MasterType.INDEPENDENT_MASTER) {
            allowed = master.getUser().getId().equals(actorId);
        } else if (master.getMasterType() == MasterType.SALON_OWNER) {
            // Non-INDEPENDENT branch covers BOTH SALON_MASTER (invited) and SALON_OWNER
            // (owner-operated) masters: authority derives from salon management access.
            // Explicit SALON_OWNER case prevents silent fallthrough if new MasterType values are added.
            allowed = master.getSalon() != null
                    && master.getSalon().getOwner() != null
                    && master.getSalon().getOwner().getId().equals(actorId);
        } else {
            // Remaining types (SALON_MASTER): authorize via salon management access.
            allowed = master.getSalon() != null && hasManagementAccess(master.getSalon().getId(), actorId);
        }
        if (!allowed) {
            throw new ForbiddenException("Access denied");
        }
    }

    /**
     * Returns true iff the given master is a member of the given salon.
     * Used in @PreAuthorize on assignServiceToMaster to prevent a timing-oracle
     * IDOR where a caller with a valid token for Salon B could probe whether a
     * master UUID belongs to Salon A by observing 403 vs 404 responses.
     *
     * Returns false immediately when either argument is null.
     */
    public boolean masterBelongsToSalon(UUID masterId, UUID salonId) {
        if (masterId == null || salonId == null) return false;
        return masterRepository.existsByIdAndSalonId(masterId, salonId);
    }

    /**
     * Returns true iff the given user is a {@code SALON_ADMIN} currently assigned to the
     * given salon. Mirrors {@link #masterBelongsToSalon} — used in {@code @PreAuthorize} on
     * {@code DELETE /salons/{salonId}/admins/{userId}} to prevent a timing-oracle IDOR where a
     * caller with management access to Salon A could probe whether a user UUID is a
     * SALON_ADMIN of Salon B by observing 403 vs 404/204 responses.
     *
     * <p>Returns false immediately when either argument is null.
     */
    public boolean adminBelongsToSalon(UUID userId, UUID salonId) {
        if (userId == null || salonId == null) return false;
        return userRepository.existsByIdAndSalonIdAndRole(userId, salonId, Role.SALON_ADMIN);
    }

    /**
     * Returns {@code true} iff {@code sourceSalonId} and {@code destSalonId} are both salons
     * owned by the same {@code SALON_OWNER}. Used by the Phase 21.3 rotate-admin/rotate-master
     * flows as a service-layer (not {@code @PreAuthorize} SpEL) guard: rotation is legal only
     * within a single owner's portfolio of salons — an admin or owner acting on Salon A must
     * never be able to move staff into a salon they have no visibility into.
     *
     * <p>Uses {@link SalonRepository#findOwnerIdById} — a single-column owner-id projection —
     * instead of hydrating the full {@code Salon} entity (~20 columns) just to read
     * {@code owner.getId()} (Perf MEDIUM-3). Combined with {@link SalonRepository#existsByIdAndOwnerId}
     * this resolves the same-owner check in two narrow queries with no full entity load.
     * Returns {@code false} immediately when either argument is {@code null}, when
     * {@code sourceSalonId} does not resolve to a salon, or when that salon has no owner
     * (should not happen in practice — {@code Salon.owner} is {@code nullable = false} —
     * but treated as a safe deny rather than an NPE).
     */
    public boolean salonsShareOwner(UUID sourceSalonId, UUID destSalonId) {
        if (sourceSalonId == null || destSalonId == null) return false;
        return salonRepository.findOwnerIdById(sourceSalonId)
                .map(ownerId -> salonRepository.existsByIdAndOwnerId(destSalonId, ownerId))
                .orElse(false);
    }

    /**
     * Returns true iff the authenticated actor owns the parent entity of the given
     * ServiceDefinition:
     *   ownerType == SALON              → actor must own the salon (ownerId is salonId)
     *   ownerType == INDEPENDENT_MASTER → actor must be the master's own user (ownerId is masterId)
     *
     * Returns false — causing 403 — when the service definition does not exist.
     *
     * Role fast-path: CLIENT, SALON_MASTER, and SALON_ADMIN can never own a ServiceDefinition,
     * so they are rejected immediately without any DB round-trip (timing-oracle MEDIUM-1).
     * Only SALON_OWNER and INDEPENDENT_MASTER proceed to the ownership query.
     *
     * A single JPQL projection query resolves the owner's user UUID directly,
     * eliminating the two-query chain used previously.
     */
    public boolean canManageServiceDefinition(Authentication auth, UUID serviceDefId) {
        boolean mayManage = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_OWNER")
                        || a.getAuthority().equals("ROLE_INDEPENDENT_MASTER"));
        if (!mayManage) return false;  // CLIENT / SALON_MASTER / SALON_ADMIN → 403, no DB hit
        UUID actorId = principalId(auth);
        return serviceRepository.findOwnerUserId(serviceDefId)
                .map(ownerUserId -> ownerUserId.equals(actorId))
                .orElse(false);
    }

    /**
     * Returns true iff the actor has management authority over the given booking.
     *
     * <p>Uses the lightweight {@code findViewAccessById} projection (3 UUID columns,
     * no entity graph) instead of {@code findByIdWithFullGraph} (6-join entity load),
     * eliminating the redundant full-graph fetch that would otherwise occur on every
     * {@code @PreAuthorize} SpEL evaluation before the service method loads the same
     * booking again.
     *
     * <p>Authorization rule (mirrors {@link #isAuthorizedToManageBooking}):
     * <ul>
     *   <li>{@code salonOwnerUserId != null} — salon booking: actor must be the salon owner.</li>
     *   <li>{@code salonOwnerUserId == null} — independent master booking: actor must be the master's user.</li>
     * </ul>
     * {@code ROLE_SALON_MASTER} is rejected immediately (no DB round-trip).
     */
    public boolean canManageBooking(Authentication auth, UUID bookingId) {
        boolean isSalonMaster = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SALON_MASTER"));
        if (isSalonMaster) return false;
        UUID actorId = principalId(auth);
        return bookingRepository.findViewAccessById(bookingId).map(v -> {
            if (v.salonOwnerUserId() != null) {
                return v.salonOwnerUserId().equals(actorId);
            }
            return v.masterUserId().equals(actorId);
        }).orElse(false);
    }

    public boolean canViewBooking(Authentication auth, UUID bookingId) {
        UUID actorId = principalId(auth);
        // Finding 2: role is derived from the SecurityContext (set by JwtAuthenticationFilter)
        // instead of from a cross-entity DB join, eliminating the Cartesian product.
        Role actorRole = roleFromAuthentication(auth);
        return bookingRepository.findViewAccessById(bookingId).map(v -> {
            // Management access: SALON_OWNER whose id matches the salon owner, or INDEPENDENT_MASTER
            // whose user id matches the master's user id. Both checks use the projection fields
            // resolved in a single JOIN — no second DB round-trip on any branch.
            //
            // SALON_OWNER-type master booking: both the salonOwnerUserId branch AND the masterUserId
            // branch fire for the owner (the owner is the master's user), granting full client-data
            // visibility under the salon-owner branch. There is no contradiction — both return true.
            if (v.salonOwnerUserId() != null && v.salonOwnerUserId().equals(actorId)) {
                return true;
            }
            if (v.masterUserId().equals(actorId) && actorRole != Role.SALON_MASTER) {
                return true;
            }
            if (actorRole == Role.CLIENT) {
                // Guest (LINK) bookings have a null clientUserId (findViewAccessById now
                // LEFT JOINs client, per the track 24.7 audit) — null-guard so a CLIENT
                // probing a guest booking's id denies cleanly instead of NPEing.
                return v.clientUserId() != null && v.clientUserId().equals(actorId);
            }
            if (actorRole == Role.SALON_MASTER) {
                // SALON_MASTER may only view their own bookings — not all bookings at the salon.
                return v.masterUserId().equals(actorId);
            }
            return false;
        }).orElse(false);
    }

    /**
     * Shared provider-authority predicate underlying booking completion, no-show, and
     * provider-initiated cancellation (Phase 18.4 completion; Phase 24.2 decline/not-complete).
     * An {@code INDEPENDENT_MASTER}-type booking is authorized by the master's own user; a
     * salon-type booking (SALON_OWNER-type or SALON_MASTER-type master) resolves via
     * {@link #hasManagementAccess}, which admits BOTH the salon owner (by ownership) AND the
     * assigned {@code SALON_ADMIN} (by salon assignment).
     *
     * <p><strong>Intentional divergence from {@link #isAuthorizedToManageBooking}</strong> (used
     * only by {@link #enforceCanViewBooking} now): that predicate deliberately excludes
     * {@code SALON_ADMIN}. Do NOT broaden {@code isAuthorizedToManageBooking} to match this one.
     */
    private boolean hasProviderAuthorityOverBooking(
            boolean independentMasterBooking, UUID masterUserId, UUID salonId, UUID actorId, Role actorRole) {
        if (independentMasterBooking) {
            return masterUserId != null && masterUserId.equals(actorId);
        }
        return salonId != null && hasManagementAccess(salonId, actorId, actorRole);
    }

    /**
     * Entity-based wrapper of {@link #hasProviderAuthorityOverBooking} for the {@code enforce*}
     * guards.
     *
     * <p><b>Perf finding 5 (track 24.7 audit):</b> every caller of this overload loads the
     * booking via {@code BookingRepository#findByIdWithFullGraph}, which {@code LEFT JOIN FETCH}es
     * {@code m.salon s} — so a {@code SALON_OWNER} actor can be authorized purely in memory, with
     * zero extra round trip. Only the {@code SALON_ADMIN} case falls back to
     * {@link #hasManagementAccess}, a real DB query, because the admin's assigned-salon id
     * genuinely is not present in the loaded graph.
     *
     * <p><b>{@code s.owner} is deliberately NOT fetched</b>, and this check does not need it to be.
     * The owner comparison below reads {@code getOwner().getId()} only; Hibernate serves an
     * identifier off an uninitialised proxy without issuing a statement, and {@code Salon.owner} is
     * a {@code nullable = false} {@code @ManyToOne(LAZY)} so the proxy always exists. Fetching the
     * owner would hydrate a full {@code User} row ({@code password_hash} included) to answer a
     * question already answered by the FK sitting in the {@code salons} row. Do not "restore" the
     * fetch join for this method's benefit — see {@code BookingRepository#findByIdWithFullGraph}.
     *
     * <p>Evaluation is branch-local: the in-memory owner comparison runs first and short-circuits
     * on a match; {@link #hasManagementAccess} is invoked only when it does not, so this never
     * unconditionally pays for both checks (the eager-evaluation bug this replaces).
     *
     * <p><b>Public since the {@code providerCanReviewClient} viewer-aware DTO field</b> (extends
     * Phase 27.5): {@code BookingService#getBooking} calls this directly, non-throwing, to
     * pre-compute whether the CURRENT viewer has provider review-authority over the booking being
     * fetched — the exact same predicate {@link #enforceCanReviewClient} throws on, reused rather
     * than re-derived so the two can never diverge. The booking is already loaded once by that
     * caller (via {@code findByIdWithFullGraph}), so this remains a zero-extra-query call on the
     * SALON_OWNER branch, same as every other caller of this overload.
     */
    public boolean hasProviderAuthorityOverBooking(UUID actorId, Booking booking) {
        Master master = booking.getMaster();
        if (master.getMasterType() == MasterType.INDEPENDENT_MASTER) {
            return hasProviderAuthorityOverBooking(true, master.getUser().getId(), null, actorId, null);
        }
        Salon salon = master.getSalon();
        if (salon == null) {
            return false;
        }
        if (salon.getOwner() != null && salon.getOwner().getId().equals(actorId)) {
            return true;
        }
        // Not the (in-memory) owner — fall back to the DB-backed check, which also covers the
        // assigned SALON_ADMIN case the loaded graph cannot answer.
        return hasManagementAccess(salon.getId(), actorId, roleFromCurrentAuthentication());
    }

    /**
     * Service-layer completion guard (Phase 18.4) — the entity-based twin of
     * {@link #canCompleteBooking}. See {@link #hasProviderAuthorityOverBooking(UUID, Booking)}
     * for the shared predicate.
     */
    public void enforceCanCompleteBooking(UUID actorUserId, Booking booking) {
        if (!hasProviderAuthorityOverBooking(actorUserId, booking)) {
            throw new ForbiddenException("Access denied");
        }
    }

    /**
     * Service-layer provider-cancellation guard (Phase 24.2) — the entity-based twin of
     * {@link #canCancelBooking}, backing {@code declineBooking}/{@code notCompleteBooking}.
     * Same predicate as {@link #enforceCanCompleteBooking}: an actor trusted to mark a visit
     * completed is trusted to cancel one or mark a no-show (decisions D1/D2).
     */
    public void enforceCanCancelBooking(UUID actorUserId, Booking booking) {
        if (!hasProviderAuthorityOverBooking(actorUserId, booking)) {
            throw new ForbiddenException("Access denied");
        }
    }

    /**
     * SpEL {@code @PreAuthorize} completion predicate (Phase 18.4), mirroring
     * {@link #canManageBooking} but on the lightweight {@link BookingCompletionAccess} projection
     * (which carries the booking's {@code salonId}) so a {@code SALON_ADMIN} assigned to the
     * booking's salon is admitted — see {@link #enforceCanCompleteBooking} for the intentional
     * divergence from the manage predicate.
     *
     * <p>Role fast path: {@code SALON_MASTER} (read-only) and {@code CLIENT} can never complete a
     * booking, so they are rejected immediately with no DB round-trip (timing-oracle hygiene).
     * A missing booking maps to {@code false} (403, no existence oracle).
     */
    public boolean canCompleteBooking(Authentication auth, UUID bookingId) {
        boolean cannotComplete = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotComplete) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return bookingRepository.findCompletionAccessById(bookingId)
                .map(v -> hasProviderAuthorityOverBooking(
                        v.salonId() == null, v.masterUserId(), v.salonId(), actorId, actorRole))
                .orElse(false);
    }

    /**
     * SpEL {@code @PreAuthorize} provider-cancellation predicate (Phase 24.2), backing
     * {@code PATCH /bookings/{id}/decline} and {@code PATCH /bookings/{id}/not-complete}.
     * Reuses the same {@link BookingCompletionAccess} projection and predicate as
     * {@link #canCompleteBooking} — see {@link #enforceCanCancelBooking} for why the two
     * actions share one authority shape.
     *
     * <p>Role fast path + missing-booking handling mirror {@link #canCompleteBooking} exactly.
     */
    public boolean canCancelBooking(Authentication auth, UUID bookingId) {
        boolean cannotCancel = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotCancel) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return bookingRepository.findCompletionAccessById(bookingId)
                .map(v -> hasProviderAuthorityOverBooking(
                        v.salonId() == null, v.masterUserId(), v.salonId(), actorId, actorRole))
                .orElse(false);
    }

    /**
     * SpEL {@code @PreAuthorize} provider-reschedule predicate (Phase 27.2), backing the provider
     * arm of {@code PATCH /bookings/{id}/reschedule}'s union role check:
     * {@code hasRole('CLIENT') or (hasAnyRole(providers) and @authz.canRescheduleBooking(...))}.
     *
     * <p>Reuses the exact same {@link BookingCompletionAccess} projection and
     * {@link #hasProviderAuthorityOverBooking} predicate as {@link #canCancelBooking}/
     * {@link #canCompleteBooking} — mirrors them verbatim, including the role fast path and the
     * missing-booking handling.
     *
     * <p><b>Why this method never needs to special-case {@code ROLE_CLIENT}:</b> Spring SpEL
     * evaluates {@code hasRole('CLIENT') or (hasAnyRole(...) and @authz.canRescheduleBooking(...))}
     * left-to-right with short-circuiting {@code or} — for a CLIENT principal the left operand is
     * already {@code true}, so the right operand (and therefore this method) is never evaluated at
     * all. This method is only ever reached once {@code hasAnyRole('SALON_OWNER','SALON_ADMIN',
     * 'INDEPENDENT_MASTER')} has already narrowed the caller to a provider role, so rejecting
     * {@code ROLE_SALON_MASTER}/{@code ROLE_CLIENT} here (mirroring {@link #canCancelBooking}) is
     * purely defensive — the client path structurally never reaches this method.
     */
    public boolean canRescheduleBooking(Authentication auth, UUID bookingId) {
        boolean cannotReschedule = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotReschedule) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return bookingRepository.findCompletionAccessById(bookingId)
                .map(v -> hasProviderAuthorityOverBooking(
                        v.salonId() == null, v.masterUserId(), v.salonId(), actorId, actorRole))
                .orElse(false);
    }

    /**
     * Service-layer provider-reschedule guard (Phase 27.2) — the entity-based twin of
     * {@link #canRescheduleBooking}, for {@code BookingService.rescheduleBooking}'s provider
     * branch to call after its own single load (mirrors {@link #enforceCanCancelBooking}/
     * {@link #enforceCanCompleteBooking}).
     */
    public void enforceCanRescheduleBooking(UUID actorUserId, Booking booking) {
        if (!hasProviderAuthorityOverBooking(actorUserId, booking)) {
            throw new ForbiddenException("Access denied");
        }
    }

    /**
     * SpEL {@code @PreAuthorize} provider-reschedule predicate for the VISIT-level analogue of
     * {@link #canRescheduleBooking}, backing the provider arm of
     * {@code PATCH /appointments/{id}/reschedule}'s union role check: {@code hasRole('CLIENT') or
     * (hasAnyRole(providers) and @authz.canRescheduleAppointment(...))}.
     *
     * <p>A visit is single-master (BE-1 locked design), so every chained row resolves to the
     * IDENTICAL {@code (masterUserId, salonId)} pair via
     * {@link BookingRepository#findCompletionAccessByAppointmentId} — only one row is fetched
     * (capped via {@code Limit.of(1)}, deterministically ordered by the query), authoritative for
     * the whole visit, exactly as {@link #canRescheduleBooking} uses the single booking's own
     * pair. Role fast path + missing-visit handling mirror {@link #canRescheduleBooking} exactly
     * (no existence oracle — a missing/foreign/itemless appointment id answers {@code false}).
     */
    public boolean canRescheduleAppointment(Authentication auth, UUID appointmentId) {
        boolean cannotReschedule = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotReschedule) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        List<BookingCompletionAccess> access =
                bookingRepository.findCompletionAccessByAppointmentId(appointmentId, Limit.of(1));
        if (access.isEmpty()) return false;
        BookingCompletionAccess v = access.get(0);
        return hasProviderAuthorityOverBooking(v.salonId() == null, v.masterUserId(), v.salonId(), actorId, actorRole);
    }

    /**
     * SpEL {@code @PreAuthorize} predicate for {@code POST /client-reviews} (Phase 27.5 — REVERSES
     * the previously-deferred/out-of-scope status of master&rarr;client reviews). Mirrors {@link
     * #canCancelBooking}/{@link #canCompleteBooking}/{@link #canRescheduleBooking} verbatim — a
     * provider may review the CLIENT of any booking they have provider authority over, the exact
     * same authority shape as decline/complete/reschedule.
     */
    public boolean canReviewClient(Authentication auth, UUID bookingId) {
        boolean cannotReview = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotReview) return false;
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return bookingRepository.findCompletionAccessById(bookingId)
                .map(v -> hasProviderAuthorityOverBooking(
                        v.salonId() == null, v.masterUserId(), v.salonId(), actorId, actorRole))
                .orElse(false);
    }

    /**
     * Service-layer defense-in-depth guard (Phase 27.5) — the entity-based twin of {@link
     * #canReviewClient}, for {@code ClientReviewService.create} to call after its own single load
     * (mirrors {@link #enforceCanCancelBooking}/{@link #enforceCanRescheduleBooking}).
     */
    public void enforceCanReviewClient(UUID actorUserId, Booking booking) {
        if (!hasProviderAuthorityOverBooking(actorUserId, booking)) {
            throw new ForbiddenException("Access denied");
        }
    }

    public void enforceCanViewBooking(UUID actorUserId, Booking booking) {
        if (isAuthorizedToManageBooking(actorUserId, booking)) {
            return;
        }
        // Finding 3: role is derived from the SecurityContext instead of a userRepository
        // round-trip. The booking entity is already loaded by the caller, so all
        // ownership fields are available in memory — no additional DB call is needed.
        Role actorRole = roleFromCurrentAuthentication();
        boolean allowed = false;
        if (actorRole == Role.CLIENT) {
            // Guest (LINK) bookings have a null client (V89 chk_bookings_guest_fields) — a
            // CLIENT can never own one, so null-guard rather than dereference getId() on null
            // (regression: NPE'd into a 500 instead of the correct 403 here).
            allowed = booking.getClient() != null && booking.getClient().getId().equals(actorUserId);
        } else if (actorRole == Role.SALON_MASTER) {
            // Fix M1: SALON_MASTER may only view their own bookings, not all bookings
            // at the salon — the previous salon-scoped check leaked other masters'
            // client names and prices to every master at the same salon.
            allowed = booking.getMaster().getUser().getId().equals(actorUserId);
        }
        if (!allowed) {
            throw new ForbiddenException("Access denied");
        }
    }

    /**
     * Owner-only booking-management predicate. As of Phase 24.2 this backs ONLY
     * {@link #canViewBooking} / {@link #enforceCanViewBooking} — the completion, decline, and
     * not-complete actions moved to {@link #hasProviderAuthorityOverBooking(UUID, Booking)},
     * which additionally admits the assigned {@code SALON_ADMIN}. Do NOT broaden this predicate
     * to match that one; the divergence (owner-only view-authorization vs. owner+admin
     * provider-action authorization) is intentional.
     */
    private boolean isAuthorizedToManageBooking(UUID actorId, Booking booking) {
        // SALON_MASTER exclusion: callers are responsible for short-circuiting before reaching here.
        // canManageBooking() does so explicitly; other callers (canViewBooking) rely on the
        // ID-ownership checks below, which a SALON_MASTER cannot satisfy because their userId is
        // never equal to the salon owner's userId.
        //
        // SALON_ADMIN exclusion: implicit via ownership semantics — SALON_ADMIN has a distinct userId
        // from the salon owner, so the owner-ID equality check below always returns false for them.
        Master master = booking.getMaster();
        if (master.getMasterType() == MasterType.INDEPENDENT_MASTER) {
            return master.getUser().getId().equals(actorId);
        }
        // SALON_OWNER-type master booking: master.salon.owner.id == actorId grants the owner
        // view authority over their own bookings. SALON_ADMIN still excluded (distinct userId).
        // Non-INDEPENDENT branch covers BOTH SALON_MASTER (invited) and SALON_OWNER
        // (owner-operated) masters: authority derives from salon management access.
        // Explicit SALON_OWNER case prevents silent fallthrough if new MasterType values are added.
        if (master.getMasterType() == MasterType.SALON_OWNER) {
            return master.getSalon() != null
                    && master.getSalon().getOwner() != null
                    && master.getSalon().getOwner().getId().equals(actorId);
        }
        // Remaining types (SALON_MASTER): owner of the master's salon has manage authority.
        if (master.getSalon() != null) {
            return master.getSalon().getOwner() != null
                    && master.getSalon().getOwner().getId().equals(actorId);
        }
        return false;
    }

    /**
     * Role-aware fast path for callers that have already resolved {@code actorRole}
     * from the JWT. Avoids the {@code userRepository.findById} round-trip for
     * {@code SALON_OWNER} actors — the ownership check goes directly to the
     * repository query that verifies the owner relationship.
     *
     * <p>The {@code SALON_ADMIN} branch still calls {@code userRepository.findById}
     * because the admin's assigned {@code salonId} is stored on the {@code User}
     * record and cannot be derived from the JWT alone.
     */
    private boolean hasManagementAccess(UUID salonId, UUID actorId, Role actorRole) {
        if (actorRole == Role.SALON_OWNER) {
            return salonRepository.existsByIdAndOwnerId(salonId, actorId);
        }
        if (actorRole == Role.SALON_ADMIN) {
            // Fix MEDIUM-7 PERF: the previous findById loaded the full User entity
            // (including passwordHash) just to read salonId. findSalonIdById uses a
            // SELECT projection that fetches only the salonId column — one column vs all.
            return userRepository.findSalonIdById(actorId)
                    .map(salonId::equals)
                    .orElse(false);
        }
        return false;
    }

    /**
     * Resolves the {@link Role} of the supplied {@code Authentication}.
     *
     * <p>Delegates to {@link AuthenticationUtils#role(Authentication)} — the single
     * canonical reader of the {@code Authentication} authorities (B14 dedup). This is the
     * last {@code AuthorizationService}-local copy of role extraction being routed through
     * that reader, so there is now exactly one implementation. Fails closed with
     * {@link ForbiddenException} (403) on a missing/unrecognised/ambiguous role.
     */
    private Role roleFromAuthentication(Authentication auth) {
        return AuthenticationUtils.role(auth);
    }

    /**
     * Convenience variant of {@link #roleFromAuthentication} that reads from
     * {@code SecurityContextHolder} directly. Used in non-{@code Authentication}-
     * accepting methods such as {@code enforceCanViewBooking}.
     */
    private Role roleFromCurrentAuthentication() {
        return roleFromAuthentication(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Resolves the authenticated principal's UUID from the supplied {@code Authentication}.
     *
     * <p>Delegates to {@link AuthenticationUtils#userId(Authentication)} — the single
     * canonical reader of the token principal/details (B14 dedup). Fails closed with
     * {@link ForbiddenException} (403) on a missing/non-UUID principal (Anti-Bug §B2),
     * rather than the previous raw {@link IllegalStateException} that surfaced as a 500.
     * Not exploitable today — {@code JwtAuthenticationFilter} always sets a UUID — so the
     * happy path is behaviour-identical; only the malformed-principal error type changes.
     */
    private UUID principalId(Authentication auth) {
        return AuthenticationUtils.userId(auth);
    }
}
