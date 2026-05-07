package com.beautica.service.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "service_types")
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

    @Column(name = "name_en", length = 255)
    private String nameEn;

    @Column(nullable = false, unique = true, length = 255)
    private String slug;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
