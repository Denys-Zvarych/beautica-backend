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
 * Phase 375 + 376 — the caller's shareable public booking links (MASTER {@code /book/{slug}}, SALON
 * {@code /book/s/{slug}}). HTTP only: role gate here (D2 — every provider role, never {@code CLIENT}),
 * link resolution, kill-switch and lazy slug issue in {@link BookingLinkService}.
 */
@RestController
@RequestMapping("/api/v1/booking-links")
@RequiredArgsConstructor
@Tag(name = "Booking links", description = "Shareable public booking-page links: master pages (beautica.app/book/{slug}) and salon pages (beautica.app/book/s/{slug})")
public class BookingLinkController {

    private final BookingLinkService bookingLinkService;

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('INDEPENDENT_MASTER', 'SALON_OWNER', 'SALON_ADMIN', 'SALON_MASTER')")
    @Operation(
            summary = "My shareable public booking links",
            description = """
                    INDEPENDENT_MASTER and SALON_OWNER get their own MASTER link \
                    (https://beautica.app/book/{slug}). SALON_OWNER additionally gets one SALON link \
                    (https://beautica.app/book/s/{slug}) per active owned salon, ordered by salon name; \
                    SALON_ADMIN gets the SALON link of the active salon it is bound to. Inactive \
                    salons and inactive master rows yield no link. SALON_MASTER gets no links. A legacy \
                    master's or salon's slug is issued on the first call and never changes, not even on \
                    rename. bookable reports whether the public page can take a booking today (for a \
                    salon: at least one bookable master). When sharing is switched off server-side, \
                    shareEnabled is false and links is empty. 409 when a slug issue cannot acquire its \
                    lock in time (retry).""",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Links"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Not authenticated", content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "CLIENT role", content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Slug issue lock contended past its timeout; retry",
                    content = @Content())
    })
    public ApiResponse<BookingLinksResponse> getMine(Authentication authentication) {
        return ApiResponse.ok(bookingLinkService.getMyLinks(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication)));
    }
}
