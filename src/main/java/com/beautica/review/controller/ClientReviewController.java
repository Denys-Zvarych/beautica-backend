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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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
     *
     * <p><b>B547 (REV-11, REV-D06)</b> — the booking's {@code SALON_OWNER} / {@code SALON_ADMIN}, the
     * {@code INDEPENDENT_MASTER} on their own booking, or the {@code SALON_MASTER} who performed it may rate the
     * client (reverses phase 355's master exclusion). {@code @authz.canReviewClient} checks the actor's authority over
     * THIS booking, so a colleague master or another salon's owner/admin gets 403. One salon-side rating per booking:
     * the first writer wins, the second gets 409 (REV-42, {@code uq_client_reviews_booking}).
     */
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER','SALON_MASTER') "
            + "and @authz.canReviewClient(authentication, #request.bookingId)")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientReviewResponse>> create(
            @Valid @RequestBody CreateClientReviewRequest request,
            Authentication auth) {
        ClientReviewResponse response = clientReviewService.create(AuthenticationUtils.userId(auth), request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    /**
     * B570 (REV-45, REV-D12) — the provider deletes their own client rating, stars and comment. Role-only gate here;
     * the {@code own} check runs in the service after its single locked load (Anti-Bug §D), and anything not own —
     * unknown, another provider's, hidden or already deleted — is the same 404.
     */
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER','SALON_MASTER')")
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> deleteOwn(@PathVariable UUID reviewId, Authentication auth) {
        clientReviewService.deleteOwn(reviewId, AuthenticationUtils.userId(auth), AuthenticationUtils.role(auth));
        return ResponseEntity.noContent().build();
    }
}
