package com.beautica.service.repository;

import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.entity.ServiceTypeSuggestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link ServiceTypeSuggestion}. Mirrors the token-lookup +
 * dedup-existence shape of {@code PlatformCategoryRepository}.
 */
public interface ServiceTypeSuggestionRepository extends JpaRepository<ServiceTypeSuggestion, UUID> {

    /**
     * Token lookup for the approve/reject POST. Matched on the hashed token only;
     * expiry, status, and the constant-time comparison are enforced in the service.
     * Returns at most one row — token_hash is effectively unique while PENDING.
     */
    Optional<ServiceTypeSuggestion> findByTokenHash(String tokenHash);

    /**
     * Dedup guard: returns {@code true} when a PENDING suggestion already exists for
     * the same category slug and (case-insensitively) the same suggested name. A
     * previously decided (APPROVED/REJECTED) suggestion does not block a re-suggest.
     *
     * <p>{@code LOWER(...)} normalization mirrors the partial dedup index in V76.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM ServiceTypeSuggestion s
             WHERE s.status = com.beautica.service.entity.ServiceTypeSuggestionStatus.PENDING
               AND s.categoryName = :categoryName
               AND LOWER(s.suggestedName) = LOWER(:suggestedName)
            """)
    boolean existsPendingByCategoryNameAndSuggestedNameIgnoreCase(
            @Param("categoryName") String categoryName,
            @Param("suggestedName") String suggestedName);
}
