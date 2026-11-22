package com.beautica.catalog.admin;

import com.beautica.catalog.admin.dto.CatalogRequestDto;
import com.beautica.catalog.admin.dto.EditRequestNameRequest;
import com.beautica.catalog.admin.repository.CatalogRequestQueryRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B502 (SVC-55) — the catalog portal's pending-request queue: one list over both request kinds, and the name edit.
 *
 * <p>The edit delegates the domain change to the owning service ({@link CategoryRequestService#editPendingName},
 * {@link ServiceTypeSuggestionService#editPendingName}, which joins this transaction) and records the
 * {@code EDIT_REQUEST_NAME} audit row in the SAME transaction, with only the edited name fields (no requester data).
 * No cache eviction: pending rows feed no cache (phase 502 scope 2).
 */
@Service
@RequiredArgsConstructor
public class CatalogRequestAdminService {

    static final String EDIT_ACTION = "EDIT_REQUEST_NAME";

    private final CatalogRequestQueryRepository queryRepository;
    private final CategoryRequestService categoryRequestService;
    private final ServiceTypeSuggestionService suggestionService;
    private final CatalogAdminAuditService auditService;

    /** Oldest first, whatever sort the caller sent; only page number and size are honoured. */
    @Transactional(readOnly = true)
    public Page<CatalogRequestDto> list(CatalogRequestStatus status, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        long total = queryRepository.count(status);
        if (total == 0 || unsorted.getOffset() >= total) {
            return new PageImpl<>(List.of(), unsorted, total);
        }
        return new PageImpl<>(
                queryRepository.findPage(status, unsorted.getOffset(), unsorted.getPageSize()), unsorted, total);
    }

    /**
     * Edits the name of a PENDING request. {@code rawId} is the path segment: a {@code bigint} for CATEGORY, a UUID
     * for SERVICE_TYPE; a malformed one is a 400.
     */
    @Transactional
    public void editName(AdminActor actor, CatalogRequestKind kind, String rawId, EditRequestNameRequest request) {
        switch (kind) {
            case CATEGORY -> editCategory(actor, parseCategoryId(rawId), request);
            case SERVICE_TYPE -> editSuggestion(actor, parseSuggestionId(rawId), request);
        }
    }

    private void editCategory(AdminActor actor, Long id, EditRequestNameRequest request) {
        CategoryRequestService.NameChange change =
                categoryRequestService.editPendingName(id, request.name(), request.initialServiceName());
        auditService.record(actor, EDIT_ACTION, CatalogAuditTargetType.CATEGORY_REQUEST, String.valueOf(id),
                categoryFields(change.displayNameBefore(), change.initialServiceNameBefore()),
                categoryFields(change.displayNameAfter(), change.initialServiceNameAfter()));
    }

    private void editSuggestion(AdminActor actor, UUID id, EditRequestNameRequest request) {
        if (request.initialServiceName() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "initialServiceName applies to CATEGORY only");
        }
        ServiceTypeSuggestionService.NameChange change = suggestionService.editPendingName(id, request.name());
        auditService.record(actor, EDIT_ACTION, CatalogAuditTargetType.TYPE_SUGGESTION, id.toString(),
                Map.of("suggestedName", change.before()), Map.of("suggestedName", change.after()));
    }

    /** {@link LinkedHashMap}, not {@code Map.of}: the initial service name may be {@code null}. */
    private static Map<String, Object> categoryFields(String displayName, String initialServiceName) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("displayName", displayName);
        fields.put("initialServiceName", initialServiceName);
        return fields;
    }

    private static Long parseCategoryId(String rawId) {
        try {
            return Long.valueOf(rawId);
        } catch (NumberFormatException ex) {
            throw invalidId();
        }
    }

    private static UUID parseSuggestionId(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException ex) {
            throw invalidId();
        }
    }

    private static BusinessException invalidId() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "Invalid request id");
    }
}
