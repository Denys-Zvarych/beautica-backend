package com.beautica.favorite.service;

import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.repository.FavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic write unit for a new {@link Favorite} row, deliberately split out of
 * {@link FavoriteService} — same pattern as {@code auth.InvitePersistenceService}.
 *
 * <p><strong>Why a separate bean (CORRECTNESS):</strong> {@code FavoriteService#addFavorite} is
 * {@code @Transactional} end-to-end. When a concurrent double-submit races past the pre-check and
 * the losing caller's {@code saveAndFlush} hits {@code uq_favorite}, Postgres marks that session's
 * transaction ABORTED at the protocol level, not just the one statement. If the insert attempt and
 * the caller's recovery re-read shared that transaction, the re-read would itself fail with
 * {@code 25P02 current transaction is aborted}, surfacing as a {@code JpaSystemException} (500)
 * instead of the intended graceful idempotent return.
 *
 * <p>{@link Propagation#REQUIRES_NEW} runs the insert attempt in its own physical
 * transaction/connection, suspending the caller's. A {@code uq_favorite} violation rolls back
 * ONLY this inner transaction; the exception then propagates to
 * {@link FavoriteService#insertFavorite}, which catches it and re-reads on its own — still-healthy
 * — transaction. Self-invocation from {@code FavoriteService} would NOT go through the
 * {@code @Transactional} proxy at all (same-bean calls bypass it), hence the dedicated bean.
 */
@Service
@RequiredArgsConstructor
class FavoritePersistenceService {

    private final FavoriteRepository favoriteRepository;

    /**
     * Persists {@code favorite} in a brand-new transaction and flushes immediately so a
     * {@code uq_favorite} violation surfaces here, inside this method's own transaction, rather
     * than at the caller's later commit.
     *
     * @throws DataIntegrityViolationException on a concurrent duplicate; the caller resolves this
     *                                          by re-reading the now-present row in its own,
     *                                          unaffected transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Favorite persistNew(Favorite favorite) {
        return favoriteRepository.saveAndFlush(favorite);
    }
}
