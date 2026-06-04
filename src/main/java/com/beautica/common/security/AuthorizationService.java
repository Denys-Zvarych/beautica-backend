package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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

    public boolean canManageMaster(Authentication auth, UUID masterId) {
        boolean hasSalonMasterRole = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SALON_MASTER"));
        if (hasSalonMasterRole) {
            return false;
        }
        UUID actorId = principalId(auth);
        Role actorRole = roleFromAuthentication(auth);
        return masterRepository.findByIdWithSalonAndOwner(masterId).map(m -> {
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
        return masterRepository.findByIdWithSalonAndOwner(masterId).map(m -> {
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
        return masterRepository.findByIdWithSalonAndOwner(masterId).map(m -> {
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
                return v.clientUserId().equals(actorId);
            }
            if (actorRole == Role.SALON_MASTER) {
                // SALON_MASTER may only view their own bookings — not all bookings at the salon.
                return v.masterUserId().equals(actorId);
            }
            return false;
        }).orElse(false);
    }

    public void enforceCanManageBooking(UUID actorUserId, Booking booking) {
        if (!isAuthorizedToManageBooking(actorUserId, booking)) {
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
            allowed = booking.getClient().getId().equals(actorUserId);
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

    private boolean isAuthorizedToManageBooking(UUID actorId, Booking booking) {
        // SALON_MASTER exclusion: callers are responsible for short-circuiting before reaching here.
        // canManageBooking() does so explicitly; other callers (enforceCanManageBooking, canViewBooking)
        // rely on the ID-ownership checks below, which a SALON_MASTER cannot satisfy because their
        // userId is never equal to the salon owner's userId.
        //
        // SALON_ADMIN exclusion: implicit via ownership semantics — SALON_ADMIN has a distinct userId
        // from the salon owner, so the owner-ID equality check below always returns false for them.
        // This means SALON_ADMIN cannot confirm/decline/complete individual bookings — only the salon
        // OWNER and the assigned INDEPENDENT_MASTER can. This boundary keeps booking lifecycle
        // authority at the owner level and must be preserved if ownership logic ever relaxes.
        Master master = booking.getMaster();
        if (master.getMasterType() == MasterType.INDEPENDENT_MASTER) {
            return master.getUser().getId().equals(actorId);
        }
        // SALON_OWNER-type master booking: master.salon.owner.id == actorId grants the
        // owner confirm/decline/complete authority over their own bookings. SALON_ADMIN
        // still excluded (distinct userId), preserving the owner-level lifecycle boundary.
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
     * Extracts the {@link Role} from the supplied {@code Authentication} object.
     * The JWT filter encodes the role as a {@code GrantedAuthority} with the
     * standard {@code ROLE_} prefix.
     *
     * <p>Wraps {@link Role#valueOf} to prevent an unchecked
     * {@link IllegalArgumentException} from propagating as a 500 when the token
     * carries an unrecognised role string. Re-thrown as {@link ForbiddenException}
     * so the global exception handler maps it to 403.
     */
    private Role roleFromAuthentication(Authentication auth) {
        if (auth == null) {
            throw new IllegalStateException("No authentication in security context");
        }
        return auth.getAuthorities().stream()
                .map(a -> {
                    String name = a.getAuthority().replace("ROLE_", "");
                    try {
                        return Role.valueOf(name);
                    } catch (IllegalArgumentException ex) {
                        throw new ForbiddenException("Unrecognized role in security context");
                    }
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No role in security context"));
    }

    /**
     * Convenience variant of {@link #roleFromAuthentication} that reads from
     * {@code SecurityContextHolder} directly. Used in non-{@code Authentication}-
     * accepting methods such as {@code enforceCanViewBooking}.
     */
    private Role roleFromCurrentAuthentication() {
        return roleFromAuthentication(SecurityContextHolder.getContext().getAuthentication());
    }

    private UUID principalId(Authentication auth) {
        // JwtAuthenticationFilter sets the UUID as authentication.getDetails()
        // and the email string as the principal.
        if (auth == null || !(auth.getDetails() instanceof UUID id)) {
            throw new IllegalStateException("No authenticated principal UUID in security context");
        }
        return id;
    }
}
