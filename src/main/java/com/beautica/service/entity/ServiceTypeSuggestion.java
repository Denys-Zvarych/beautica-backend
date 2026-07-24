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
 * suggestion must never appear in the catalog/picker. A suggestion is a PENDING row
 * carrying a hashed single-use token; a platform admin approves or rejects it via an
 * emailed token-authenticated link. Approve marks the record {@code APPROVED}; the
 * catalog/service materialization (a {@code service_types} row + a draft
 * {@code service_definitions} row for the requesting master) is performed by
 * {@code ServiceTypeSuggestionService.decide} via {@code ServiceTypePromotionService}
 * (Phase 16.9, supersedes the Phase 16.8 "no auto-promotion" decision). This entity's
 * mutators never touch repositories.
 *
 * <p>Mirrors {@link PlatformCategory} verbatim for security properties:
 * <ul>
 *   <li>Raw token never persisted — only {@code SHA-256(token)} hex is stored.</li>
 *   <li>7-day expiry; single-use (token nulled on decision).</li>
 *   <li>State transitions go through factory methods and the
 *       {@link #approve(OffsetDateTime)} / {@link #reject(OffsetDateTime)} mutators;
 *       no public setters.</li>
 * </ul>
 *
 * <p>No {@code @ToString} is generated — the token hash must never leak into logs.
 */
@Entity
@Table(
        name = "service_type_suggestion",
        indexes = {
                @Index(name = "idx_service_type_suggestion_token_hash", columnList = "token_hash")
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

    /** SHA-256 hex of the raw approval token; never the raw token. Single-use. */
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private ServiceTypeSuggestion(
            String categoryName,
            String suggestedName,
            String description,
            UUID requestedByUserId,
            String tokenHash,
            OffsetDateTime tokenExpiresAt) {
        this.categoryName = categoryName;
        this.suggestedName = suggestedName;
        this.description = description;
        this.requestedByUserId = requestedByUserId;
        this.status = ServiceTypeSuggestionStatus.PENDING;
        this.tokenHash = tokenHash;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    /**
     * Creates a PENDING suggestion: not promoted into the catalog until approval, with a
     * hashed single-use token and a 7-day expiry. On approval the suggestion is
     * materialized into a {@code service_types} row + a draft service for the requester
     * (Phase 16.9).
     *
     * @param tokenHash SHA-256 hex of the raw token (raw token is emailed, never stored)
     */
    public static ServiceTypeSuggestion ofPending(
            String categoryName,
            String suggestedName,
            String description,
            UUID requestedByUserId,
            String tokenHash,
            OffsetDateTime tokenExpiresAt) {
        return new ServiceTypeSuggestion(
                categoryName, suggestedName, description, requestedByUserId, tokenHash, tokenExpiresAt);
    }

    /**
     * Approves a PENDING suggestion: marks APPROVED and clears the token (single-use).
     * Caller guarantees the current status is PENDING. The catalog/service materialization
     * happens in {@code ServiceTypeSuggestionService.decide} via
     * {@code ServiceTypePromotionService} (Phase 16.9) — this mutator never touches
     * repositories.
     */
    public void approve(OffsetDateTime decidedAt) {
        this.status = ServiceTypeSuggestionStatus.APPROVED;
        this.decidedAt = decidedAt;
        clearToken();
    }

    /**
     * Rejects a PENDING suggestion: marks REJECTED and clears the token (single-use).
     * Caller guarantees the current status is PENDING.
     */
    public void reject(OffsetDateTime decidedAt) {
        this.status = ServiceTypeSuggestionStatus.REJECTED;
        this.decidedAt = decidedAt;
        clearToken();
    }

    public boolean isPending() {
        return this.status == ServiceTypeSuggestionStatus.PENDING;
    }

    private void clearToken() {
        this.tokenHash = null;
        this.tokenExpiresAt = null;
    }
}
