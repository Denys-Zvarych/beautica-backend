package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.service.ServiceCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServiceCatalogController {

    private final ServiceCatalogService serviceCatalogService;

    @GetMapping("/service-categories")
    public ApiResponse<List<CatalogCategoryResponse>> getCategories() {
        return ApiResponse.ok(serviceCatalogService.getCategories());
    }

    @GetMapping("/service-types")
    public ApiResponse<List<ServiceTypeResponse>> getServiceTypes(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) @Size(max = 100) String q) {
        return ApiResponse.ok(serviceCatalogService.searchServiceTypes(categoryId, q));
    }

    @PostMapping("/service-types/suggest")
    @PreAuthorize("hasAnyRole('SALON_OWNER','INDEPENDENT_MASTER','SALON_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> suggestServiceType(
            @Valid @RequestBody SuggestServiceTypeRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        serviceCatalogService.suggestServiceType(request, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(null, "Suggestion submitted"));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID userId) {
            return userId;
        }
        throw new ForbiddenException("Not authenticated");
    }
}
