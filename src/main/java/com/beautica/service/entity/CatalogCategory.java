package com.beautica.service.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "service_categories")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogCategory extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name_uk", nullable = false, length = 100)
    private String nameUk;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
