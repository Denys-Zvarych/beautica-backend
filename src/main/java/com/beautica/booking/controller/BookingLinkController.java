package com.beautica.booking.controller;

import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.booking.service.BookingLinkService;
import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 375 — the caller's shareable public booking links. HTTP only: role gate here (D2 — every
 * provider role, never {@code CLIENT}), link resolution, kill-switch and lazy slug issue in
 * {@link BookingLinkService}.
 */
@RestController
@RequestMapping("/api/v1/booking-links")
@RequiredArgsConstructor
@Tag(name = "Booking links", description = "Shareable public booking-page links (beautica.app/book/{slug})")
public class BookingLinkController {

    private final BookingLinkService bookingLinkService;

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('INDEPENDENT_MASTER', 'SALON_OWNER', 'SALON_ADMIN', 'SALON_MASTER')")
    @Operation(
            summary = "My shareable public booking links",
            description = """
                    INDEPENDENT_MASTER and SALON_OWNER get their own MASTER link (a legacy master's slug \
                    is issued on the first call and never changes). SALON_ADMIN and SALON_MASTER get \
                    no master link. When sharing is switched off server-side, shareEnabled is false and \
                    links is empty.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Links"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Not authenticated", content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "CLIENT role", content = @Content())
    })
    public ApiResponse<BookingLinksResponse> getMine(Authentication authentication) {
        return ApiResponse.ok(bookingLinkService.getMyLinks(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication)));
    }
}
