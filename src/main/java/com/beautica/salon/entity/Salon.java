package com.beautica.salon.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.location.SettlementDisplayNames;
import com.beautica.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
import lombok.AccessLevel;
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

    // ---- Phase 10.3 locality (raw UUID FK columns) ------------------------
    // FKs to the Phase 10.1 taxonomy (cities / city_districts). Modeled as raw
    // UUIDs — not @ManyToOne — to avoid adding an association traversal surface
    // on Salon (existing salon read paths must not N+1 / LazyInit on locality)
    // and consistent with the User-side representation. Read/write semantics
    // are owned by Phases 10.4/10.6. Legacy city/region/address stay (nullable).
    //
    // cityId was NULLABLE at the DB level from V54 through V149 — added to an
    // already-populated table with no backfill, promotion deferred to the
    // write-path guard (LocalityWriteValidator.validateProviderLocality, called
    // unconditionally from SalonService#createSalon/#updateSalon). V150/V151
    // make the invariant ("a salon must always have a city") a real DB
    // constraint — V150 does NOT clean up any pre-existing null-city row; it
    // fails loudly and refuses to apply if one exists. Any database carrying
    // legacy null-city salon rows must have them resolved by hand (backfill
    // city_id, or otherwise dispose of the row) before V150 can apply — for a
    // disposable LOCAL dev database only, see
    // scripts/dev-cleanup-null-city-salons.sql. The validator's null check
    // stays regardless: it is what turns a null city into a clean 400 instead
    // of a raw DataIntegrityViolationException (500).
    @Column(name = "city_id", nullable = false)
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

    // ---- Phase 343 image pointers (logo + cover) ----------------------------
    // All four columns are updatable = false: the ONLY writers are SalonRepository's targeted
    // native pointer writes (writeLogoPointers / writeCoverPointers, under the row lock) and
    // nullImageUrls (salon teardown). Hibernate updates every column of a dirty entity, so a
    // concurrent PATCH /salons/{id} (admin-reachable) that loaded the row before an upload
    // committed would otherwise write the superseded pointer back over the new one — leaving the
    // row pointing at a blob the upload already purged. Excluding them from entity UPDATEs makes
    // that lost update impossible. No setters: replaceImage keeps the managed instance in step
    // with the native write so the response built from it is current.

    /** Salon logo (square, 1:1 crop on mobile). V189: https-only CHECK. */
    @Setter(AccessLevel.NONE)
    @Column(name = "avatar_url", updatable = false)
    private String avatarUrl;

    /** R2 key of {@link #avatarUrl}; V189 CHECK pins it under {@code salons/<id>/logo/}. NULL on legacy rows. */
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Column(name = "avatar_r2_key", length = 500, updatable = false)
    private String avatarR2Key;

    /**
     * Wide hero/banner image shown at the top of the public salon profile (Phase 13.6; 16:9 crop on
     * mobile). Nullable — salons without a cover image fall back to a plain header on the client.
     * Uploaded since Phase 343 (OWNER only); V189 adds the https-only CHECK.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "cover_image_url", length = 2048, updatable = false)
    private String coverImageUrl;

    /** R2 key of {@link #coverImageUrl}; V189 CHECK pins it under {@code salons/<id>/cover/}. NULL on legacy rows. */
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Column(name = "cover_r2_key", length = 500, updatable = false)
    private String coverR2Key;

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

    /**
     * Denormalises a settlement's labels into the legacy {@code city}/{@code region} columns
     * whenever {@code cityId} is written. {@code null} (the id did not resolve) CLEARS both — a
     * stale label must never sit beside a new id. Same rule as
     * {@link User#applySettlementDisplayNames(SettlementDisplayNames)}.
     *
     * @param names the resolved labels, or {@code null} to clear
     */
    public void applySettlementDisplayNames(SettlementDisplayNames names) {
        this.city = names == null ? null : names.city();
        this.region = names == null ? null : names.region();
    }

    /** The current pointers of one image slot (Phase 343). */
    public SalonImagePointer imagePointer(SalonImageSlot slot) {
        return switch (slot) {
            case LOGO -> new SalonImagePointer(avatarUrl, avatarR2Key);
            case COVER -> new SalonImagePointer(coverImageUrl, coverR2Key);
        };
    }

    /**
     * Replaces one image slot's pointers IN MEMORY and returns the superseded pair. Persisting is the
     * caller's job via {@code SalonRepository#writeLogoPointers}/{@code #writeCoverPointers} — the columns
     * are {@code updatable = false} (see the field block). {@code (null, null)} clears the slot.
     */
    public SalonImagePointer replaceImage(SalonImageSlot slot, String url, String key) {
        SalonImagePointer previous = imagePointer(slot);
        switch (slot) {
            case LOGO -> {
                this.avatarUrl = url;
                this.avatarR2Key = key;
            }
            case COVER -> {
                this.coverImageUrl = url;
                this.coverR2Key = key;
            }
        }
        return previous;
    }
}
