package com.beautica.salon.controller;

import com.beautica.auth.dto.InviteResponse;
import com.beautica.booking.dto.BookableMasterResponse;
import com.beautica.booking.service.BookingMasterService;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.InviteRequest;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.RotateAdminRequest;
import com.beautica.salon.dto.SalonAdminResponse;
import com.beautica.salon.dto.SalonInviteHistoryResponse;
import com.beautica.salon.dto.SalonInviteResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.SalonStaffMemberResponse;
import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.service.SalonService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/salons")
@RequiredArgsConstructor
public class SalonController {

    private final SalonService salonService;
    private final BookingMasterService bookingMasterService;

    @PostMapping
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ResponseEntity<ApiResponse<SalonResponse>> createSalon(
            @Valid @RequestBody CreateSalonRequest request,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        SalonResponse response = salonService.createSalon(ownerId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ApiResponse<List<SalonResponse>> getOwnedSalons(Authentication authentication) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(salonService.getOwnerSalons(ownerId));
    }

    @GetMapping("/{salonId}")
    public ApiResponse<PublicSalonResponse> getSalon(@PathVariable UUID salonId) {
        return ApiResponse.ok(salonService.getPublicSalon(salonId));
    }

    @PatchMapping("/{salonId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<SalonResponse> updateSalon(
            @PathVariable UUID salonId,
            @Valid @RequestBody UpdateSalonRequest request,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(salonService.updateSalon(ownerId, salonId, request));
    }

    // SALON_ADMIN may invite masters to their own salon — intentional per product decision.
    // canManageSalon enforces salonId == admin's assigned salon_id set at invite time.
    @PostMapping("/{salonId}/invite")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<ApiResponse<InviteResponse>> inviteMaster(
            @PathVariable UUID salonId,
            @Valid @RequestBody InviteRequest request,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        InviteResponse response = salonService.inviteMaster(ownerId, salonId, request.email(), request.effectiveRole());
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/{salonId}/masters")
    public ApiResponse<PageResponse<MasterSummaryResponse>> getMastersBySalon(
            @PathVariable UUID salonId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        var page = salonService.getMastersBySalon(salonId, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    /**
     * Management-scoped staff roster (Phase 21.5) — masters AND admins in one read, backing the
     * mobile Персонал tab and the staff-member detail screen for BOTH a {@code SALON_MASTER} and
     * a {@code SALON_ADMIN}. Unlike {@link #getMastersBySalon} (public, master-only,
     * PII-masked) this endpoint returns unmasked {@code phoneNumber}/{@code instagram} and
     * includes admins — so it is management-gated, not {@code permitAll}.
     *
     * <p>{@code @authz.canManageSalon} is the IDENTICAL expression already gating
     * {@link #updateSalon}/{@link #inviteMaster}/{@link #listSalonInvites} — reused verbatim,
     * not re-derived, so a future role change to salon management cannot diverge between sibling
     * endpoints.
     */
    @Operation(summary = "List salon staff (masters and admins)",
            description = "Management-scoped roster combining the salon's masters (any type) "
                    + "and SALON_ADMINs, with unmasked contact details. Requires management "
                    + "access to the salon (owner or assigned admin).")
    @GetMapping("/{salonId}/staff")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<List<SalonStaffMemberResponse>> getSalonStaff(@PathVariable UUID salonId) {
        return ApiResponse.ok(salonService.getSalonStaff(salonId));
    }

    /**
     * Sibling salons (Phase 21.3b) — every ACTIVE salon sharing {@code salonId}'s owner,
     * <b>excluding {@code salonId} itself</b>. This is the destination candidate set for
     * {@link #rotateAdmin} ({@code PATCH /{salonId}/admins/{userId}/salon}), which already enforces
     * the same-owner rule server-side; this read only lets the mobile rotate-admin picker
     * (mobile Phase 21.6) show the correct choices instead of guessing.
     *
     * <p><b>Self is excluded by design:</b> the picker chooses a <em>destination</em>, and
     * rotating an admin into the salon they already occupy is a no-op that {@code rotateAdmin}
     * rejects with 400. Offering it would render a guaranteed-to-fail option.
     *
     * <p>Why {@code GET /mine} cannot serve this: it is {@code hasRole('SALON_OWNER')} only, so the
     * {@code SALON_ADMIN} who may legitimately perform the rotation gets 403 — and even for an
     * owner it returns the CALLER's portfolio, not the portfolio of {@code salonId}'s owner.
     *
     * <p>{@code @authz.canManageSalon} is the IDENTICAL expression already gating
     * {@link #getSalonStaff}/{@link #updateSalon}/{@link #listSalonInvites} — reused verbatim,
     * not re-derived, so a future role change to salon management cannot diverge between sibling
     * endpoints. It also places the caller inside exactly the trust boundary {@link #rotateAdmin}
     * operates in, so this leaks nothing that mutation does not already expose — an argument that
     * depends on self-rotation remaining legal for a {@code SALON_ADMIN}; see
     * {@link SalonService#getSiblingSalons} for that coupling.
     *
     * <p><b>Returns {@link SiblingSalonOption}, not {@code SalonResponse}.</b> A picker needs the
     * id it will submit plus enough text to tell two salons apart. The full {@code SalonResponse}
     * additionally handed an assigned {@code SALON_ADMIN} the owner's UUID and every sibling's
     * {@code description}, {@code phone}, {@code instagramUrl}, {@code avatarUrl}, legacy
     * city/region/address, {@code isPrimary} and {@code createdAt} — for salons they hold no
     * assignment to. See that record's Javadoc.
     */
    @Operation(summary = "List sibling salons of the same owner",
            description = "Active salons sharing this salon's owner, excluding this salon itself, "
                    + "as id + name + short address. Backs the rotate-admin destination picker. "
                    + "Requires management access to the salon (owner or assigned admin).")
    @GetMapping("/{salonId}/sibling-salons")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<List<SiblingSalonOption>> getSiblingSalons(@PathVariable UUID salonId) {
        return ApiResponse.ok(salonService.getSiblingSalons(salonId));
    }

    /**
     * Masters actually bookable for {@code serviceDefId} within {@code salonId} — booking-flow
     * master selection (Phase 23.x), distinct from {@link #getMastersBySalon} (the salon-profile
     * roster). Public/unauthenticated, matching the existing {@code GET /{salonId}/masters} and
     * {@code GET /masters/{masterId}/services} read config in {@code SecurityConfig}: clients
     * browse and pick a master before authenticating to book.
     *
     * <p>Filtering (active + actively assigned to the service + schedule-usable) is entirely
     * delegated to {@link BookingMasterService} — see its Javadoc for why a master with no usable
     * schedule must never appear here.
     */
    @GetMapping("/{salonId}/services/{serviceDefId}/masters")
    public ApiResponse<List<BookableMasterResponse>> getBookableMasters(
            @PathVariable UUID salonId,
            @PathVariable UUID serviceDefId
    ) {
        return ApiResponse.ok(bookingMasterService.getBookableMasters(salonId, serviceDefId));
    }

    @DeleteMapping("/{salonId}")
    @PreAuthorize("hasRole('SALON_OWNER') and @authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<Void> deactivateSalon(
            @PathVariable UUID salonId,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        salonService.deactivateSalon(ownerId, salonId);
        return ResponseEntity.noContent().build();
    }

    // SALON_OWNER may remove any admin from a salon they own; SALON_ADMIN may remove another
    // admin from their own salon only. canManageSalon enforces the salon-scoping half of that;
    // adminBelongsToSalon additionally confirms #userId is actually a SALON_ADMIN assigned to
    // #salonId — without it a caller with management access to Salon A could probe arbitrary
    // user UUIDs and distinguish "exists elsewhere" from "not an admin" via 403 vs 404 (IDOR).
    @DeleteMapping("/{salonId}/admins/{userId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') "
            + "and @authz.canManageSalon(authentication, #salonId) "
            + "and @authz.adminBelongsToSalon(#userId, #salonId)")
    public ResponseEntity<Void> removeAdmin(
            @PathVariable UUID salonId,
            @PathVariable UUID userId,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        salonService.removeAdmin(actorId, salonId, userId);
        return ResponseEntity.noContent().build();
    }

    // Phase 21.3 — rotate a SALON_ADMIN from #salonId to another salon owned by the SAME owner.
    // canManageSalon enforces salon-scoping for the source salon (owner-of-any-salon or
    // admin-of-this-salon); adminBelongsToSalon confirms #userId is actually a SALON_ADMIN
    // assigned to #salonId (same IDOR guard as removeAdmin — see its Javadoc). The destination
    // salon's same-owner requirement is NOT expressible in this SpEL gate (it depends on the
    // request body, not a path variable) — SalonService.rotateAdmin enforces it via
    // AuthorizationService.salonsShareOwner before mutating anything.
    @PatchMapping("/{salonId}/admins/{userId}/salon")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') "
            + "and @authz.canManageSalon(authentication, #salonId) "
            + "and @authz.adminBelongsToSalon(#userId, #salonId)")
    public ResponseEntity<ApiResponse<SalonAdminResponse>> rotateAdmin(
            @PathVariable UUID salonId,
            @PathVariable UUID userId,
            @Valid @RequestBody RotateAdminRequest request,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        SalonAdminResponse response =
                salonService.rotateAdmin(actorId, salonId, userId, request.destinationSalonId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // SALON_OWNER/SALON_ADMIN manage their salon's outbound invites. canManageSalon enforces the
    // same salon-scoping as updateSalon/inviteMaster above (owner-of-this-salon or
    // admin-assigned-to-this-salon); a caller without access is denied before either method runs.
    @Operation(summary = "List the salon's invite history",
            description = """
                    Returns every invite the salon has ever dispatched — pending, accepted, \
                    expired and cancelled alike — newest-first by createdAt, under \
                    `data.invites`. `status` is derived per row at read time and is one of \
                    PENDING, ACCEPTED, EXPIRED, CANCELLED; only a PENDING invite can be \
                    cancelled. The token value is never exposed. Capped at the 200 most recent \
                    invites; `data.truncated` is true when older invites exist beyond that cap \
                    and are not included.""")
    @GetMapping("/{salonId}/invites")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<SalonInviteHistoryResponse> listSalonInvites(
            @PathVariable UUID salonId
    ) {
        return ApiResponse.ok(salonService.listSalonInvites(salonId));
    }

    @Operation(summary = "Cancel a pending invite",
            description = """
                    Revokes an invite that is still PENDING. The row is kept as history, \
                    relabelled CANCELLED. Any invite that is not PENDING — already accepted, \
                    already cancelled, superseded by a re-invite, or simply lapsed — returns 404, \
                    as does an invite belonging to another salon: a non-pending invite must never \
                    have its recorded outcome rewritten.""")
    @DeleteMapping("/{salonId}/invites/{inviteId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<Void> cancelInvite(
            @PathVariable UUID salonId,
            @PathVariable UUID inviteId,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        salonService.cancelInvite(actorId, salonId, inviteId);
        return ResponseEntity.noContent().build();
    }
}
