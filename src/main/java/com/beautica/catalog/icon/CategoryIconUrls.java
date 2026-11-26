package com.beautica.catalog.icon;

import com.beautica.media.service.R2StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * B539 (track D8) — turns a stored {@code icon_key} into the public {@code iconUrl} at read time, so a change of
 * {@code CLOUDFLARE_R2_PUBLIC_URL} needs no SQL. {@code null} when the category has no icon or R2 is disabled
 * (local / test without storage, where {@link R2StorageService#buildPublicUrl} returns {@code ""}).
 */
@Component
@RequiredArgsConstructor
public class CategoryIconUrls {

    private final R2StorageService r2StorageService;

    @Nullable
    public String urlFor(@Nullable String iconKey) {
        if (iconKey == null || iconKey.isBlank()) {
            return null;
        }
        String url = r2StorageService.buildPublicUrl(iconKey);
        return url == null || url.isEmpty() ? null : url;
    }
}
