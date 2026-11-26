package com.beautica.catalog.icon;

import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.config.CatalogIconProperties;
import com.beautica.service.entity.PlatformCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("CategoryIconGate — B541 app.catalog.icon-required")
class CategoryIconGateTest {

    private static PlatformCategory withIcon() {
        PlatformCategory category = PlatformCategory.ofApproved("MANICURE", "Манікюр");
        category.changeIcon("catalog/category-icons/MANICURE/0123456789abcdef.svg", "a".repeat(64),
                OffsetDateTime.parse("2026-11-26T10:00:00Z"));
        return category;
    }

    @Test
    @DisplayName("gate on, no icon: 409 CATEGORY_ICON_REQUIRED")
    void should_throwIconRequired_when_onAndNoIcon() {
        CategoryIconGate gate = new CategoryIconGate(new CatalogIconProperties(true));

        Throwable thrown = catchThrowable(() -> gate.requireIcon(PlatformCategory.ofApproved("MANICURE", "Манікюр")));

        assertThat(thrown).isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                .isEqualTo(CatalogRequestConflictException.Code.CATEGORY_ICON_REQUIRED));
    }

    @Test
    @DisplayName("gate on, icon set: passes")
    void should_pass_when_onAndIconSet() {
        CategoryIconGate gate = new CategoryIconGate(new CatalogIconProperties(true));

        assertThatCode(() -> gate.requireIcon(withIcon())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("gate off (deploy default): passes without an icon")
    void should_pass_when_offAndNoIcon() {
        CategoryIconGate gate = new CategoryIconGate(new CatalogIconProperties(false));

        assertThatCode(() -> gate.requireIcon(PlatformCategory.ofApproved("MANICURE", "Манікюр")))
                .doesNotThrowAnyException();
        assertThat(gate.isEnabled()).isFalse();
    }
}
