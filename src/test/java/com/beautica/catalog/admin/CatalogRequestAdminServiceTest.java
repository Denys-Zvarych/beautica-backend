package com.beautica.catalog.admin;

import com.beautica.catalog.admin.dto.CatalogRequestDto;
import com.beautica.catalog.admin.dto.EditRequestNameRequest;
import com.beautica.catalog.admin.repository.CatalogRequestQueryRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogRequestAdminService — unit")
class CatalogRequestAdminServiceTest {

    private static final AdminActor ACTOR = AdminActor.of("beautica.ua@gmail.com");

    @Mock private CatalogRequestQueryRepository queryRepository;
    @Mock private CategoryRequestService categoryRequestService;
    @Mock private ServiceTypeSuggestionService suggestionService;
    @Mock private CatalogAdminAuditService auditService;

    private CatalogRequestAdminService service;

    @BeforeEach
    void setUp() {
        service = new CatalogRequestAdminService(queryRepository, categoryRequestService, suggestionService, auditService);
    }

    private static CatalogRequestDto row(String id) {
        return new CatalogRequestDto(CatalogRequestKind.SERVICE_TYPE, id, "Балаяж", null, "HAIR", null, null,
                "Salon", Instant.parse("2026-11-01T10:00:00Z"), CatalogRequestStatus.PENDING);
    }

    // ── list ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnPageWithSqlOffset_when_rowsExist")
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
    @DisplayName("should_skipPageQuery_when_offsetPastTotal")
    void should_skipPageQuery_when_offsetPastTotal() {
        when(queryRepository.count(CatalogRequestStatus.REJECTED)).thenReturn(1L);

        Page<CatalogRequestDto> page = service.list(CatalogRequestStatus.REJECTED, PageRequest.of(5, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1L);
        verify(queryRepository, never()).findPage(org.mockito.ArgumentMatchers.any(), anyLong(), anyInt());
    }

    // ── editName ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_editSuggestionAndAuditNameOnly_when_kindServiceType")
    void should_editSuggestionAndAuditNameOnly_when_kindServiceType() {
        UUID id = UUID.randomUUID();
        when(suggestionService.editPendingName(id, "Балаяж"))
                .thenReturn(new ServiceTypeSuggestionService.NameChange("HAIR", "Балаж", "Балаяж"));

        service.editName(ACTOR, CatalogRequestKind.SERVICE_TYPE, id.toString(), new EditRequestNameRequest("Балаяж", null));

        verify(auditService).record(ACTOR, "EDIT_REQUEST_NAME", CatalogAuditTargetType.TYPE_SUGGESTION, id.toString(),
                Map.of("suggestedName", "Балаж"), Map.of("suggestedName", "Балаяж"));
    }

    @Test
    @DisplayName("should_editCategoryAndAuditBothFields_when_kindCategory")
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
    @DisplayName("should_throw400_when_initialServiceNameSentForServiceType")
    void should_throw400_when_initialServiceNameSentForServiceType() {
        String id = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.editName(ACTOR, CatalogRequestKind.SERVICE_TYPE, id,
                new EditRequestNameRequest("Балаяж", "Френч")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(suggestionService, auditService);
    }

    @Test
    @DisplayName("should_throw400_when_idMalformedForKind")
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
}
