package com.beautica.catalog.admin;

import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.admin.dto.CatalogRequestDto;
import com.beautica.catalog.admin.dto.EditRequestNameRequest;
import com.beautica.catalog.admin.repository.CatalogRequestQueryRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogRequestAdminService — unit")
class CatalogRequestAdminServiceTest {

    private static final AdminActor ACTOR = AdminActor.of("beautica.ua@gmail.com");

    @Mock private CatalogRequestQueryRepository queryRepository;
    @Mock private CategoryRequestService categoryRequestService;
    @Mock private ServiceTypeSuggestionService suggestionService;
    @Mock private CatalogAdminAuditService auditService;
    @Mock private CatalogCacheEvictor cacheEvictor;

    private CatalogRequestAdminService service;

    @BeforeEach
    void setUp() {
        service = new CatalogRequestAdminService(queryRepository, categoryRequestService, suggestionService, auditService,
                cacheEvictor);
    }

    private static CatalogRequestDto row(String id) {
        return new CatalogRequestDto(CatalogRequestKind.SERVICE_TYPE, id, "Балаяж", null, "HAIR", null, null,
                "Salon", Instant.parse("2026-11-01T10:00:00Z"), CatalogRequestStatus.PENDING);
    }

    // ── list ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list → page number × size becomes the SQL offset; the caller's sort is ignored (always oldest first)")
    void should_returnPageWithSqlOffset_when_rowsExist() {
        when(queryRepository.count(CatalogRequestStatus.PENDING)).thenReturn(3L);
        when(queryRepository.findPage(CatalogRequestStatus.PENDING, 2L, 2)).thenReturn(List.of(row("c")));

        Page<CatalogRequestDto> page = service.list(CatalogRequestStatus.PENDING,
                PageRequest.of(1, 2, Sort.by("name").descending()));

        assertThat(page.getContent()).extracting(CatalogRequestDto::id).containsExactly("c");
        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getSort().isUnsorted()).as("caller sort ignored: always oldest first").isTrue();
    }

    @Test
    @DisplayName("list past the last page → empty page with the real total, no page query")
    void should_skipPageQuery_when_offsetPastTotal() {
        when(queryRepository.count(CatalogRequestStatus.REJECTED)).thenReturn(1L);

        Page<CatalogRequestDto> page = service.list(CatalogRequestStatus.REJECTED, PageRequest.of(5, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1L);
        verify(queryRepository, never()).findPage(org.mockito.ArgumentMatchers.any(), anyLong(), anyInt());
    }

    // ── editName ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("edit SERVICE_TYPE → renames via the owning service; audit carries only {suggestedName}")
    void should_editSuggestionAndAuditNameOnly_when_kindServiceType() {
        UUID id = UUID.randomUUID();
        when(suggestionService.editPendingName(id, "Балаяж"))
                .thenReturn(new ServiceTypeSuggestionService.NameChange("HAIR", "Балаж", "Балаяж"));

        service.editName(ACTOR, CatalogRequestKind.SERVICE_TYPE, id.toString(), new EditRequestNameRequest("Балаяж", null));

        verify(auditService).record(ACTOR, "EDIT_REQUEST_NAME", CatalogAuditTargetType.TYPE_SUGGESTION, id.toString(),
                Map.of("suggestedName", "Балаж"), Map.of("suggestedName", "Балаяж"));
    }

    @Test
    @DisplayName("edit CATEGORY → audit carries exactly {displayName, initialServiceName}, null kept")
    void should_editCategoryAndAuditBothFields_when_kindCategory() {
        when(categoryRequestService.editPendingName(42L, "Нейл-арт", ""))
                .thenReturn(new CategoryRequestService.NameChange("Нейл арт", "Нейл-арт", "Френч", null));

        service.editName(ACTOR, CatalogRequestKind.CATEGORY, "42", new EditRequestNameRequest("Нейл-арт", ""));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("displayName", "Нейл арт");
        before.put("initialServiceName", "Френч");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("displayName", "Нейл-арт");
        after.put("initialServiceName", null);
        verify(auditService).record(ACTOR, "EDIT_REQUEST_NAME", CatalogAuditTargetType.CATEGORY_REQUEST, "42",
                before, after);
    }

    @Test
    @DisplayName("edit SERVICE_TYPE with initialServiceName → 400, nothing edited or audited")
    void should_throw400_when_initialServiceNameSentForServiceType() {
        String id = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.editName(ACTOR, CatalogRequestKind.SERVICE_TYPE, id,
                new EditRequestNameRequest("Балаяж", "Френч")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(suggestionService, auditService);
    }

    @Test
    @DisplayName("edit with an id of the wrong shape for its kind → 400 before any service call")
    void should_throw400_when_idMalformedForKind() {
        EditRequestNameRequest request = new EditRequestNameRequest("Балаяж", null);

        assertThatThrownBy(() -> service.editName(ACTOR, CatalogRequestKind.CATEGORY, "not-a-number", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.editName(ACTOR, CatalogRequestKind.SERVICE_TYPE, "42", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(categoryRequestService, suggestionService, auditService);
    }

    // ── approve / reject (B503) ────────────────────────────────────────────────

    @Test
    @DisplayName("approve SERVICE_TYPE → decide by id, then APPROVE audit {status}, then evict every catalog cache")
    void should_decideAuditThenEvict_when_suggestionApproved() {
        UUID id = UUID.randomUUID();

        service.approve(ACTOR, CatalogRequestKind.SERVICE_TYPE, id.toString());

        InOrder order = inOrder(suggestionService, auditService, cacheEvictor);
        order.verify(suggestionService).approveById(id);
        order.verify(auditService).record(ACTOR, "APPROVE", CatalogAuditTargetType.TYPE_SUGGESTION, id.toString(),
                Map.of("status", "PENDING"), Map.of("status", "APPROVED"));
        order.verify(cacheEvictor).evictAll();
        verifyNoInteractions(categoryRequestService);
    }

    @Test
    @DisplayName("approve CATEGORY → numeric id to the category service, CATEGORY_REQUEST audit, evict")
    void should_approveCategoryById_when_kindCategory() {
        service.approve(ACTOR, CatalogRequestKind.CATEGORY, "77");

        verify(categoryRequestService).approveById(77L);
        verify(auditService).record(ACTOR, "APPROVE", CatalogAuditTargetType.CATEGORY_REQUEST, "77",
                Map.of("status", "PENDING"), Map.of("status", "APPROVED"));
        verify(cacheEvictor).evictAll();
    }

    @Test
    @DisplayName("reject → reason cleaned by the email-form rules; audit records only reasonPresent=true; no eviction")
    void should_passCleanedReasonAuditItAndNotEvict_when_rejected() {
        service.reject(ACTOR, CatalogRequestKind.CATEGORY, "77", "  Вже\r\nє\u200B така ");

        verify(categoryRequestService).rejectById(77L, "Вже є така");
        verify(auditService).record(ACTOR, "REJECT", CatalogAuditTargetType.CATEGORY_REQUEST, "77",
                Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reasonPresent", true));
        verifyNoInteractions(cacheEvictor);
    }

    @Test
    @DisplayName("reject with a blank reason → no reason passed, audit reasonPresent=false, no eviction")
    void should_rejectWithNullReason_when_reasonBlank() {
        UUID id = UUID.randomUUID();

        service.reject(ACTOR, CatalogRequestKind.SERVICE_TYPE, id.toString(), "   ");

        verify(suggestionService).rejectById(id, null);
        verify(auditService).record(ACTOR, "REJECT", CatalogAuditTargetType.TYPE_SUGGESTION, id.toString(),
                Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reasonPresent", false));
        verifyNoInteractions(cacheEvictor);
    }

    @Test
    @DisplayName("reject with a 301-code-point reason → 400, nothing decided, audited or evicted")
    void should_throw400AndDecideNothing_when_reasonOver300CodePoints() {
        assertThatThrownBy(() -> service.reject(ACTOR, CatalogRequestKind.CATEGORY, "77", "ж".repeat(301)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(categoryRequestService, auditService, cacheEvictor);
    }

    @Test
    @DisplayName("decision on a non-pending row → REQUEST_NOT_PENDING propagates, no audit, no eviction")
    void should_propagateConflictWithoutAuditOrEvict_when_notPending() {
        UUID id = UUID.randomUUID();
        doThrow(new CatalogRequestConflictException(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING))
                .when(suggestionService).approveById(id);

        assertThatThrownBy(() -> service.approve(ACTOR, CatalogRequestKind.SERVICE_TYPE, id.toString()))
                .isInstanceOf(CatalogRequestConflictException.class);

        verify(auditService, never()).record(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyMap(), anyMap());
        verifyNoInteractions(cacheEvictor);
    }

    @Test
    @DisplayName("approve with a malformed id → 400 before any decision")
    void should_throw400_when_decisionIdMalformed() {
        assertThatThrownBy(() -> service.approve(ACTOR, CatalogRequestKind.SERVICE_TYPE, "42"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(suggestionService, auditService, cacheEvictor);
    }
}
