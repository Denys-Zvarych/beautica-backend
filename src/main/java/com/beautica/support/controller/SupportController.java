package com.beautica.support.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.support.dto.ContactSupportRequest;
import com.beautica.support.dto.ContactSupportResponse;
import com.beautica.support.service.SupportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Help / Contact-us endpoint. Authenticated users only — the route is NOT in the
 * Spring Security permit-all list, so it falls through to
 * {@code anyRequest().authenticated()}. Sender identity is captured from the JWT
 * principal (email) and details (UUID) set by {@code JwtAuthenticationFilter};
 * the request body carries no identity field, so the sender cannot spoof it.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;

    @Operation(
            summary = "Send a Help / Contact-us message to support",
            description = """
                    Accepts a free-text message plus optional file attachments and emails the whole
                    thing to the support inbox. Authenticated users only; the sender's identity is
                    taken from the JWT, never from the request body.

                    Constraints: up to 5 attachments, each ≤ 5 MB, total ≤ 5 MB; allowed types
                    JPEG, PNG, WebP, PDF (validated by content, not the declared header).""",
            requestBody = @RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(implementation = ContactSupportForm.class)))
    )
    @PostMapping(value = "/support/contact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ContactSupportResponse>> contact(
            @Valid @RequestPart("request") ContactSupportRequest request,
            @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments,
            Authentication authentication) {

        UUID senderUserId = AuthenticationUtils.userId(authentication);
        String senderEmail = extractEmail(authentication);

        ContactSupportResponse response =
                supportService.contact(senderUserId, senderEmail, request, attachments);
        return ResponseEntity.accepted().body(ApiResponse.ok(response));
    }

    private String extractEmail(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof String email
                && !email.isBlank()) {
            return email;
        }
        throw new ForbiddenException("Not authenticated");
    }

    /**
     * OpenAPI-only schema describing the multipart contract so the generated mobile
     * client emits the correct parts. Not bound at runtime — the controller binds
     * {@code request} (the JSON/text part) and {@code attachments} separately.
     */
    private record ContactSupportForm(
            @Schema(implementation = ContactSupportRequest.class)
            ContactSupportRequest request,
            @Schema(type = "array", format = "binary",
                    description = "Optional file attachments (≤5 files, ≤5 MB each, ≤5 MB total)")
            List<MultipartFile> attachments
    ) {
    }
}
