package com.beautica.review.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.review.dto.ClientReviewResponse;
import com.beautica.review.dto.CreateClientReviewRequest;
import com.beautica.review.service.ClientReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-authored review of the CLIENT after a completed booking (Phase 27.5 — REVERSES the
 * previously deferred/out-of-scope status of master&rarr;client reviews). Thin controller — parse,
 * delegate, wrap; all business logic lives in {@link ClientReviewService}.
 */
@RestController
@RequestMapping("/api/v1/client-reviews")
@RequiredArgsConstructor
public class ClientReviewController {

    private final ClientReviewService clientReviewService;

    /**
     * Role-only fast path plus the same {@code can*} SpEL ownership check the sibling
     * decline/complete/reschedule provider actions use ({@code @authz.canReviewClient}), backed by
     * the entity-based {@code enforceCanReviewClient} re-check inside the service after its own
     * load — the established double-check pattern for booking provider actions in this codebase
     * (Anti-Bug §D), not an accidental duplication.
     */
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER') "
            + "and @authz.canReviewClient(authentication, #request.bookingId)")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientReviewResponse>> create(
            @Valid @RequestBody CreateClientReviewRequest request,
            Authentication auth) {
        ClientReviewResponse response = clientReviewService.create(AuthenticationUtils.userId(auth), request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }
}
