package com.beautica.salon.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "salons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Salon extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    private String description;

    private String city;

    private String region;

    private String address;

    // ---- Phase 10.3 locality (raw UUID FK columns, NULLABLE) -------------
    // FKs to the Phase 10.1 taxonomy (cities / city_districts). Modeled as raw
    // UUIDs — not @ManyToOne — to avoid adding an association traversal surface
    // on Salon (existing salon read paths must not N+1 / LazyInit on locality)
    // and consistent with the User-side representation. Read/write semantics
    // are owned by Phases 10.4/10.6. Legacy city/region/address stay (nullable).
    @Column(name = "city_id")
    private UUID cityId;

    @Column(name = "district_id")
    private UUID districtId;

    // Light, unvalidated structured address (M1) — separate street / building /
    // landmark fields, no geocoding now. Lengths mirror V54 exactly so
    // ddl-auto=validate catches drift.
    @Column(name = "street", length = 255)
    private String street;

    @Column(name = "building_no", length = 50)
    private String buildingNo;

    @Column(name = "location_note", columnDefinition = "TEXT")
    private String locationNote;

    private String phone;

    @Column(name = "instagram_url")
    private String instagramUrl;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "is_active")
    private boolean isActive;
}
