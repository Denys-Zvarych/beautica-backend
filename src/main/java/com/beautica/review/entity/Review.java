package com.beautica.review.entity;

import com.beautica.booking.entity.Booking;
import com.beautica.common.AuditableEntity;
import com.beautica.master.entity.Master;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "reviews",
        indexes = {
                @Index(name = "idx_reviews_master_created", columnList = "master_id, created_at DESC"),
                @Index(name = "idx_reviews_client_created", columnList = "client_id, created_at DESC"),
                // Phase 8.11 master rating sorts (V116) — plain indexes, mirror-able here because
                // master_id is NOT NULL (no partial predicate).
                @Index(name = "idx_reviews_master_rating_desc", columnList = "master_id, rating DESC, created_at DESC"),
                @Index(name = "idx_reviews_master_rating_asc", columnList = "master_id, rating ASC, created_at DESC"),
                // idx_reviews_salon_created and idx_reviews_independent_master are partial indexes
                // (WHERE salon_id IS NOT NULL / IS NULL) — defined in V40/V41, not expressible in JPA @Index.
                // idx_reviews_salon_rating_desc/asc (V104) are likewise partial (WHERE salon_id IS NOT NULL).
                // idx_reviews_salon_master_rating (V141) — (salon_id, master_id, rating)
                // WHERE salon_id IS NOT NULL. Also partial, so also not mirror-able here. It backs the
                // nested per-master aggregate mobile Phase 111 introduced
                // (ReviewRepository#recalculateSalonRating and #countBySalonIdGroupByRating): it supplies
                // the GROUP BY master_id order and makes the scan index-only, removing the Sort node and
                // all heap fetches. idx_reviews_auto_hidden_at (V209, WHERE auto_hidden_at IS NOT NULL) is partial
                // too: it backs the phase-396 per-provider auto-hide cap count.
                // Documented here so ddl-auto=validate drift on this table stays
                // explained rather than looking like an unmirrored index.
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = lombok.AccessLevel.PACKAGE)
@Builder
public class Review extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private User client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id", nullable = false)
    private Master master;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salon_id")
    private Salon salon;

    @NotNull
    @Min(1)
    @Max(5)
    @Column(nullable = false)
    private Short rating;

    @Size(max = 2000)
    @Column(length = 2000)
    private String comment;

    /**
     * Phase 396 D1. Public reads and rating aggregates include only {@link ModerationStatus#VISIBLE}. Changed only
     * through {@link #moderate}, by {@code ReviewModerationService}.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 16)
    private ModerationStatus moderationStatus = ModerationStatus.VISIBLE;

    /**
     * Phase 396 (V209): when an auto-hide last moved this review to {@link ModerationStatus#HIDDEN_PENDING}. Never
     * cleared by a restore — it records the transition, which the per-provider auto-hide cap counts.
     */
    @Column(name = "auto_hidden_at")
    private Instant autoHiddenAt;

    /**
     * Moves this review to {@code target}. {@link ModerationStatus#REMOVED} is terminal: a late RESTORE / DISMISS
     * (e.g. on a report that raced the takedown) never brings removed content back.
     *
     * @return {@code true} when the status changed (the caller then publishes a recalculation event)
     */
    public boolean moderate(ModerationStatus target) {
        if (moderationStatus == target || moderationStatus == ModerationStatus.REMOVED) {
            return false;
        }
        moderationStatus = target;
        return true;
    }

    /**
     * The auto-hide transition: {@link #moderate} to {@link ModerationStatus#HIDDEN_PENDING}, stamping
     * {@link #autoHiddenAt} when it changed.
     *
     * @return {@code true} when the status changed
     */
    public boolean autoHide(Instant now) {
        if (!moderate(ModerationStatus.HIDDEN_PENDING)) {
            return false;
        }
        autoHiddenAt = now;
        return true;
    }
}
