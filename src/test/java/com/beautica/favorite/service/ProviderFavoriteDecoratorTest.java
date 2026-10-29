package com.beautica.favorite.service;

import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ProviderFavoriteDecorator}: who gets queried, how many queries a page
 * costs, and that the (cached) inputs are never mutated. The cross-client cache-leak proof
 * against a real cache is {@code ProviderFavoriteCacheIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderFavoriteDecorator")
class ProviderFavoriteDecoratorTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    private ProviderFavoriteDecorator decorator;

    @BeforeEach
    void setUp() {
        decorator = new ProviderFavoriteDecorator(favoriteRepository);
    }

    // ── single profiles ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("master detail: null Authentication → input returned untouched, isFavorite=false, no query")
    void should_returnInputUntouched_when_authenticationIsNull() {
        MasterDetailResponse detail = masterDetail(UUID.randomUUID());

        MasterDetailResponse result = decorator.decorate(detail, null);

        assertThat(result).isSameAs(detail);
        assertThat(result.isFavorite()).isFalse();
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("master detail: anonymous principal → untouched, no query")
    void should_returnInputUntouched_when_callerIsAnonymous() {
        MasterDetailResponse detail = masterDetail(UUID.randomUUID());
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        MasterDetailResponse result = decorator.decorate(detail, anonymous);

        assertThat(result).isSameAs(detail);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("master detail: non-CLIENT principal (SALON_OWNER) → untouched, no query")
    void should_returnInputUntouched_when_callerIsNotClient() {
        MasterDetailResponse detail = masterDetail(UUID.randomUUID());

        MasterDetailResponse result = decorator.decorate(detail, principal(UUID.randomUUID(), "ROLE_SALON_OWNER"));

        assertThat(result).isSameAs(detail);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("master detail: CLIENT token without UUID details → untouched, no query")
    void should_returnInputUntouched_when_clientTokenHasNoUserId() {
        MasterDetailResponse detail = masterDetail(UUID.randomUUID());
        Authentication noDetails = new UsernamePasswordAuthenticationToken(
                "c@test", null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));

        MasterDetailResponse result = decorator.decorate(detail, noDetails);

        assertThat(result).isSameAs(detail);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("master detail: CLIENT who favourited the master → a NEW response with isFavorite=true")
    void should_flagMasterDetail_when_clientFavoritedIt() {
        UUID clientId = UUID.randomUUID();
        MasterDetailResponse detail = masterDetail(UUID.randomUUID());
        when(favoriteRepository.isFavorited(
                clientId, FavoriteTargetType.MASTER, detail.masterId())).thenReturn(true);

        MasterDetailResponse result = decorator.decorate(detail, client(clientId));

        assertThat(result.isFavorite()).isTrue();
        assertThat(result.withIsFavorite(false)).isEqualTo(detail);
        assertThat(detail.isFavorite()).as("the cached input is never mutated").isFalse();
    }

    @Test
    @DisplayName("master detail: CLIENT who did not favourite the master → isFavorite=false, input returned as-is")
    void should_returnFalse_when_clientDidNotFavoriteMaster() {
        UUID clientId = UUID.randomUUID();
        MasterDetailResponse detail = masterDetail(UUID.randomUUID());
        when(favoriteRepository.isFavorited(
                clientId, FavoriteTargetType.MASTER, detail.masterId())).thenReturn(false);

        MasterDetailResponse result = decorator.decorate(detail, client(clientId));

        assertThat(result.isFavorite()).isFalse();
        assertThat(result).as("not favourited → the cached input itself, no copy").isSameAs(detail);
    }

    @Test
    @DisplayName("salon profile: CLIENT who favourited the salon → isFavorite=true, keyed as SALON")
    void should_flagSalon_when_clientFavoritedIt() {
        UUID clientId = UUID.randomUUID();
        PublicSalonResponse salon = salon(UUID.randomUUID());
        when(favoriteRepository.isFavorited(
                clientId, FavoriteTargetType.SALON, salon.id())).thenReturn(true);

        PublicSalonResponse result = decorator.decorate(salon, client(clientId));

        assertThat(result.isFavorite()).isTrue();
        assertThat(result.withIsFavorite(false)).isEqualTo(salon);
    }

    @Test
    @DisplayName("salon profile: CLIENT who did not favourite the salon → input returned as-is")
    void should_returnSalonAsIs_when_clientDidNotFavoriteIt() {
        UUID clientId = UUID.randomUUID();
        PublicSalonResponse salon = salon(UUID.randomUUID());
        when(favoriteRepository.isFavorited(clientId, FavoriteTargetType.SALON, salon.id())).thenReturn(false);

        PublicSalonResponse result = decorator.decorate(salon, client(clientId));

        assertThat(result).isSameAs(salon);
    }

    @Test
    @DisplayName("salon profile: anonymous → untouched, no query")
    void should_returnSalonUntouched_when_authenticationIsNull() {
        PublicSalonResponse salon = salon(UUID.randomUUID());

        PublicSalonResponse result = decorator.decorate(salon, null);

        assertThat(result).isSameAs(salon);
        verifyNoInteractions(favoriteRepository);
    }

    // ── search pages — one batched query, never N ───────────────────────────────

    @Test
    @DisplayName("master search page: CLIENT → exactly ONE IN-query over all page ids, flags per card")
    void should_flagMasterPageWithOneQuery_when_callerIsClient() {
        UUID clientId = UUID.randomUUID();
        List<MasterSearchResult> page = List.of(
                masterCard(UUID.randomUUID()), masterCard(UUID.randomUUID()), masterCard(UUID.randomUUID()));
        List<UUID> ids = page.stream().map(MasterSearchResult::masterId).toList();
        when(favoriteRepository.findFavoritedTargetIds(clientId, FavoriteTargetType.MASTER, ids))
                .thenReturn(Set.of(ids.get(1)));

        List<MasterSearchResult> result = decorator.decorateMasters(page, client(clientId));

        assertThat(result).extracting(MasterSearchResult::isFavorite).containsExactly(false, true, false);
        assertThat(result).extracting(MasterSearchResult::masterId).containsExactlyElementsOf(ids);
        assertThat(page).as("cached page records are never mutated")
                .allSatisfy(card -> assertThat(card.isFavorite()).isFalse());
        verify(favoriteRepository, times(1)).findFavoritedTargetIds(clientId, FavoriteTargetType.MASTER, ids);
        verifyNoMoreInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("salon search page: CLIENT → exactly ONE IN-query keyed as SALON, flags per card")
    void should_flagSalonPageWithOneQuery_when_callerIsClient() {
        UUID clientId = UUID.randomUUID();
        List<SalonSearchResult> page = List.of(salonCard(UUID.randomUUID()), salonCard(UUID.randomUUID()));
        List<UUID> ids = page.stream().map(SalonSearchResult::salonId).toList();
        when(favoriteRepository.findFavoritedTargetIds(clientId, FavoriteTargetType.SALON, ids))
                .thenReturn(Set.of(ids.get(0)));

        List<SalonSearchResult> result = decorator.decorateSalons(page, client(clientId));

        assertThat(result).extracting(SalonSearchResult::isFavorite).containsExactly(true, false);
        verify(favoriteRepository, times(1)).findFavoritedTargetIds(clientId, FavoriteTargetType.SALON, ids);
        verifyNoMoreInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("search page: CLIENT with no favourite on the page → the cached page returned as-is")
    void should_returnPageAsIs_when_noCardIsFavorited() {
        UUID clientId = UUID.randomUUID();
        List<MasterSearchResult> page = List.of(masterCard(UUID.randomUUID()), masterCard(UUID.randomUUID()));
        List<UUID> ids = page.stream().map(MasterSearchResult::masterId).toList();
        when(favoriteRepository.findFavoritedTargetIds(clientId, FavoriteTargetType.MASTER, ids))
                .thenReturn(Set.of());

        List<MasterSearchResult> result = decorator.decorateMasters(page, client(clientId));

        assertThat(result).isSameAs(page);
    }

    @Test
    @DisplayName("search page: empty page → returned untouched, no query with an empty IN list")
    void should_skipQuery_when_pageIsEmpty() {
        List<MasterSearchResult> empty = List.of();

        List<MasterSearchResult> result = decorator.decorateMasters(empty, client(UUID.randomUUID()));

        assertThat(result).isSameAs(empty);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("search page: non-CLIENT caller → returned untouched, no query")
    void should_returnPageUntouched_when_callerIsNotClient() {
        List<SalonSearchResult> page = List.of(salonCard(UUID.randomUUID()));

        List<SalonSearchResult> result = decorator.decorateSalons(page, principal(UUID.randomUUID(), "ROLE_INDEPENDENT_MASTER"));

        assertThat(result).isSameAs(page);
        verifyNoInteractions(favoriteRepository);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static Authentication client(UUID clientId) {
        return principal(clientId, "ROLE_CLIENT");
    }

    private static Authentication principal(UUID userId, String role) {
        var token = new UsernamePasswordAuthenticationToken(
                "user@test", null, List.of(new SimpleGrantedAuthority(role)));
        token.setDetails(userId);
        return token;
    }

    private static MasterDetailResponse masterDetail(UUID masterId) {
        return new MasterDetailResponse(
                masterId, "Oksana", "Kovalenko", null, null, null, null, null, null, null, null,
                null, null, 0, MasterType.INDEPENDENT_MASTER, null, List.of(), null, null, null,
                null, null, null, null);
    }

    private static PublicSalonResponse salon(UUID salonId) {
        return new PublicSalonResponse(
                salonId, "Salon", null, null, null, null, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null, null, null, null, 0, null, null);
    }

    private static MasterSearchResult masterCard(UUID masterId) {
        return new MasterSearchResult(
                masterId, "Olha", "M", "Київ", null, null, 0, null,
                BigDecimal.TEN, BigDecimal.TEN, List.of(), null, null, null, List.of());
    }

    private static SalonSearchResult salonCard(UUID salonId) {
        return new SalonSearchResult(
                salonId, "Salon", "Львів", null, null, BigDecimal.ONE, BigDecimal.TEN,
                List.of(), null, null, null, List.of());
    }
}
