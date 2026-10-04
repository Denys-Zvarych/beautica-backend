package com.beautica.salon;

import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.SettlementDisplayNameResolver;
import com.beautica.location.service.LocationQueryService;
import com.beautica.master.repository.MasterCacheKeys;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.entity.SalonImagePointer;
import com.beautica.salon.entity.SalonImageSlot;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.service.SearchCacheNames;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.beautica.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 343 — the salon-side steps of the logo/cover write ({@link SalonService#requireOwnedActiveSalon},
 * {@link SalonService#replaceSalonImageLocked}, {@link SalonService#clearSalonImageLocked}): the OWNER-only
 * service gate (D2), the in-lock active re-check, the targeted pointer write and the after-commit cache set (D9).
 * Synchronizations are driven by hand so each eviction is observed only after a simulated commit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — salon logo/cover locked write (Phase 343)")
class SalonServiceSalonImageTest {

    private static final String NEW_URL = "https://cdn.example/new.jpg";

    @Mock private SalonRepository salonRepository;
    @Mock private AuthorizationService authorizationService;
    @Mock private MasterRepository masterRepository;
    @Mock private CacheManager cacheManager;
    @Mock private LocationQueryService locationQueryService;
    @Mock private SettlementDisplayNameResolver settlementDisplayNameResolver;

    @InjectMocks private SalonService salonService;

    private final UUID salonId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private Salon salon;

    @BeforeEach
    void setUp() {
        User owner = mock(User.class);
        lenient().when(owner.getId()).thenReturn(ownerId);
        salon = Salon.builder().id(salonId).owner(owner).name("S").cityId(UUID.randomUUID()).isActive(true)
                .avatarUrl("https://cdn.example/old.jpg").avatarR2Key("salons/" + salonId + "/logo/old.jpg")
                .build();
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("replace: writes the new pointers, returns the superseded pair and the new body")
    void should_writeNewPointersAndReturnSuperseded_when_ownerReplacesLogo() {
        String newKey = "salons/" + salonId + "/logo/new.jpg";
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));

        SalonService.SalonImageWrite write =
                salonService.replaceSalonImageLocked(ownerId, salonId, SalonImageSlot.LOGO, NEW_URL, newKey);

        verify(salonRepository).writeLogoPointers(salonId, NEW_URL, newKey);
        verifyNoInteractions(authorizationService);
        assertThat(write.superseded()).isEqualTo(
                new SalonImagePointer("https://cdn.example/old.jpg", "salons/" + salonId + "/logo/old.jpg"));
        assertThat(write.body().avatarUrl()).isEqualTo(NEW_URL);
    }

    @Test
    @DisplayName("TC-12 service layer: a non-owner (e.g. the assigned SALON_ADMIN) is refused under the lock — "
            + "no pointer write, no eviction registered")
    void should_throwForbiddenAndWriteNothing_when_callerIsNotOwner() {
        UUID adminId = UUID.randomUUID();
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));

        assertThatThrownBy(() -> salonService.replaceSalonImageLocked(
                adminId, salonId, SalonImageSlot.COVER, NEW_URL, "salons/" + salonId + "/cover/x.jpg"))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).writeCoverPointers(any(), any(), any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("under the lock a non-owner of a DEACTIVATED salon gets 403, not 404 — same 403-then-404 order "
            + "as the read-gate")
    void should_throwForbidden_when_nonOwnerAndSalonInactiveUnderLock() {
        salon.setActive(false);
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));

        assertThatThrownBy(() -> salonService.clearSalonImageLocked(UUID.randomUUID(), salonId, SalonImageSlot.LOGO))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).writeLogoPointers(any(), any(), any());
    }

    @Test
    @DisplayName("under the lock a missing row is 'not the owner' → 403, as in the read-gate")
    void should_throwForbidden_when_lockedRowMissing() {
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.clearSalonImageLocked(ownerId, salonId, SalonImageSlot.LOGO))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("perf: the master cache-key query runs BEFORE the salon row lock is taken")
    void should_readMasterCacheKeysBeforeLocking_when_replacing() {
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));

        salonService.replaceSalonImageLocked(ownerId, salonId, SalonImageSlot.COVER, NEW_URL,
                "salons/" + salonId + "/cover/new.jpg");

        InOrder order = inOrder(masterRepository, salonRepository);
        order.verify(masterRepository).findCacheKeysBySalonId(salonId);
        order.verify(salonRepository).findByIdForUpdate(salonId);
    }

    @Test
    @DisplayName("replace on a salon deactivated since the read-gate → 404 under the lock, nothing written")
    void should_throwNotFound_when_salonInactiveUnderLock() {
        salon.setActive(false);
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));

        assertThatThrownBy(() -> salonService.replaceSalonImageLocked(
                ownerId, salonId, SalonImageSlot.LOGO, NEW_URL, "salons/" + salonId + "/logo/x.jpg"))
                .isInstanceOf(NotFoundException.class);

        verify(salonRepository, never()).writeLogoPointers(any(), any(), any());
    }

    @Test
    @DisplayName("read-gate: ownership is checked before the active flag; an inactive salon is 404")
    void should_throwNotFound_when_readGateSeesInactiveSalon() {
        when(salonRepository.findIsActiveByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(false));

        assertThatThrownBy(() -> salonService.requireOwnedActiveSalon(ownerId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(salonRepository).findIsActiveByIdAndOwnerId(salonId, ownerId);
        verifyNoMoreInteractions(salonRepository);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("read-gate: the owner of an active salon passes on ONE query")
    void should_pass_when_readGateSeesOwnedActiveSalon() {
        when(salonRepository.findIsActiveByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(true));

        salonService.requireOwnedActiveSalon(ownerId, salonId);

        verify(salonRepository).findIsActiveByIdAndOwnerId(salonId, ownerId);
        verifyNoMoreInteractions(salonRepository);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("read-gate: a non-owner is 403 even though the probe never reveals the active flag")
    void should_throwForbidden_when_readGateCallerIsNotOwner() {
        UUID adminId = UUID.randomUUID();
        when(salonRepository.findIsActiveByIdAndOwnerId(salonId, adminId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.requireOwnedActiveSalon(adminId, salonId))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).existsByIdAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("D9 LOGO: after commit evicts ownerSalons(owner), salon-detail(salon), every affiliated master's "
            + "master-detail/master-detail-by-user, and (non-Caffeine fallback) clears both salon discovery caches")
    void should_evictEveryLogoRenderingCache_when_logoReplacedAndCommitted() {
        UUID masterId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));
        when(masterRepository.findCacheKeysBySalonId(salonId))
                .thenReturn(List.of(new MasterCacheKeys(masterId, masterUserId, "slug")));
        Cache ownerSalons = cache("ownerSalons");
        Cache salonDetail = cache("salon-detail");
        Cache masterDetail = cache("master-detail");
        Cache masterDetailByUser = cache("master-detail-by-user");
        List<Cache> search = SearchCacheNames.SALONS_ALL.stream().map(this::cache).toList();

        salonService.replaceSalonImageLocked(ownerId, salonId, SalonImageSlot.LOGO, NEW_URL,
                "salons/" + salonId + "/logo/new.jpg");
        verify(ownerSalons, never()).evict(any());
        commit();

        verify(ownerSalons).evict(ownerId);
        verify(salonDetail).evict(salonId);
        verify(masterDetail).evict(masterId);
        verify(masterDetailByUser).evict(masterUserId);
        search.forEach(c -> verify(c).clear());
    }

    @Test
    @DisplayName("perf: a LOGO change evicts only the discovery pages that list THIS salon — an unrelated "
            + "salon's cached page survives (no blanket clear on a Caffeine backend)")
    void should_evictOnlyPagesListingSalon_when_logoReplacedAndCommitted() {
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));
        lenient().when(cacheManager.getCache(any())).thenReturn(null);
        UUID otherSalonId = UUID.randomUUID();
        List<CaffeineCache> search = SearchCacheNames.SALONS_ALL.stream().map(name -> {
            CaffeineCache c = new CaffeineCache(name, Caffeine.newBuilder().build());
            c.put("hit", page(otherSalonId, salonId));
            c.put("miss", page(otherSalonId));
            c.put("other-type", "not a page");
            when(cacheManager.getCache(name)).thenReturn(c);
            return c;
        }).toList();

        salonService.replaceSalonImageLocked(ownerId, salonId, SalonImageSlot.LOGO, NEW_URL,
                "salons/" + salonId + "/logo/new.jpg");
        assertThat(search.get(0).get("hit")).as("nothing evicted before commit").isNotNull();
        commit();

        for (CaffeineCache c : search) {
            assertThat(c.get("hit")).as("%s page listing the salon", c.getName()).isNull();
            assertThat(c.get("miss")).as("%s unrelated salon's page", c.getName()).isNotNull();
            assertThat(c.get("other-type")).as("%s non-page entry", c.getName()).isNotNull();
        }
    }

    @Test
    @DisplayName("D9 COVER: discovery caches are NOT cleared (search rows never carry the cover)")
    void should_notClearSearchCaches_when_coverReplaced() {
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));
        Cache ownerSalons = cache("ownerSalons");
        Cache salonDetail = cache("salon-detail");
        List<Cache> search = SearchCacheNames.SALONS_ALL.stream().map(this::lenientCache).toList();

        salonService.replaceSalonImageLocked(ownerId, salonId, SalonImageSlot.COVER, NEW_URL,
                "salons/" + salonId + "/cover/new.jpg");
        commit();

        verify(ownerSalons).evict(ownerId);
        verify(salonDetail).evict(salonId);
        search.forEach(c -> verify(c, never()).clear());
    }

    @Test
    @DisplayName("clear on an empty slot: no write, no eviction, EMPTY returned")
    void should_doNothing_when_clearingEmptySlot() {
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));

        SalonImagePointer cleared = salonService.clearSalonImageLocked(ownerId, salonId, SalonImageSlot.COVER);

        assertThat(cleared.isEmpty()).isTrue();
        verify(salonRepository, never()).writeCoverPointers(any(), any(), any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    @DisplayName("clear on a set slot: both pointers nulled, the cleared pair returned")
    void should_nullPointersAndReturnCleared_when_clearingSetSlot() {
        when(salonRepository.findByIdForUpdate(salonId)).thenReturn(Optional.of(salon));

        SalonImagePointer cleared = salonService.clearSalonImageLocked(ownerId, salonId, SalonImageSlot.LOGO);

        assertThat(cleared.key()).isEqualTo("salons/" + salonId + "/logo/old.jpg");
        verify(salonRepository).writeLogoPointers(salonId, null, null);
        assertThat(salon.imagePointer(SalonImageSlot.LOGO).isEmpty()).isTrue();
    }

    private static Page<SalonSearchResult> page(UUID... salonIds) {
        List<SalonSearchResult> rows = java.util.Arrays.stream(salonIds)
                .map(id -> new SalonSearchResult(id, "S", null, null, null, null, null, List.of(), null, null, null,
                        List.of()))
                .toList();
        return new PageImpl<>(rows, PageRequest.of(0, 20), rows.size());
    }

    private Cache cache(String name) {
        Cache c = mock(Cache.class, name);
        when(cacheManager.getCache(name)).thenReturn(c);
        return c;
    }

    private Cache lenientCache(String name) {
        Cache c = mock(Cache.class, name);
        lenient().when(cacheManager.getCache(name)).thenReturn(c);
        return c;
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }
}
