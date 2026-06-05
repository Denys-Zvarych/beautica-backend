package com.beautica.service.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.UUID;

@Entity
@Table(
        name = "service_types",
        indexes = {
                // GIN trigram indexes from V12 for Ukrainian/English name autocomplete.
                @jakarta.persistence.Index(name = "idx_service_types_name_uk_trgm", columnList = "name_uk"),
                @jakarta.persistence.Index(name = "idx_service_types_name_en_trgm", columnList = "name_en"),
                // Composite partial index from V14 covering category+active filter.
                @jakarta.persistence.Index(name = "idx_service_types_category_active", columnList = "category_id, name_uk"),
                // Partial B-tree index from V73 for platform-category filtering (Phase 16.2+).
                // Mirrors the DB partial index: WHERE is_active = TRUE.
                @jakarta.persistence.Index(name = "idx_service_types_platform_category", columnList = "platform_category_name")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceType extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private CatalogCategory category;

    @Column(name = "name_uk", nullable = false, length = 255)
    private String nameUk;

    // Intentionally nullable: English names are optional. Callers must null-check
    // or fall back to nameUk before displaying (no coalescing guard exists in the DB).
    @Column(name = "name_en", length = 255)
    private String nameEn;

    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
    @Size(max = 255)
    @Column(nullable = false, unique = true, length = 255)
    private String slug;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    // Added in V73 (Phase 16.1). Stores the platform_categories.name slug that
    // this service type belongs to (e.g. 'MANICURE', 'HAIR'). The column has a
    // FK → platform_categories(name) for closed-set enforcement and a partial
    // B-tree index (idx_service_types_platform_category WHERE is_active = TRUE)
    // for Phase 16.2+ category-filtered queries.
    // Field mapping is intentionally minimal here (16.2 service layer will use it);
    // adding the @Column now stops ddl-auto=validate from drifting.
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$")
    @Size(max = 100)
    @Column(name = "platform_category_name", nullable = false, length = 100)
    private String platformCategoryName;
}
