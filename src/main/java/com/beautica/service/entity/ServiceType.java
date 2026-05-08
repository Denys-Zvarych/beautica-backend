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
                @jakarta.persistence.Index(name = "idx_service_types_category_active", columnList = "category_id, name_uk")
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
}
