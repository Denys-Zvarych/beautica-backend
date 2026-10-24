package com.beautica.booking.controller;

import com.beautica.booking.dto.BookingLinkMutationRequest;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.booking.service.BookingLinkRetirementService;
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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 375 + 376 — the caller's shareable public booking links (MASTER {@code /book/{slug}}, SALON
 * {@code /book/s/{slug}}). HTTP only: role gate here (D2 — every provider role, never {@code CLIENT}),
 * link resolution, kill-switch and lazy slug issue in {@link BookingLinkService}.
 *
 * <p>Phase 378 — regenerate / revoke (four routes): role-only gate here (D6, Anti-Bug §D canonical split);
 * stored-row ownership / membership, kill-switch, compare-and-swap and tombstone in
 * {@link BookingLinkRetirementService}. Throttled per user by {@code BookingRateLimitFilter} (D10).
 */
@RestController
@RequestMapping("/api/v1/booking-links")
@RequiredArgsConstructor
@Tag(name = "Booking links", description = "Shareable public booking-page links: master pages (beautica.app/book/{slug}) and salon pages (beautica.app/book/s/{slug})")
public class BookingLinkController {

    private final BookingLinkService bookingLinkService;
    private final BookingLinkRetirementService bookingLinkRetirementService;

    private static final String MUTATION_NOTES = """
             Body: {"currentSlug": "<the slug the app shows>"} or {"currentSlug": null} when the link \
            is REVOKED (optimistic concurrency). The retired slug is never issued to anyone again. \
            404 when sharing is switched off server-side or the master / salon is not active; \
            409 when currentSlug is stale (refresh and retry) or no unique slug could be allocated; \
            429 after 10 regenerate/revoke calls per user per hour (all four routes share the budget).""";

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('INDEPENDENT_MASTER', 'SALON_OWNER', 'SALON_ADMIN', 'SALON_MASTER')")
    @Operation(
            summary = "My shareable public booking links",
            description = """
                    INDEPENDENT_MASTER gets its own MASTER link (https://beautica.app/book/{slug}). \
                    SALON_OWNER gets ONLY SALON links (https://beautica.app/book/s/{slug}), one per \
                    active owned salon, ordered by salon name, and no personal MASTER link; \
                    SALON_ADMIN gets the SALON link of the active salon it is bound to (the same link \
                    the owner shares). Inactive salons and inactive master rows yield no link. \
                    SALON_MASTER gets no links. A legacy \
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

    @PostMapping("/master/regenerate")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    @Operation(summary = "Regenerate my master booking link",
            description = "INDEPENDENT_MASTER only. Retires the current slug (if any) and issues a new one; "
                    + "from a REVOKED link this creates a new active link." + MUTATION_NOTES,
            security = @SecurityRequirement(name = "bearerAuth"))
    @MutationResponses
    public ApiResponse<BookingLinkResponse> regenerateMaster(
            @Valid @RequestBody BookingLinkMutationRequest request, Authentication authentication) {
        return ApiResponse.ok(bookingLinkRetirementService.regenerateMaster(
                AuthenticationUtils.userId(authentication), request.currentSlug()));
    }

    @DeleteMapping("/master")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    @Operation(summary = "Revoke my master booking link",
            description = "INDEPENDENT_MASTER only. Switches the link off: the slug is retired, GET /mine "
                    + "shows status REVOKED and never re-issues it. Idempotent when already revoked "
                    + "(currentSlug null)." + MUTATION_NOTES,
            security = @SecurityRequirement(name = "bearerAuth"))
    @MutationResponses
    public ApiResponse<BookingLinkResponse> revokeMaster(
            @Valid @RequestBody BookingLinkMutationRequest request, Authentication authentication) {
        return ApiResponse.ok(bookingLinkRetirementService.revokeMaster(
                AuthenticationUtils.userId(authentication), request.currentSlug()));
    }

    @PostMapping("/salons/{salonId}/regenerate")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'SALON_ADMIN')")
    @Operation(summary = "Regenerate a salon booking link",
            description = "SALON_OWNER of the salon (any salon they own) or the SALON_ADMIN bound to it; "
                    + "any other salon → 403. Retires the current slug (if any) and issues a new one."
                    + MUTATION_NOTES,
            security = @SecurityRequirement(name = "bearerAuth"))
    @MutationResponses
    public ApiResponse<BookingLinkResponse> regenerateSalon(
            @PathVariable UUID salonId,
            @Valid @RequestBody BookingLinkMutationRequest request, Authentication authentication) {
        return ApiResponse.ok(bookingLinkRetirementService.regenerateSalon(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication),
                salonId, request.currentSlug()));
    }

    @DeleteMapping("/salons/{salonId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'SALON_ADMIN')")
    @Operation(summary = "Revoke a salon booking link",
            description = "SALON_OWNER of the salon or the SALON_ADMIN bound to it; any other salon → 403. "
                    + "Switches the salon link off (status REVOKED, never re-issued by GET /mine). "
                    + "Idempotent when already revoked (currentSlug null)." + MUTATION_NOTES,
            security = @SecurityRequirement(name = "bearerAuth"))
    @MutationResponses
    public ApiResponse<BookingLinkResponse> revokeSalon(
            @PathVariable UUID salonId,
            @Valid @RequestBody BookingLinkMutationRequest request, Authentication authentication) {
        return ApiResponse.ok(bookingLinkRetirementService.revokeSalon(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication),
                salonId, request.currentSlug()));
    }

    /** The documented error responses shared by the four phase-378 mutation routes. */
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated link"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Malformed currentSlug", content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Not authenticated", content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Role, ownership or salon membership", content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Sharing switched off, or no active master / salon",
                    content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Stale currentSlug, or no unique slug available",
                    content = @Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = "Per-user regenerate/revoke budget exhausted",
                    content = @Content())
    })
    @interface MutationResponses {
    }
}
