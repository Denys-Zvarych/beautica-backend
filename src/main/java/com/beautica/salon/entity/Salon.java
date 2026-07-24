package com.beautica.salon.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
        name = "salons",
        indexes = {
                // V46: partial index — only active salons (WHERE is_active = true).
                // Backs getOwnerSalons and salon listing queries filtered by owner.
                // JPA @Index cannot express partial WHERE — column list only.
                @Index(name = "idx_salons_owner_active_created", columnList = "owner_id, created_at"),
                // V56: partial unique — one primary salon per owner (WHERE is_primary = true).
                // DB index is partial unique; using unique=true here would generate a full unique
                // constraint under ddl-auto=create-drop, which is wrong — omit unique=true.
                @Index(name = "idx_salons_owner_primary", columnList = "owner_id"),
                // V57: non-partial — backs existsByOwnerId(UUID) which must find any salon
                // regardless of is_active or is_primary state.
                @Index(name = "idx_salons_owner_id", columnList = "owner_id")
        }
)
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

    /**
     * Wide hero/banner image shown at the top of the public salon profile (Phase 13.6).
     * Nullable — salons without a cover image fall back to a plain header on the client.
     * No CHECK constraint yet (V103): there is no upload endpoint until the Phase 9
     * media feature ships, so URL-format validation is deferred to that migration.
     */
    @Column(name = "cover_image_url", length = 2048)
    private String coverImageUrl;

    /**
     * Persisted rating aggregate — mirrors {@code Master#avgRating}'s exact JPA style
     * (bare {@code @Column(name = "avg_rating")}, no {@code nullable = false}; the DB
     * column is {@code NUMERIC(3,2)}, precision/scale intentionally left off the
     * annotation to match). {@code null} until {@link com.beautica.review.repository.ReviewRepository#recalculateSalonRating}
     * has run at least once; the DTO layer additionally treats a zero {@code reviewCount}
     * as "no rating" regardless of the stored value.
     */
    @Column(name = "avg_rating")
    private BigDecimal avgRating;

    @Column(name = "review_count")
    private int reviewCount;

    @Column(name = "is_active")
    private boolean isActive;

    // True for the salon created during SALON_OWNER registration (Phase 12.1).
    // Only one primary salon per owner — enforced by idx_salons_owner_primary (V56).
    // No initializer: @Builder ignores field initializers (it warns when one is present),
    // and the JVM already defaults a primitive boolean to false — which is the intent here.
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;
}
