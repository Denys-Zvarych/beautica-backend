package com.beautica.favorite.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A CLIENT's favorite — a polymorphic like on an independent master
 * ({@link FavoriteTargetType#MASTER}, {@code targetId == masters.id}) or a salon
 * ({@link FavoriteTargetType#SALON}, {@code targetId == salons.id}).
 *
 * <p>Does <b>not</b> extend {@code AuditableEntity}: the {@code favorites} table
 * has only {@code created_at} (a favorite is created/deleted, never mutated, so
 * there is no {@code updated_at}). Lombok {@code @Setter} is intentionally absent
 * — the entity is immutable after construction (rule: entities have no public
 * setters; use the static factory).
 */
@Entity
@Table(
        name = "favorites",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_favorite",
                columnNames = {"client_id", "target_type", "target_id"}
        ),
        indexes = @Index(name = "idx_favorites_client", columnList = "client_id, target_type")
)
@Getter
@NoArgsConstructor
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private FavoriteTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Favorite(UUID clientId, FavoriteTargetType targetType, UUID targetId) {
        this.clientId = clientId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    /**
     * Creates a new favorite for the given client and target. {@code id} and
     * {@code createdAt} are assigned on persist (DB default / Hibernate).
     */
    public static Favorite of(UUID clientId, FavoriteTargetType targetType, UUID targetId) {
        return new Favorite(clientId, targetType, targetId);
    }
}
