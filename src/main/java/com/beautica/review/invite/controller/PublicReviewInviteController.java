package com.beautica.review.invite.controller;

import com.beautica.auth.filter.VisitorIpResolver;
import com.beautica.auth.turnstile.TurnstileVerifier;
import com.beautica.review.invite.dto.GuestReviewSubmitRequest;
import com.beautica.review.invite.dto.GuestReviewSubmitResponse;
import com.beautica.review.invite.dto.ReviewInviteOptOutRequest;
import com.beautica.review.invite.dto.ReviewInviteResponse;
import com.beautica.review.invite.service.GuestReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 401 — the guest review page's public API ({@code permitAll}; the path token is the credential). Order on a
 * POST (D1): per-IP {@code AuthRateLimitFilter} bucket → {@code @Valid} body → Turnstile (action
 * {@code review_submit}) → {@link GuestReviewService}. Bare bodies, like the other {@code /book} public routes. The
 * token is never logged: it only travels in the path and is hashed inside the service.
 */
@Tag(name = "Guest reviews", description = "Public SMS-invite review page (beautica.app/r/{token})")
@RestController
@RequestMapping("/api/v1/public/review-invites")
@RequiredArgsConstructor
public class PublicReviewInviteController {

    private final GuestReviewService guestReviewService;
    private final TurnstileVerifier turnstileVerifier;
    private final VisitorIpResolver visitorIpResolver;

    @Operation(summary = "Read a review invite", description = "The visit to rate, or state=ALREADY_REVIEWED once "
            + "the token was used. Unknown, expired and malformed tokens are one identical 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Open form or ALREADY_REVIEWED"),
            @ApiResponse(responseCode = "404", description = "Invalid or expired invite", content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content())
    })
    @GetMapping("/{token}")
    public ResponseEntity<ReviewInviteResponse> getInvite(@PathVariable String token) {
        return ResponseEntity.ok(guestReviewService.getInvite(token));
    }

    @Operation(summary = "Submit the guest's review(s)", description = "Rates any subset of the visit's services, "
            + "once per token.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reviews created"),
            @ApiResponse(responseCode = "400", description = "Validation or verification failed", content = @Content()),
            @ApiResponse(responseCode = "404", description = "Invalid, expired or used invite", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Booking already reviewed or not in the invite",
                    content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content())
    })
    @PostMapping("/{token}/reviews")
    public ResponseEntity<GuestReviewSubmitResponse> submit(@PathVariable String token,
                                                            @Valid @RequestBody GuestReviewSubmitRequest request,
                                                            HttpServletRequest httpRequest) {
        turnstileVerifier.verifyReviewInvite(request.turnstileToken(), visitorIpResolver.resolve(httpRequest));
        int created = guestReviewService.submit(token, request.items());
        return ResponseEntity.status(HttpStatus.CREATED).body(new GuestReviewSubmitResponse(created));
    }

    @Operation(summary = "Stop review-invite SMS for this phone", description = "Idempotent; valid for 30 days "
            + "after the invite was sent, also after reviewing.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Opted out"),
            @ApiResponse(responseCode = "400", description = "Verification failed", content = @Content()),
            @ApiResponse(responseCode = "404", description = "Invalid invite", content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content())
    })
    @PostMapping("/{token}/opt-out")
    public ResponseEntity<Void> optOut(@PathVariable String token,
                                       @Valid @RequestBody(required = false) ReviewInviteOptOutRequest request,
                                       HttpServletRequest httpRequest) {
        String turnstileToken = request == null ? null : request.turnstileToken();
        turnstileVerifier.verifyReviewInvite(turnstileToken, visitorIpResolver.resolve(httpRequest));
        guestReviewService.optOut(token);
        return ResponseEntity.noContent().build();
    }
}
