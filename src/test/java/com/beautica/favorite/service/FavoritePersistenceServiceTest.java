package com.beautica.favorite.service;

import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoritePersistenceService} — the {@code REQUIRES_NEW} write unit
 * extracted from {@link FavoriteService} to fix the {@code 25P02} aborted-transaction bug (see
 * that class's javadoc and {@code FavoriteSalonServiceIT}'s real-Postgres concurrency proof).
 *
 * <p>Before this class existed, {@code persistNew} had NO direct test: it was only exercised
 * transitively — as a Mockito mock inside {@code FavoriteServiceTest}, or indirectly through
 * Testcontainers in the {@code *IT} suites. Neither proves {@code persistNew} itself behaves
 * correctly in isolation, and neither would fail fast (without booting a container) if someone
 * "simplified" its {@code REQUIRES_NEW} annotation away — exactly the defect the mutation test
 * for this fix simulates. The reflection checks below close that gap cheaply: no Spring context,
 * no database, sub-second.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FavoritePersistenceService — unit")
class FavoritePersistenceServiceTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    private FavoritePersistenceService service;

    @Test
    @DisplayName("persistNew returns the repository's saved row unchanged")
    void should_returnSavedFavorite_when_saveAndFlushSucceeds() {
        service = new FavoritePersistenceService(favoriteRepository);
        UUID clientId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Favorite toSave = Favorite.of(clientId, FavoriteTargetType.MASTER, targetId);
        Favorite saved = Favorite.of(clientId, FavoriteTargetType.MASTER, targetId);
        when(favoriteRepository.saveAndFlush(toSave)).thenReturn(saved);

        Favorite result = service.persistNew(toSave);

        assertThat(result).isSameAs(saved);
    }

    @Test
    @DisplayName("persistNew delegates to saveAndFlush, not save — the immediate flush is what "
            + "forces the uq_favorite check inside this method's own transaction")
    void should_callSaveAndFlush_when_persisting() {
        service = new FavoritePersistenceService(favoriteRepository);
        Favorite toSave = Favorite.of(UUID.randomUUID(), FavoriteTargetType.SALON, UUID.randomUUID());
        when(favoriteRepository.saveAndFlush(any(Favorite.class))).thenReturn(toSave);

        service.persistNew(toSave);

        verify(favoriteRepository).saveAndFlush(toSave);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    @DisplayName("persistNew propagates DataIntegrityViolationException from a uq_favorite "
            + "violation unchanged — the caller (FavoriteService.insertFavorite) is the one that "
            + "catches and resolves it, on its own separate transaction")
    void should_propagateDataIntegrityViolationException_when_saveAndFlushTripsUniqueGuard() {
        service = new FavoritePersistenceService(favoriteRepository);
        Favorite toSave = Favorite.of(UUID.randomUUID(), FavoriteTargetType.SERVICE, UUID.randomUUID());
        when(favoriteRepository.saveAndFlush(toSave))
                .thenThrow(new DataIntegrityViolationException("uq_favorite"));

        assertThatThrownBy(() -> service.persistNew(toSave))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_favorite");
    }

    // ── annotation guard ──────────────────────────────────────────────────────────
    //
    // Pure reflection, no Spring context: this is the fast, deterministic net for the exact
    // regression the mutation test proved end-to-end (collapsing REQUIRES_NEW to the default
    // REQUIRED reintroduces the 25P02 bug, but only visibly so under genuine two-thread
    // concurrency against a real Postgres — see FavoriteSalonServiceIT). A future refactor that
    // drops or weakens the propagation, or inlines persistNew as a same-bean call (which would
    // bypass the Spring proxy entirely, silently disabling REQUIRES_NEW), fails HERE in
    // milliseconds instead of only in the slow Testcontainers suite — and unlike that suite, this
    // check cannot be "accidentally" green because the race didn't happen to interleave this run.

    @Test
    @DisplayName("persistNew is annotated @Transactional(propagation = REQUIRES_NEW) — the "
            + "annotation IS the fix; silently weakening it to the default REQUIRED reintroduces "
            + "the 25P02 aborted-transaction bug this class exists to prevent")
    void should_haveRequiresNewPropagation_when_persistNewDeclared() throws NoSuchMethodException {
        Method persistNew = FavoritePersistenceService.class.getDeclaredMethod("persistNew", Favorite.class);

        Transactional annotation = persistNew.getAnnotation(Transactional.class);

        assertThat(annotation)
                .as("persistNew must stay annotated @Transactional")
                .isNotNull();
        assertThat(annotation.propagation())
                .as("propagation must stay REQUIRES_NEW, or a concurrent duplicate's re-read runs "
                        + "on an aborted transaction again")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("FavoritePersistenceService carries a Spring @Service stereotype — without a "
            + "component-scanned bean there is no AOP proxy, and REQUIRES_NEW is silently ignored "
            + "on a plain Java object")
    void should_beSpringManagedService_when_classDeclared() {
        assertThat(FavoritePersistenceService.class.isAnnotationPresent(org.springframework.stereotype.Service.class))
                .as("no @Service means no proxy means REQUIRES_NEW never takes effect")
                .isTrue();
    }

    @Test
    @DisplayName("persistNew stays package-private — a same-bean (this.) call from FavoriteService "
            + "would bypass the @Transactional proxy entirely and silently disable REQUIRES_NEW; a "
            + "public method invites exactly that self-invocation mistake")
    void should_stayPackagePrivate_when_persistNewDeclared() throws NoSuchMethodException {
        Method persistNew = FavoritePersistenceService.class.getDeclaredMethod("persistNew", Favorite.class);

        int modifiers = persistNew.getModifiers();

        assertThat(Modifier.isPublic(modifiers) || Modifier.isPrivate(modifiers) || Modifier.isProtected(modifiers))
                .as("persistNew must be package-private (no public/private/protected modifier) so "
                        + "only a cross-bean, proxy-routed call from another class in this package "
                        + "can reach it")
                .isFalse();
    }
}
