package com.beautica.review.invite.repository;

import com.beautica.review.invite.entity.ReviewInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Phase 399 — data access for {@link ReviewInvite}. Schema-only phase: the create (400 D1), due-row claim with
 * {@code FOR UPDATE SKIP LOCKED} (400 D4), token lookup / atomic consume (401 D2/D4) and retention purge (402 D3)
 * queries are added by the phases that own them. Unscoped by design: invites are never read on behalf of an
 * authenticated user.
 */
public interface ReviewInviteRepository extends JpaRepository<ReviewInvite, UUID> {
}
