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
     *
     * <p><b>Phase 316 — {@code SALON_MASTER} joins the role list, and ONLY here.</b> This is the
     * one booking write the read-only role may perform, and the role gate alone does not grant it:
     * {@code @authz.canReviewClient} admits a salon master solely on the booking whose performing
     * master they are ({@code AuthorizationService#isPerformingMasterOfBooking}), so a master
     * pointing this endpoint at a colleague's booking — or at another salon's — still gets 403.
     * The sibling provider actions ({@code /decline}, {@code /not-complete}, {@code /complete},
     * {@code /reschedule}) keep their {@code SALON_MASTER}-free role lists AND their fast-reject
     * inside {@code canCancelBooking}/{@code canCompleteBooking}/{@code canRescheduleBooking}; do
     * not "align" them with this one.
     *
     * <p><b>Phase 320 — {@code SALON_ADMIN} LEAVES the role list; {@code SALON_OWNER} stays.</b>
     * Locked product decision: "salon owner or salon admin can complete the booking, and after it
     * only salon master can leave the feedback". {@code @authz.canReviewClient} is now the single
     * term {@code AuthorizationService#isPerformingMasterOfBooking}, so an admin could never clear
     * it: {@code MasterType} is {@code &#123;SALON_MASTER, INDEPENDENT_MASTER, SALON_OWNER&#125;} —
     * there is no admin master type, so no {@code masters.user_id} can be a {@code SALON_ADMIN}
     * user. Dropping the role is therefore provably not a narrowing beyond intent; it just moves
     * the rejection from a 403-after-a-DB-read to a 403 before one.
     *
     * <p>{@code SALON_OWNER} must NOT be dropped alongside it, and the asymmetry is deliberate:
     * {@code MasterService#createMasterForOwner} builds an owner-as-master row
     * ({@code .user(owner).masterType(SALON_OWNER)}), so an owner who personally performs a visit
     * IS the {@code masters.user_id} the SpEL compares against. Removing the role here would 403
     * them on their own client — the opposite of the decision. Owner and admin alike keep {@code
     * /complete} and every other closing action; only the review is narrowed.
     */
    @PreAuthorize("hasAnyRole('SALON_OWNER','INDEPENDENT_MASTER','SALON_MASTER') "
            + "and @authz.canReviewClient(authentication, #request.bookingId)")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientReviewResponse>> create(
            @Valid @RequestBody CreateClientReviewRequest request,
            Authentication auth) {
        ClientReviewResponse response = clientReviewService.create(AuthenticationUtils.userId(auth), request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }
}
