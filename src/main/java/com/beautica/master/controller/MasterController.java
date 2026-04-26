package com.beautica.master.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.dto.ScheduleExceptionRequest;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.service.MasterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/masters")
@RequiredArgsConstructor
@Validated
public class MasterController {

    private final MasterService masterService;

    @GetMapping("/{masterId}")
    public ApiResponse<MasterDetailResponse> getMasterDetail(@PathVariable UUID masterId) {
        return ApiResponse.ok(masterService.getMasterDetail(masterId));
    }

    @GetMapping("/by-salon/{salonId}")
    public ApiResponse<PageResponse<MasterSummaryResponse>> getMastersBySalon(
            @PathVariable UUID salonId,
            Pageable pageable
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

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token) {
            return (UUID) token.getDetails();
        }
        throw new ForbiddenException("Not authenticated");
    }
}
