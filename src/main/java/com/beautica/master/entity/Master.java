package com.beautica.master.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
        name = "masters",
        indexes = {
                // Backs owner-master lookup (Phase 12.2/12.4) and dashboard scope join.
                // DB index is partial: WHERE master_type = 'SALON_OWNER' AND is_active = true (V56).
                // JPA @Index cannot express partial WHERE — column list only.
                @Index(name = "idx_masters_salon_owner_active", columnList = "salon_id, user_id"),
                // V56: partial unique — one SALON_OWNER-type master row per user
                // (WHERE master_type = 'SALON_OWNER'). DB index is partial unique; using unique=true
                // here would generate a full unique constraint under ddl-auto=create-drop, which is
                // wrong — omit unique=true. JPA @Index cannot express partial WHERE — column list only.
                @Index(name = "idx_masters_user_owner_type", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Master extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salon_id")
    private Salon salon;

    @Enumerated(EnumType.STRING)
    @Column(name = "master_type", nullable = false)
    private MasterType masterType;

    @Column(name = "avg_rating")
    private BigDecimal avgRating;

    @Column(name = "review_count")
    private int reviewCount;

    @Column(name = "is_active")
    private boolean isActive;

    /**
     * Pre-computed minimum effective price for this master — the lowest of
     * {@code MIN(COALESCE(ms.price_override, sd.base_price))} across all
     * active service assignments. {@code null} means the master has no active
     * services.
     *
     * <p>Maintained by {@link com.beautica.service.service.ServiceCatalogService}
     * whenever a master service assignment is created, removed, or when a service
     * definition is deactivated. Read by the search query (PERF-M2, V58) to avoid
     * the per-request aggregate join.
     */
    @Nullable
    @Column(name = "min_effective_price", precision = 10, scale = 2)
    private BigDecimal minEffectivePrice;
}
