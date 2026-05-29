package com.beautica.master.controller;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.master.dto.AvailableSlotsResponse;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.dto.MasterPublicProfileResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.dto.ScheduleExceptionRequest;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.service.MasterService;
import com.beautica.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/masters")
@RequiredArgsConstructor
@Validated
public class MasterController {

    private final MasterService masterService;
    private final SlotCalculationService slotCalculationService;
    private final UserService userService;

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('SALON_MASTER', 'INDEPENDENT_MASTER')")
    public ApiResponse<MasterDetailResponse> getMyProfile(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ApiResponse.ok(masterService.getMyMasterDetail(userId));
    }

    /**
     * Public master profile — no authentication required.
     * Address fields (street, buildingNo, locationNote) are masked on this endpoint.
     * The authenticated GET /independent-masters/me endpoint returns the full address.
     */
    @GetMapping("/{masterId}")
    public ApiResponse<MasterDetailResponse> getMasterDetail(@PathVariable UUID masterId) {
        MasterDetailResponse detail = masterService.getMasterDetail(masterId);
        // Mask home-address triple on the public (unauthenticated) endpoint.
        // Full address is only returned on the authenticated GET /me endpoint.
        MasterDetailResponse publicDetail = new MasterDetailResponse(
                detail.masterId(), detail.firstName(), detail.lastName(), detail.city(),
                null, null, null, // street, buildingNo, locationNote — masked for public access
                detail.bio(), detail.instagram(),
                detail.avatarUrl(), detail.avgRating(), detail.reviewCount(),
                detail.masterType(), detail.salon(), detail.workingHours()
        );
        return ApiResponse.ok(publicDetail);
    }

    @GetMapping("/by-salon/{salonId}")
    public ApiResponse<PageResponse<MasterSummaryResponse>> getMastersBySalon(
            @PathVariable UUID salonId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        var page = masterService.getMastersByPage(salonId, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    @PatchMapping("/{masterId}/working-hours")
    @PreAuthorize("@authz.canManageMasterSchedule(authentication, #masterId)")
    public ApiResponse<List<WorkingHoursResponse>> upsertWorkingHours(
            @PathVariable UUID masterId,
            @Valid @RequestBody @Size(max = 7) List<WorkingHoursRequest> requests,
            Authentication authentication
    ) {
        UUID actorId = extractUserId(authentication);
        return ApiResponse.ok(masterService.upsertWorkingHours(actorId, masterId, requests));
    }

    @PostMapping("/{masterId}/schedule-exceptions")
    @PreAuthorize("@authz.canManageMasterSchedule(authentication, #masterId)")
    public ResponseEntity<ApiResponse<Void>> addScheduleException(
            @PathVariable UUID masterId,
            @Valid @RequestBody ScheduleExceptionRequest request,
            Authentication authentication
    ) {
        UUID actorId = extractUserId(authentication);
        masterService.addScheduleException(actorId, masterId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(null));
    }

    @DeleteMapping("/{masterId}/schedule-exceptions/{date}")
    @PreAuthorize("@authz.canManageMasterSchedule(authentication, #masterId)")
    public ResponseEntity<ApiResponse<Void>> removeScheduleException(
            @PathVariable UUID masterId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication
    ) {
        UUID actorId = extractUserId(authentication);
        masterService.removeScheduleException(actorId, masterId, date);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{masterId}")
    @PreAuthorize("@authz.canManageMaster(authentication, #masterId)")
    public ResponseEntity<ApiResponse<Void>> deactivateMaster(
            @PathVariable UUID masterId,
            Authentication authentication
    ) {
        UUID actorId = extractUserId(authentication);
        masterService.deactivateMaster(actorId, masterId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/calendar")
    @PreAuthorize("hasAnyRole('SALON_MASTER', 'INDEPENDENT_MASTER')")
    public ApiResponse<PageResponse<BookingResponse>> getMasterCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication
    ) {
        long daysBetween = ChronoUnit.DAYS.between(from, to);
        if (daysBetween < 0) {
            throw new BusinessException("'from' must not be after 'to'");
        }
        if (daysBetween > 31) {
            throw new BusinessException("Date range cannot exceed 31 days");
        }
        // Bounds guard: reject extreme historical or future dates to prevent Caffeine cache-key flooding.
        // LocalDate.now() is acceptable here — this is a controller sanity check, not business logic.
        if (from.isBefore(LocalDate.now().minusYears(1))) {
            throw new BusinessException("Date range out of allowed bounds");
        }
        if (to.isAfter(LocalDate.now().plusYears(2))) {
            throw new BusinessException("Date range out of allowed bounds");
        }
        UUID actorId = extractUserId(authentication);
        Master actor = masterService.getMasterByUserId(actorId);
        Page<BookingResponse> page = masterService.getMasterCalendar(actor.getId(), from, to, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    @GetMapping("/{masterId}/slots")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AvailableSlotsResponse> getAvailableSlots(
            @PathVariable UUID masterId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam UUID serviceId
    ) {
        List<AvailableSlotResponse> slots = slotCalculationService.getAvailableSlots(masterId, date, serviceId);
        return ApiResponse.ok(new AvailableSlotsResponse(date, slots));
    }

    /**
     * Updates the authenticated salon master's public profile fields.
     *
     * <p>Mirrors the {@code PATCH /api/v1/independent-masters/me/profile} endpoint
     * that serves {@code INDEPENDENT_MASTER}. {@code SALON_MASTER} has no other
     * self-service route to update bio, phone, or Instagram handle.
     *
     * <p>Delegates to {@link UserService#updateMasterProfile} — the same service
     * method used by the independent master path. The service has no internal role
     * assertion blocking {@code SALON_MASTER}, and sharing the implementation is
     * intentional: the DB columns written ({@code phone_number}, {@code bio},
     * {@code instagram}) are role-agnostic.
     *
     * @param request        validated profile update body (phone required; bio, instagram optional)
     * @param authentication Spring Security context — carries the user UUID
     * @return updated public-profile fields wrapped in {@link ApiResponse}
     */
    @PatchMapping("/me/profile")
    @PreAuthorize("hasRole('SALON_MASTER')")
    public ResponseEntity<ApiResponse<MasterPublicProfileResponse>> updateMyProfile(
            @Valid @RequestBody MasterProfileUpdateRequest request,
            Authentication authentication
    ) {
        UUID userId = extractUserId(authentication);
        MasterPublicProfileResponse updated = userService.updateMasterProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        throw new ForbiddenException("Access denied");
    }
}
