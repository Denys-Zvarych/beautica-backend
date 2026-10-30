package com.beautica.notification.telegram.link;

import com.beautica.notification.telegram.link.dto.GuestTelegramLinkResponse;
import com.beautica.notification.telegram.link.dto.GuestTelegramStatusResponse;
import com.beautica.notification.telegram.link.dto.GuestTelegramTokenRequest;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 407 — public guest Telegram link API for the website (success screen, website 022; cancel page, website 024).
 * {@code permitAll} in {@code SecurityConfig}; the booking's cancel token in the BODY is the only credential (POST
 * everywhere so it never reaches a URL or access log). Per-IP buckets in {@code AuthRateLimitFilter}, the
 * per-cancel-token one in {@link GuestTelegramLinkService}. Every non-eligible case, and the channel being off, is a
 * 404. HTTP only: all rules live in the service.
 */
@RestController
@RequestMapping("/api/v1/book/telegram")
@RequiredArgsConstructor
public class GuestTelegramLinkController {

    private final GuestTelegramLinkService guestTelegramLinkService;

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deep link issued, or the phone is already linked"),
            @ApiResponse(responseCode = "404", description = "Not eligible or channel off", content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content())
    })
    @PostMapping("/link-token")
    public ResponseEntity<GuestTelegramLinkResponse> linkToken(@Valid @RequestBody GuestTelegramTokenRequest request) {
        return ResponseEntity.ok(guestTelegramLinkService.issueLinkToken(request.cancelToken()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Link status for the booking phone"),
            @ApiResponse(responseCode = "404", description = "Not eligible or channel off", content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content())
    })
    @PostMapping("/status")
    public ResponseEntity<GuestTelegramStatusResponse> status(@Valid @RequestBody GuestTelegramTokenRequest request) {
        return ResponseEntity.ok(guestTelegramLinkService.status(request.cancelToken()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Phone switched back to SMS (idempotent)"),
            @ApiResponse(responseCode = "404", description = "Not eligible or channel off", content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content())
    })
    @PostMapping("/unlink")
    public ResponseEntity<Void> unlink(@Valid @RequestBody GuestTelegramTokenRequest request) {
        guestTelegramLinkService.unlink(request.cancelToken());
        return ResponseEntity.noContent().build();
    }
}
