package com.beautica.client.provider.controller;

import com.beautica.client.provider.dto.ClientCardResponse;
import com.beautica.client.provider.dto.ClientListItemResponse;
import com.beautica.client.provider.dto.ClientListRemovalRequest;
import com.beautica.client.provider.dto.ClientOwnRatingResponse;
import com.beautica.client.provider.dto.ClientVisitResponse;
import com.beautica.client.provider.service.ClientRef;
import com.beautica.client.provider.service.ClientScope;
import com.beautica.client.provider.service.ProviderClientScopeResolver;
import com.beautica.client.provider.service.ProviderClientService;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.security.AuthenticationUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * B507 — the provider's client list (CLI-38). Read-only, HTTP only: parse, authorise, resolve the scope, page. A NEW
 * controller on purpose (a new dependency on an existing one breaks its {@code @WebMvcTest} slice). The salon
 * endpoint is gated by role + {@code @authz.canManageSalon}; {@code /masters/me/clients} by role, then the resolver
 * (active master row, else 403).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProviderClientController {

    /** Silent clamp, like the timeline cap; the global {@code max-page-size} (100) applies first. */
    static final int MAX_PAGE_SIZE = 50;

    /** Anti-Bug §J: hard ceiling on the page number (giant OFFSET scans) — a 400, not a clamp. */
    static final int MAX_PAGE_NUMBER = 1000;

    private final ProviderClientService providerClientService;
    private final ProviderClientScopeResolver scopeResolver;

    @GetMapping("/salons/{salonId}/clients")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<PageResponse<ClientListItemResponse>> listSalonClients(
            @PathVariable UUID salonId,
            @RequestParam(required = false) UUID masterId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        ClientScope scope = scopeResolver.forSalon(salonId, masterId);
        return page(scope, q, pageable);
    }

    @GetMapping("/masters/me/clients")
    @PreAuthorize("hasAnyRole('SALON_MASTER','INDEPENDENT_MASTER')")
    public ApiResponse<PageResponse<ClientListItemResponse>> listMyClients(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication
    ) {
        ClientScope scope = scopeResolver.forMasterMe(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication));
        return page(scope, q, pageable);
    }

    // ── B508: client card + visit history (CLI-42..45) — same @PreAuthorize per family as the list ──

    private static final String SALON_ACCESS =
            "hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)";
    private static final String MASTER_ACCESS = "hasAnyRole('SALON_MASTER','INDEPENDENT_MASTER')";

    @GetMapping("/salons/{salonId}/clients/users/{userId}")
    @PreAuthorize(SALON_ACCESS)
    public ApiResponse<ClientCardResponse> getSalonUserCard(
            @PathVariable UUID salonId, @PathVariable UUID userId, @RequestParam(required = false) UUID masterId) {
        return ApiResponse.ok(providerClientService.getCard(
                scopeResolver.forSalon(salonId, masterId), new ClientRef.Registered(userId)));
    }

    @GetMapping("/salons/{salonId}/clients/guests/{bookingId}")
    @PreAuthorize(SALON_ACCESS)
    public ApiResponse<ClientCardResponse> getSalonGuestCard(
            @PathVariable UUID salonId, @PathVariable UUID bookingId, @RequestParam(required = false) UUID masterId) {
        return ApiResponse.ok(providerClientService.getCard(
                scopeResolver.forSalon(salonId, masterId), new ClientRef.GuestAnchor(bookingId)));
    }

    @GetMapping("/salons/{salonId}/clients/users/{userId}/visits")
    @PreAuthorize(SALON_ACCESS)
    public ApiResponse<PageResponse<ClientVisitResponse>> getSalonUserVisits(
            @PathVariable UUID salonId, @PathVariable UUID userId, @RequestParam(required = false) UUID masterId,
            @PageableDefault(size = 20) Pageable pageable) {
        return visits(scopeResolver.forSalon(salonId, masterId), new ClientRef.Registered(userId), pageable);
    }

    @GetMapping("/salons/{salonId}/clients/guests/{bookingId}/visits")
    @PreAuthorize(SALON_ACCESS)
    public ApiResponse<PageResponse<ClientVisitResponse>> getSalonGuestVisits(
            @PathVariable UUID salonId, @PathVariable UUID bookingId, @RequestParam(required = false) UUID masterId,
            @PageableDefault(size = 20) Pageable pageable) {
        return visits(scopeResolver.forSalon(salonId, masterId), new ClientRef.GuestAnchor(bookingId), pageable);
    }

    @GetMapping("/masters/me/clients/users/{userId}")
    @PreAuthorize(MASTER_ACCESS)
    public ApiResponse<ClientCardResponse> getMyUserCard(@PathVariable UUID userId, Authentication authentication) {
        return ApiResponse.ok(providerClientService.getCard(myScope(authentication), new ClientRef.Registered(userId)));
    }

    @GetMapping("/masters/me/clients/guests/{bookingId}")
    @PreAuthorize(MASTER_ACCESS)
    public ApiResponse<ClientCardResponse> getMyGuestCard(
            @PathVariable UUID bookingId, Authentication authentication) {
        return ApiResponse.ok(providerClientService.getCard(
                myScope(authentication), new ClientRef.GuestAnchor(bookingId)));
    }

    @GetMapping("/masters/me/clients/users/{userId}/visits")
    @PreAuthorize(MASTER_ACCESS)
    public ApiResponse<PageResponse<ClientVisitResponse>> getMyUserVisits(
            @PathVariable UUID userId, @PageableDefault(size = 20) Pageable pageable, Authentication authentication) {
        return visits(myScope(authentication), new ClientRef.Registered(userId), pageable);
    }

    @GetMapping("/masters/me/clients/guests/{bookingId}/visits")
    @PreAuthorize(MASTER_ACCESS)
    public ApiResponse<PageResponse<ClientVisitResponse>> getMyGuestVisits(
            @PathVariable UUID bookingId, @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        return visits(myScope(authentication), new ClientRef.GuestAnchor(bookingId), pageable);
    }

    // ── B526: the viewer's own ratings of a client (CLI-59) — same @PreAuthorize per family as the card ──

    @GetMapping("/salons/{salonId}/clients/users/{userId}/ratings")
    @PreAuthorize(SALON_ACCESS)
    public ApiResponse<PageResponse<ClientOwnRatingResponse>> getSalonUserRatings(
            @PathVariable UUID salonId, @PathVariable UUID userId, @RequestParam(required = false) UUID masterId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ratings(scopeResolver.forSalon(salonId, masterId), new ClientRef.Registered(userId), pageable);
    }

    @GetMapping("/salons/{salonId}/clients/guests/{bookingId}/ratings")
    @PreAuthorize(SALON_ACCESS)
    public ApiResponse<PageResponse<ClientOwnRatingResponse>> getSalonGuestRatings(
            @PathVariable UUID salonId, @PathVariable UUID bookingId, @RequestParam(required = false) UUID masterId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ratings(scopeResolver.forSalon(salonId, masterId), new ClientRef.GuestAnchor(bookingId), pageable);
    }

    @GetMapping("/masters/me/clients/users/{userId}/ratings")
    @PreAuthorize(MASTER_ACCESS)
    public ApiResponse<PageResponse<ClientOwnRatingResponse>> getMyUserRatings(
            @PathVariable UUID userId, @PageableDefault(size = 20) Pageable pageable, Authentication authentication) {
        return ratings(myScope(authentication), new ClientRef.Registered(userId), pageable);
    }

    @GetMapping("/masters/me/clients/guests/{bookingId}/ratings")
    @PreAuthorize(MASTER_ACCESS)
    public ApiResponse<PageResponse<ClientOwnRatingResponse>> getMyGuestRatings(
            @PathVariable UUID bookingId, @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        return ratings(myScope(authentication), new ClientRef.GuestAnchor(bookingId), pageable);
    }

    // ── B564: remove a client from the list / undo (CLI-82..86) — 204; same @PreAuthorize per family as the list ──
    // The salon family keys the salon list (owner + admins share it, D1): no masterId here, the narrowed view reads
    // the same salon row. /masters/me keys the caller's own master row.

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Removed from the list (repeat = removal time moves to now)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Not exactly one of clientId / guestBookingId"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Role not allowed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such client in the caller's list scope (same body as the client card)")
    })
    @PostMapping("/salons/{salonId}/clients/removals")
    @PreAuthorize(SALON_ACCESS)
    public ResponseEntity<Void> removeSalonClient(
            @PathVariable UUID salonId, @Valid @RequestBody ClientListRemovalRequest request,
            Authentication authentication) {
        providerClientService.removeFromList(scopeResolver.forSalon(salonId, null), ref(request),
                AuthenticationUtils.userId(authentication));
        return ResponseEntity.noContent().build();
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Back on the list (idempotent: also when nothing was removed)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Not exactly one of clientId / guestBookingId"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Role not allowed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such client in the caller's list scope (same body as the client card)")
    })
    @PostMapping("/salons/{salonId}/clients/removals/restore")
    @PreAuthorize(SALON_ACCESS)
    public ResponseEntity<Void> restoreSalonClient(
            @PathVariable UUID salonId, @Valid @RequestBody ClientListRemovalRequest request) {
        providerClientService.restoreToList(scopeResolver.forSalon(salonId, null), ref(request));
        return ResponseEntity.noContent().build();
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Removed from the list (repeat = removal time moves to now)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Not exactly one of clientId / guestBookingId"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Role not allowed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such client in the caller's list scope (same body as the client card)")
    })
    @PostMapping("/masters/me/clients/removals")
    @PreAuthorize(MASTER_ACCESS)
    public ResponseEntity<Void> removeMyClient(
            @Valid @RequestBody ClientListRemovalRequest request, Authentication authentication) {
        providerClientService.removeFromList(myScope(authentication), ref(request),
                AuthenticationUtils.userId(authentication));
        return ResponseEntity.noContent().build();
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Back on the list (idempotent: also when nothing was removed)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Not exactly one of clientId / guestBookingId"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Role not allowed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such client in the caller's list scope (same body as the client card)")
    })
    @PostMapping("/masters/me/clients/removals/restore")
    @PreAuthorize(MASTER_ACCESS)
    public ResponseEntity<Void> restoreMyClient(
            @Valid @RequestBody ClientListRemovalRequest request, Authentication authentication) {
        providerClientService.restoreToList(myScope(authentication), ref(request));
        return ResponseEntity.noContent().build();
    }

    /** The body is validated to carry exactly one id (400 otherwise). */
    private static ClientRef ref(ClientListRemovalRequest request) {
        return request.clientId() != null
                ? new ClientRef.Registered(request.clientId())
                : new ClientRef.GuestAnchor(request.guestBookingId());
    }

    private ApiResponse<PageResponse<ClientOwnRatingResponse>> ratings(
            ClientScope scope, ClientRef ref, Pageable pageable) {
        Page<ClientOwnRatingResponse> result = providerClientService.getOwnRatings(scope, ref, bound(pageable));
        return ApiResponse.ok(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages()));
    }

    private ClientScope myScope(Authentication authentication) {
        return scopeResolver.forMasterMe(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication));
    }

    private ApiResponse<PageResponse<ClientVisitResponse>> visits(ClientScope scope, ClientRef ref, Pageable pageable) {
        Page<ClientVisitResponse> result = providerClientService.getVisits(scope, ref, bound(pageable));
        return ApiResponse.ok(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages()));
    }

    /** Fixed order: page + size only (any `sort` is dropped); size clamped to 50, page number > 1000 is a 400. */
    private static Pageable bound(Pageable pageable) {
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            throw new BusinessException("Page number out of range");
        }
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
    }

    private ApiResponse<PageResponse<ClientListItemResponse>> page(ClientScope scope, String q, Pageable pageable) {
        Pageable bounded = bound(pageable);
        Page<ClientListItemResponse> result = providerClientService.listClients(scope, q, bounded);
        return ApiResponse.ok(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages()));
    }
}
