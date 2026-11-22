package com.beautica.catalog.admin;

import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.admin.dto.CatalogRequestDto;
import com.beautica.catalog.admin.dto.EditRequestNameRequest;
import com.beautica.catalog.admin.repository.CatalogRequestQueryRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.service.controller.DecisionReason;
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
 *
 * <p>B503 (SVC-06) — approve / reject by id. The owning service runs the SAME decision as the emailed link
 * ({@code approveById} / {@code rejectById} → the shared {@code decide(…)}), joining this transaction; this class adds
 * the {@code APPROVE} / {@code REJECT} audit row in the same transaction; an approve also calls
 * {@link CatalogCacheEvictor#evictAll()}, which runs after commit (a rollback evicts nothing). A reject evicts nothing:
 * pending and rejected rows feed no cache.
 */
@Service
@RequiredArgsConstructor
public class CatalogRequestAdminService {

    static final String EDIT_ACTION = "EDIT_REQUEST_NAME";
    static final String APPROVE_ACTION = "APPROVE";
    static final String REJECT_ACTION = "REJECT";
    private static final String STATUS = "status";
    private static final String REASON_PRESENT = "reasonPresent";

    private final CatalogRequestQueryRepository queryRepository;
    private final CategoryRequestService categoryRequestService;
    private final ServiceTypeSuggestionService suggestionService;
    private final CatalogAdminAuditService auditService;
    private final CatalogCacheEvictor cacheEvictor;

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

    /**
     * B503 — approves a PENDING request: promotion, decision e-mail and token invalidation exactly as the email link.
     * 404 for an unknown id, 409 {@code REQUEST_NOT_PENDING} when already decided (the row lock makes the first
     * decision win), 400 for a malformed id.
     */
    @Transactional
    public void approve(AdminActor actor, CatalogRequestKind kind, String rawId) {
        String targetId = switch (kind) {
            case CATEGORY -> {
                Long id = parseCategoryId(rawId);
                categoryRequestService.approveById(id);
                yield String.valueOf(id);
            }
            case SERVICE_TYPE -> {
                UUID id = parseSuggestionId(rawId);
                suggestionService.approveById(id);
                yield id.toString();
            }
        };
        auditService.record(actor, APPROVE_ACTION, targetType(kind), targetId,
                Map.of(STATUS, CatalogRequestStatus.PENDING.name()), Map.of(STATUS, CatalogRequestStatus.APPROVED.name()));
        cacheEvictor.evictAll();
    }

    /**
     * B503 — rejects a PENDING request with an optional reason, cleaned by the email form's rules
     * ({@link DecisionReason#parse}); over {@value DecisionReason#MAX_LENGTH} code points is a 400 and nothing changes.
     * Errors as {@link #approve}. No eviction: a rejected row feeds no cache.
     */
    @Transactional
    public void reject(AdminActor actor, CatalogRequestKind kind, String rawId, String rawReason) {
        DecisionReason.Parsed parsed = DecisionReason.parse(rawReason);
        if (parsed.tooLong()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Reason must be at most " + DecisionReason.MAX_LENGTH + " characters");
        }
        String reason = parsed.value();
        String targetId = switch (kind) {
            case CATEGORY -> {
                Long id = parseCategoryId(rawId);
                categoryRequestService.rejectById(id, reason);
                yield String.valueOf(id);
            }
            case SERVICE_TYPE -> {
                UUID id = parseSuggestionId(rawId);
                suggestionService.rejectById(id, reason);
                yield id.toString();
            }
        };
        // Only whether a reason was given: catalog_admin_audit is append-only and never erased, so free text stays out
        // of it; the reason itself lives only in the request's decision_reason column.
        auditService.record(actor, REJECT_ACTION, targetType(kind), targetId,
                Map.of(STATUS, CatalogRequestStatus.PENDING.name()),
                Map.of(STATUS, CatalogRequestStatus.REJECTED.name(), REASON_PRESENT, reason != null));
    }

    private static CatalogAuditTargetType targetType(CatalogRequestKind kind) {
        return switch (kind) {
            case CATEGORY -> CatalogAuditTargetType.CATEGORY_REQUEST;
            case SERVICE_TYPE -> CatalogAuditTargetType.TYPE_SUGGESTION;
        };
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
