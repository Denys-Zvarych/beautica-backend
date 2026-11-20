package com.beautica.client.provider.controller;

import com.beautica.client.provider.dto.ClientListItemResponse;
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

    private ApiResponse<PageResponse<ClientListItemResponse>> page(ClientScope scope, String q, Pageable pageable) {
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            throw new BusinessException("Page number out of range");
        }
        // Fixed order (latest activity first): page + size only, any `sort` is dropped.
        Pageable bounded = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
        Page<ClientListItemResponse> result = providerClientService.listClients(scope, q, bounded);
        return ApiResponse.ok(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages()));
    }
}
