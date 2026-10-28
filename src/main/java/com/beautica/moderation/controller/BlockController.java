package com.beautica.moderation.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.moderation.dto.BlockResponse;
import com.beautica.moderation.dto.CreateBlockRequest;
import com.beautica.moderation.service.ClientBlockFacade;
import com.beautica.moderation.service.ClientBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 397 — a provider blocks a client. Role gate here (CLIENT / SALON_MASTER → 403); booking scope, target
 * derivation, idempotency and the per-user POST/DELETE rate limit (429) live in {@link ClientBlockService}; a new
 * block's future-booking cancellation (phase 398) in {@link ClientBlockFacade}.
 */
@RestController
@RequestMapping("/api/v1/blocks")
@RequiredArgsConstructor
public class BlockController {

    private static final String BLOCKER_ROLES = "hasAnyRole('INDEPENDENT_MASTER','SALON_OWNER','SALON_ADMIN')";

    private final ClientBlockService clientBlockService;
    private final ClientBlockFacade clientBlockFacade;

    @Operation(summary = "Block the client of a booking",
            description = """
                    Blocks the client of a booking in the caller's scope: an independent master blocks for \
                    themselves, a salon owner/admin for the whole salon. A registered client is blocked by account \
                    only, a link guest by the phone verified at booking time; the two are never linked. A new block \
                    also cancels (DECLINED) every future confirmed booking of that client in the scope; the client \
                    gets only the ordinary provider-cancel notice, never a block notice. cancelledBookings carries \
                    the count of cancelled visits (a multi-service visit counts once). Idempotent: an existing \
                    block of the same client in the same scope returns 200 and cancels nothing. Shares a per-user budget with DELETE (429 with Retry-After).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Block created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Already blocked"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation error, or a walk-in booking (no verified identity)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is a client or a salon master"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Booking not found or not in the caller's scope (incl. an inactive salon)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "Per-user block/unblock budget exhausted")
    })
    @PostMapping
    @PreAuthorize(BLOCKER_ROLES)
    public ResponseEntity<ApiResponse<BlockResponse>> block(
            @Valid @RequestBody CreateBlockRequest request,
            Authentication authentication) {
        ClientBlockService.CreateBlockResult result = clientBlockFacade.block(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication),
                request.bookingId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.block()));
    }

    @Operation(summary = "List blocked clients",
            description = "The caller's blocks, newest first (salon owner/admin: the salon's). At most 500 rows.")
    @GetMapping
    @PreAuthorize(BLOCKER_ROLES)
    public ApiResponse<List<BlockResponse>> list(Authentication authentication) {
        return ApiResponse.ok(clientBlockService.list(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication)));
    }

    @Operation(summary = "Unblock a client",
            description = "Idempotent: 204 also when the block does not exist or is outside the caller's scope. "
                    + "Shares the per-user budget with POST (429 with Retry-After).")
    @DeleteMapping("/{blockId}")
    @PreAuthorize(BLOCKER_ROLES)
    public ResponseEntity<Void> unblock(@PathVariable UUID blockId, Authentication authentication) {
        clientBlockService.unblock(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication), blockId);
        return ResponseEntity.noContent().build();
    }
}
