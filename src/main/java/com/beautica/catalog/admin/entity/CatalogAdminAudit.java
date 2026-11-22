package com.beautica.catalog.admin.entity;

import com.beautica.catalog.admin.CatalogAuditTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One append-only catalog-admin audit row (B501, V264). {@link Immutable}: Hibernate never issues an UPDATE for it,
 * and the repository exposes no delete; V264's row trigger also rejects UPDATE/DELETE at the database.
 * {@code before}/{@code after} carry catalog fields only, never user PII.
 *
 * <p>Implements {@link Persistable} because the id is assigned in code: without it {@code save} would {@code merge},
 * i.e. issue a SELECT before every INSERT.
 */
@Entity
@Immutable
@Table(name = "catalog_admin_audit", indexes = {
        @Index(name = "idx_catalog_admin_audit_target", columnList = "target_type, target_id, created_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogAdminAudit implements Persistable<UUID> {

    public static final int ACTOR_MAX_LENGTH = 254;
    public static final int ACTION_MAX_LENGTH = 40;
    public static final int TARGET_ID_MAX_LENGTH = 100;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor", nullable = false, updatable = false, length = ACTOR_MAX_LENGTH)
    private String actor;

    @Column(name = "action", nullable = false, updatable = false, length = ACTION_MAX_LENGTH)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 30)
    private CatalogAuditTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false, length = TARGET_ID_MAX_LENGTH)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> after;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Persistence-state flag only, not a column; no Lombok getter (isNew() is the one reader). */
    @Transient
    @Getter(AccessLevel.NONE)
    private boolean fresh;

    private CatalogAdminAudit(String actor, String action, CatalogAuditTargetType targetType, String targetId,
                              Map<String, Object> before, Map<String, Object> after, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.before = before;
        this.after = after;
        this.createdAt = createdAt;
        this.fresh = true;
    }

    /** Arguments are validated by {@code CatalogAdminAuditService#record}; this factory only assembles the row. */
    public static CatalogAdminAudit of(String actor, String action, CatalogAuditTargetType targetType, String targetId,
                                       Map<String, Object> before, Map<String, Object> after, Instant createdAt) {
        return new CatalogAdminAudit(actor, action, targetType, targetId,
                snapshot(before), snapshot(after), createdAt);
    }

    /** Defensive copy that keeps {@code null} field values (a cleared {@code nameEn} is a real "before"). */
    private static Map<String, Object> snapshot(Map<String, Object> fields) {
        return fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        fresh = false;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }
}
