package com.beautica.catalog.icon;

import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.admin.AdminActor;
import com.beautica.catalog.icon.dto.CategoryIconBackfillResponse;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * B540 (SVC-E12) — gives every PENDING / APPROVED category without an icon its bundled icon, by wire name, through the
 * normal {@link CategoryIconService#setIconIfAbsent} path (same sanitiser, audit {@code ICON_SET}). The platform-catalog
 * caches are evicted ONCE after the whole run (also after a part-way failure), not per category.
 *
 * <p>Idempotent: a category that already has an icon is {@code skipped} and never overwritten (also re-checked under
 * the row lock). One transaction PER category (no outer one), so a failure part-way keeps what was set and a re-run
 * finishes the rest. {@code missing} = categories with no icon and no bundled match: they need an operator upload
 * before the icon gate (B541, {@code app.catalog.icon-required}) is switched on.
 */
@Service
@RequiredArgsConstructor
public class CategoryIconBackfillService {

    private final PlatformCategoryRepository platformCategoryRepository;
    private final BundledCategoryIcons bundledIcons;
    private final CategoryIconService iconService;
    private final CatalogCacheEvictor cacheEvictor;

    /** Deliberately not {@code @Transactional}: each {@code setIconIfAbsent} commits on its own. */
    public CategoryIconBackfillResponse backfill(AdminActor actor) {
        List<String> set = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try {
            backfillEach(actor, set, skipped, missing);
        } finally {
            if (!set.isEmpty()) {
                cacheEvictor.evictPlatformCatalog();
            }
        }
        return new CategoryIconBackfillResponse(set, skipped, missing);
    }

    private void backfillEach(AdminActor actor, List<String> set, List<String> skipped, List<String> missing) {
        for (PlatformCategory category : platformCategoryRepository.findAllNotRejected()) {
            String name = category.getName();
            Optional<String> bundled = bundledIcons.svgFor(name);
            if (category.getIconKey() != null) {
                skipped.add(name);
            } else if (bundled.isEmpty()) {
                missing.add(name);
            } else if (iconService.setIconIfAbsent(name, bundled.get(), actor)) {
                set.add(name);
            } else {
                skipped.add(name);
            }
        }
    }
}
