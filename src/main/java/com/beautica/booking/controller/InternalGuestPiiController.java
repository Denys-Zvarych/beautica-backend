package com.beautica.booking.controller;

import com.beautica.booking.dto.GuestPiiEraseRequest;
import com.beautica.booking.dto.GuestPiiEraseResponse;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Support-only guest-PII erase (phase 373 D9). Guarded by {@code InternalApiKeyFilter}
 * ({@code X-Internal-Key} on {@code /api/v1/internal/**}); no role, no UI. Support verifies the
 * requester controls the number before calling (privacy@ runbook).
 *
 * <p>Phase 402: the erase also sets the author label of the phone's guest reviews to «Гість» (rating and text stay)
 * skips their pending review invites and expires their already-sent ones (the SMS link → 404). It does NOT delete the phone's {@code sms_opt_outs} row (D2): the opt-out
 * is the guest's own wish, stored only as a phone hash, and is kept indefinitely. It is removed only when the support
 * ticket explicitly asks for it — a separate, manual support action, never a side effect of this endpoint
 * ({@code docs/release/moderation-runbook.md} § «Зняти відмову від SMS-відгуків»).
 */
@RestController
@RequestMapping("/api/v1/internal/guest-pii")
@RequiredArgsConstructor
public class InternalGuestPiiController {

    private final GuestPiiAnonymisationService anonymisationService;

    @PostMapping("/erase")
    public ResponseEntity<ApiResponse<GuestPiiEraseResponse>> erase(
            @Valid @RequestBody GuestPiiEraseRequest request) {
        GuestPiiEraseResponse response =
                GuestPiiEraseResponse.from(anonymisationService.eraseByPhone(request.phone(), request.ticketRef()));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
