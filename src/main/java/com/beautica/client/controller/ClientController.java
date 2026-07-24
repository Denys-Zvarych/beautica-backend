package com.beautica.client.controller;

import com.beautica.client.dto.PassportResponse;
import com.beautica.client.dto.TimelineItemResponse;
import com.beautica.client.service.ClientPassportService;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.security.AuthenticationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only, principal-scoped BEAUTY PASSPORT + BEAUTY TIMELINE endpoints (Phase 19.5).
 *
 * <p>Both endpoints derive everything from the authenticated client's own COMPLETED
 * booking history. The client id always comes from the security principal — there is no
 * {@code clientId} path/query parameter, so one client can never read another's passport.
 */
@RestController
@RequestMapping("/api/v1/clients/me")
@RequiredArgsConstructor
public class ClientController {

    /**
     * Hard ceiling on the page number to stop giant OFFSET scans (Anti-Bug §J).
     * The global {@code spring.data.web.pageable.max-page-size} caps the page size.
     */
    private static final int MAX_PAGE_NUMBER = 1000;

    private final ClientPassportService clientPassportService;

    @GetMapping("/passport")
    @PreAuthorize("hasRole('CLIENT')")
    public ApiResponse<PassportResponse> getPassport(Authentication auth) {
        return ApiResponse.ok(clientPassportService.getPassport(AuthenticationUtils.userId(auth)));
    }

    @GetMapping("/timeline")
    @PreAuthorize("hasRole('CLIENT')")
    public ApiResponse<PageResponse<TimelineItemResponse>> getTimeline(
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth
    ) {
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            pageable = PageRequest.of(MAX_PAGE_NUMBER, pageable.getPageSize(), pageable.getSort());
        }
        return ApiResponse.ok(clientPassportService.getTimeline(AuthenticationUtils.userId(auth), pageable));
    }
}
