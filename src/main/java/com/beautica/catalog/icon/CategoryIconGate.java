package com.beautica.catalog.icon;

import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.config.CatalogIconProperties;
import com.beautica.service.entity.PlatformCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * B541 (SVC-60) — "a category becomes live only with an icon": the one check every live-making path calls (portal
 * approve, unhide, internal create). A no-op while {@code app.catalog.icon-required} is off (the deploy default).
 */
@Component
@RequiredArgsConstructor
public class CategoryIconGate {

    private final CatalogIconProperties properties;

    /** @throws CatalogRequestConflictException {@code CATEGORY_ICON_REQUIRED} when the gate is on and there is no icon */
    public void requireIcon(PlatformCategory category) {
        if (isEnabled() && category.getIconKey() == null) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATEGORY_ICON_REQUIRED);
        }
    }

    public boolean isEnabled() {
        return properties.iconRequired();
    }
}
