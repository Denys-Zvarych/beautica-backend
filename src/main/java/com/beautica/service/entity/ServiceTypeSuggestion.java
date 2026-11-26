package com.beautica.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A persisted, reviewable service-type suggestion — the durable record behind the
 * (formerly fire-and-forget) {@code POST /service-types/suggest} path.
 *
 * <p>This is its OWN small table, NOT a row in {@code service_types}: an unreviewed
 * suggestion must never appear in the catalog/picker. A suggestion is a PENDING row; the
 * platform operator approves or rejects it in the catalog portal (B545: the operator email
 * only announces it). Approve marks the record {@code APPROVED}; the
 * catalog/service materialization (a {@code service_types} row + a draft
 * {@code service_definitions} row for the requesting master) is performed by
 * {@code ServiceTypeSuggestionService.decide} via {@code ServiceTypePromotionService}
 * (Phase 16.9, supersedes the Phase 16.8 "no auto-promotion" decision). This entity's
 * mutators never touch repositories.
 *
 * <p>State transitions go through the factory method and the {@link #approve(OffsetDateTime)} /
 * {@link #reject(OffsetDateTime)} mutators; no public setters.
 */
@Entity
@Table(
        name = "service_type_suggestion",
        indexes = {
                // V159 (phase 295 audit MEDIUM-6). Same ON DELETE SET NULL RI-trigger rationale as
                // PlatformCategory's index of the same name shape.
                @Index(name = "idx_service_type_suggestion_requested_by", columnList = "requested_by_user_id")
                // NOT mirrored here: V197's partial UNIQUE ux_service_type_suggestion_pending_dedup
                // ON (category_name, lower(suggested_name)) WHERE status = 'PENDING'. JPA @Index can
                // express neither an expression column nor a WHERE predicate, and an
                // @Index(unique = true) on the raw columns would claim a stricter, wrong constraint.
        }
)
// @DynamicInsert: created_at is populated by the DB DEFAULT NOW() (see V76). Without
// dynamic insert Hibernate emits the column with an explicit NULL, violating the
// NOT NULL constraint on every save() (a 500 on submitSuggestion). Dynamic insert
// omits null columns from the INSERT so the database default applies.
@DynamicInsert
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ServiceTypeSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Requester UUID; nullable (FK ON DELETE SET NULL — record outlives the user). */
    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    /** System-B platform-category slug the suggestion belongs to (validated active). */
    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    /** The suggested service-type name (free text, escaped on render). */
    @Column(name = "suggested_name", nullable = false, length = 255)
    private String suggestedName;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceTypeSuggestionStatus status = ServiceTypeSuggestionStatus.PENDING;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    /** Optional admin reason typed on the reject form (V196, max 300). Never logged. */
    @Column(name = "decision_reason", length = 300)
    private String decisionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private ServiceTypeSuggestion(
            String categoryName,
            String suggestedName,
            String description,
            UUID requestedByUserId) {
        this.categoryName = categoryName;
        this.suggestedName = suggestedName;
        this.description = description;
        this.requestedByUserId = requestedByUserId;
        this.status = ServiceTypeSuggestionStatus.PENDING;
    }

    /**
     * Creates a PENDING suggestion: not promoted into the catalog until approval. On approval the suggestion is
     * materialized into a {@code service_types} row + a draft service for the requester
     * (Phase 16.9).
     */
    public static ServiceTypeSuggestion ofPending(
            String categoryName,
            String suggestedName,
            String description,
            UUID requestedByUserId) {
        return new ServiceTypeSuggestion(categoryName, suggestedName, description, requestedByUserId);
    }

    /**
     * Approves a PENDING suggestion: marks APPROVED.
     * Caller guarantees the current status is PENDING. The catalog/service materialization
     * happens in {@code ServiceTypeSuggestionService.decide} via
     * {@code ServiceTypePromotionService} (Phase 16.9) — this mutator never touches
     * repositories.
     */
    public void approve(OffsetDateTime decidedAt) {
        this.status = ServiceTypeSuggestionStatus.APPROVED;
        this.decidedAt = decidedAt;
    }

    /**
     * Rejects a PENDING suggestion: marks REJECTED.
     * Caller guarantees the current status is PENDING.
     */
    public void reject(OffsetDateTime decidedAt) {
        reject(decidedAt, null);
    }

    /** Rejects with an optional, already-validated reason (null when none was given). */
    public void reject(OffsetDateTime decidedAt, String reason) {
        this.status = ServiceTypeSuggestionStatus.REJECTED;
        this.decidedAt = decidedAt;
        this.decisionReason = reason;
    }

    public boolean isPending() {
        return this.status == ServiceTypeSuggestionStatus.PENDING;
    }

    /**
     * B502 (SVC-55) — corrects the suggested name of a PENDING suggestion. Caller has already validated and canonicalised the name.
     *
     * @throws IllegalStateException when the suggestion is no longer PENDING (the service checks first)
     */
    public void renamePending(String canonicalName) {
        if (!isPending()) {
            throw new IllegalStateException("Only a PENDING suggestion can be renamed");
        }
        this.suggestedName = canonicalName;
    }
}
