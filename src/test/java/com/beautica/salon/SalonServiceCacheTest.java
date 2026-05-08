package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.CacheConfig;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {SalonService.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("SalonService — @Cacheable/@CacheEvict behaviour")
class SalonServiceCacheTest {

    @MockBean SalonRepository salonRepository;
    @MockBean UserRepository userRepository;
    @MockBean InviteService inviteService;
    @MockBean MasterRepository masterRepository;
    @MockBean AuthorizationService authorizationService;

    @Autowired SalonService salonService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("ownerSalons").clear();
    }

    @Test
    @DisplayName("second call to getOwnerSalons returns cached result without hitting repository")
    void should_notHitRepository_when_getOwnerSalonsCalledTwice() {
        UUID ownerId = UUID.randomUUID();
        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());

        salonService.getOwnerSalons(ownerId);
        salonService.getOwnerSalons(ownerId);

        verify(salonRepository, times(1)).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    @Test
    @DisplayName("deactivateSalon evicts the cache so the next getOwnerSalons call re-queries the repository")
    void should_evictCache_when_deactivateSalonCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User owner = new User("owner@example.com", "hash", Role.SALON_OWNER, "Test", "Owner", "+380501234567");
        var salon = Mockito.mock(com.beautica.salon.entity.Salon.class);

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(salon)).thenReturn(salon);

        // Populate cache
        salonService.getOwnerSalons(ownerId);
        // Evict cache via deactivateSalon
        salonService.deactivateSalon(ownerId, salonId);
        // Cache was evicted — repository must be queried again
        salonService.getOwnerSalons(ownerId);

        verify(salonRepository, times(2)).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }
}
