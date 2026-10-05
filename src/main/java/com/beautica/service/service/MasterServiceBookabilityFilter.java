package com.beautica.service.service;

import com.beautica.booking.service.BookingMasterService;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.common.security.AuthorizationService;
import com.beautica.service.dto.MasterServiceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Narrows {@code GET /masters/{masterId}/services} to the services a client can actually book —
 * the strict free-slot verdict ({@link BookingMasterService#getBookableAssignmentIds}, the same
 * {@code filterBookableAssignmentsBatch} the salon catalogue and roster use), so a master with no
 * schedule shows no bookable menu and a stale cross-owner assignment never surfaces.
 *
 * <p><b>Management view stays unfiltered.</b> The same route is read by management screens — the
 * master's own profile, and the salon owner/admin's staff-member screen — which must see what is
 * configured even when it is not yet bookable (that is exactly when the owner needs to fix it).
 * Those callers are recognised by {@link AuthorizationService#canReadMasterSchedule}: the owning
 * master (any type) or the master's salon owner/admin. Anonymous callers and CLIENTs (rejected by
 * that predicate's role fast path, no DB hit) get the filtered view.
 *
 * <p>A separate bean invoked from the controller, lexically OUTSIDE the
 * {@code @Cacheable("masterServices")} read — mirroring {@link MasterServiceFavoriteDecorator}: the
 * cached list is caller-agnostic and long-lived (10 min), while this verdict is per-viewer and
 * flips with every booking/schedule write, so it must never enter that cache.
 */
@Component
@RequiredArgsConstructor
public class MasterServiceBookabilityFilter {

    private final BookingMasterService bookingMasterService;
    private final AuthorizationService authorizationService;

    /**
     * Returns {@code services} untouched for an empty list or a management viewer; otherwise a new
     * list holding only the rows whose assignment passes the strict verdict.
     */
    public List<MasterServiceResponse> forViewer(
            UUID masterId, List<MasterServiceResponse> services, Authentication authentication) {
        if (services.isEmpty() || isManagementViewer(authentication, masterId)) {
            return services;
        }
        Set<UUID> bookableAssignmentIds = bookingMasterService.getBookableAssignmentIds(masterId);
        return services.stream()
                .filter(service -> bookableAssignmentIds.contains(service.id()))
                .toList();
    }

    private boolean isManagementViewer(Authentication authentication, UUID masterId) {
        return AuthenticationUtils.userIdOrNull(authentication) != null
                && authorizationService.canReadMasterSchedule(authentication, masterId);
    }
}
