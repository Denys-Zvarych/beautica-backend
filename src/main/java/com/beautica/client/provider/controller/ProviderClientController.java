package com.beautica.client.provider.controller;

import com.beautica.client.provider.dto.ClientCardResponse;
import com.beautica.client.provider.dto.ClientListItemResponse;
import com.beautica.client.provider.dto.ClientVisitResponse;
import com.beautica.client.provider.service.ClientRef;
import com.beautica.client.provider.service.ClientScope;
import com.beautica.client.provider.service.ProviderClientScopeResolver;
import com.beautica.client.provider.service.ProviderClientService;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.security.AuthenticationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
