package com.beautica.service.service;

import com.beautica.catalog.admin.AdminActor;
import com.beautica.catalog.icon.CategoryIconGate;
import com.beautica.catalog.icon.CategoryIconService;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.CategoryIconInvalidException;
import com.beautica.service.dto.CreatePlatformCategoryRequest;
import com.beautica.service.dto.PlatformCategoryResponse;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCategoryService.create — B541 icon gate and optional svg")
class InternalCategoryServiceIconTest {

    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M0 0L24 24\"/></svg>";
    private static final String ACTOR = "beautica.ua@gmail.com";

    @Mock private PlatformCategoryRepository repository;
    @Mock private CategoryIconGate gate;
    @Mock private CategoryIconService iconService;

    private InternalCategoryService service;

    @BeforeEach
    void setUp() {
        service = new InternalCategoryService(repository, gate, iconService);
    }

    private void saveEchoes() {
        when(repository.save(any(PlatformCategory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("gate off, no svg: created as before, no icon call")
    void should_createWithoutIcon_when_gateOffAndNoSvg() {
        saveEchoes();

        service.create(new CreatePlatformCategoryRequest("NAIL_ART"), null);

        verify(repository).save(any(PlatformCategory.class));
        verifyNoInteractions(iconService);
    }

    @Test
    @DisplayName("gate on, no svg: 409 CATEGORY_ICON_REQUIRED, nothing saved")
    void should_refuse_when_gateOnAndNoSvg() {
        when(gate.isEnabled()).thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.create(new CreatePlatformCategoryRequest("NAIL_ART"), null));

        assertThat(thrown).isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                .isEqualTo(CatalogRequestConflictException.Code.CATEGORY_ICON_REQUIRED));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("svg given: the row is created INSIDE createWithIcon (icon PUT first, then one transaction for row + "
            + "icon) with the header's actor")
    void should_createInsideCreateWithIcon_when_svgGiven() {
        saveEchoes();
        when(iconService.createWithIcon(eq("NAIL_ART"), eq(SVG), eq(new AdminActor(ACTOR)), any()))
                .thenAnswer(inv -> inv.<Supplier<?>>getArgument(3).get());

        PlatformCategoryResponse created = service.create(new CreatePlatformCategoryRequest("NAIL_ART", SVG), ACTOR);

        assertThat(created.name()).isEqualTo("NAIL_ART");
        verify(repository).save(any(PlatformCategory.class));
        verify(iconService, never()).setIcon(any(), any(), any());
    }

    @Test
    @DisplayName("svg given: nothing is saved outside createWithIcon (a refused icon creates no row)")
    void should_saveNothing_when_createWithIconRefuses() {
        when(iconService.createWithIcon(any(), any(), any(), any()))
                .thenThrow(new CategoryIconInvalidException(CategoryIconInvalidException.Reason.FORBIDDEN_ELEMENT));

        Throwable thrown = catchThrowable(() -> service.create(new CreatePlatformCategoryRequest("NAIL_ART", SVG), ACTOR));

        assertThat(thrown).isInstanceOf(CategoryIconInvalidException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("svg given without X-Admin-Actor: 400, nothing saved")
    void should_return400_when_svgWithoutActor() {
        Throwable thrown = catchThrowable(() -> service.create(new CreatePlatformCategoryRequest("NAIL_ART", SVG), null));

        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }
}
