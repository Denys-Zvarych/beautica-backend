package com.beautica.booking.controller;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.service.GuestBookingService;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.master.dto.MasterWorkingDayResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.beautica.booking.controller.PublicBookingController.GONE_DESCRIPTION;

/**
 * Phases 392/393 — the public (guest, no-account) SALON booking routes behind {@code beautica.app/book/s/{slug}}:
 * per-master availability, per-master bookable days and the per-master booking create, keyed by the SALON's
 * public slug plus the master's id (from {@code GET /book/s/{slug}/info}'s {@code serviceCoverage} / roster).
 * Never keyed by a master booking slug: salon masters have no personal share link (locked rule).
 *
 * <p>Each route is the salon twin of a {@link PublicBookingController} route and delegates to the same
 * {@link GuestBookingService} core: identical parameters, validation, guest window, error matrix, throttle
 * buckets (the {@code AuthRateLimitFilter} matches these paths by the same {@code /api/v1/book/} prefix and
 * {@code /availability}, {@code /bookable-days}, {@code /booking} suffixes, incl. the span-weighted
 * bookable-days cost) and 410 for a retired salon slug. A master of another salon, an inactive master, or a
 * service the master does not perform → one indistinguishable 404.
 *
 * <p>Security: the GETs are covered by {@code GET /api/v1/book/**} permitAll; the POST by its own precise
 * matcher {@code POST /api/v1/book/s/*}{@code /masters/*}{@code /booking} in {@code SecurityConfig} (guest JWT
 * validated in the service, as on the master route). HTTP only — no logic here.
 */
@RestController
@RequestMapping("/api/v1/book/s")
@RequiredArgsConstructor
@Validated
public class PublicSalonBookingController {

    private final GuestBookingService guestBookingService;

    /**
     * Phase 392 — free slots of master {@code masterId} of the salon on {@code date}; the twin of
     * {@code GET /book/{slug}/availability} ({@code serviceId} repeatable, N&gt;1 = one back-to-back visit).
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Available slots"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/{slug}/masters/{masterId}/availability")
    public ResponseEntity<List<AvailableSlotResponse>> availability(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @PathVariable UUID masterId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("serviceId")
            @NotEmpty(message = "at least one serviceId is required")
            @Size(max = SlotCalculationService.MAX_SERVICES_PER_VISIT,
                    message = "at most 10 services can be booked in a single visit")
            List<UUID> serviceId) {
        return ResponseEntity.ok(guestBookingService.salonAvailableSlots(slug, masterId, date, serviceId));
    }

    /**
     * Phase 392 — per-date bookability of master {@code masterId} of the salon over {@code [from, to]}; the twin
     * of {@code GET /book/{slug}/bookable-days} (same window {@code [today, today + 60]} Kyiv, same core).
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Per-date bookability"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/{slug}/masters/{masterId}/bookable-days")
    public ResponseEntity<List<MasterWorkingDayResponse>> bookableDays(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @PathVariable UUID masterId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam("serviceId")
            @NotEmpty(message = "at least one serviceId is required")
            @Size(max = SlotCalculationService.MAX_SERVICES_PER_VISIT,
                    message = "at most 10 services can be booked in a single visit")
            List<UUID> serviceId) {
        return ResponseEntity.ok(guestBookingService.salonBookableDays(slug, masterId, from, to, serviceId));
    }

    /**
     * Phase 393 — creates the auto-confirmed guest booking (or multi-service visit) with master
     * {@code masterId} of the salon; the twin of {@code POST /book/{slug}/booking} (same body and guest token).
     * One call per master: a visit split across masters is several calls with the same guest token.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Guest booking created"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @PostMapping("/{slug}/masters/{masterId}/booking")
    public ResponseEntity<GuestBookingResponse> book(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @PathVariable UUID masterId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody GuestBookingRequest request) {
        GuestBookingResponse response =
                guestBookingService.createSalonGuestBooking(authorization, slug, masterId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
