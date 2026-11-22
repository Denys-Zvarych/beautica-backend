package com.beautica.client.provider;

import com.beautica.auth.Role;
import com.beautica.client.provider.service.ClientScope;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import com.beautica.client.provider.service.ProviderClientScopeResolver;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.moderation.service.BlockScope;
import com.beautica.salon.entity.Salon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderClientScopeResolver — B507")
class ProviderClientScopeResolverTest {

    @Mock private MasterRepository masterRepository;
    @InjectMocks private ProviderClientScopeResolver resolver;

    private final UUID actorId = UUID.randomUUID();
    private final UUID salonId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();

    @Test
    void should_returnWholeSalonScope_when_noMasterIdGiven() {
        ClientScope scope = resolver.forSalon(salonId, null);

        assertThat(scope).isEqualTo(new SalonScope(salonId, null));
        assertThat(scope.blockScope()).isEqualTo(BlockScope.salon(salonId));
        verifyNoInteractions(masterRepository);
    }

    @Test
    void should_narrowToMaster_when_masterBelongsToSalon() {
        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);

        assertThat(resolver.forSalon(salonId, masterId)).isEqualTo(new SalonScope(salonId, masterId));
    }

    @Test
    void should_throwNotFound_when_masterIsOfAnotherSalon() {
        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(false);

        assertThatThrownBy(() -> resolver.forSalon(salonId, masterId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void should_returnSalonBlockScope_when_salonMasterHasActiveRow() {
        Master master = masterOf(salonId);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(actorId)).thenReturn(Optional.of(master));

        ClientScope scope = resolver.forMasterMe(actorId, Role.SALON_MASTER);

        assertThat(scope).isEqualTo(new MasterScope(masterId, salonId));
        assertThat(scope.blockScope()).isEqualTo(BlockScope.salon(salonId));
    }

    @Test
    void should_returnMasterBlockScope_when_independentMasterHasActiveRow() {
        Master master = masterOf(null);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(actorId)).thenReturn(Optional.of(master));

        ClientScope scope = resolver.forMasterMe(actorId, Role.INDEPENDENT_MASTER);

        assertThat(scope).isEqualTo(new MasterScope(masterId, null));
        assertThat(scope.blockScope()).isEqualTo(BlockScope.master(masterId));
    }

    @Test
    void should_throwForbidden_when_masterRowMissingOrDeactivated() {
        when(masterRepository.findActiveByUserIdWithUserAndSalon(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.forMasterMe(actorId, Role.SALON_MASTER))
                .isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN", "CLIENT"})
    void should_throwForbiddenWithoutLookup_when_roleIsNotAMasterRole(Role role) {
        assertThatThrownBy(() -> resolver.forMasterMe(actorId, role)).isInstanceOf(ForbiddenException.class);

        verify(masterRepository, never()).findActiveByUserIdWithUserAndSalon(actorId);
    }

    private Master masterOf(UUID salon) {
        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        if (salon != null) {
            Salon salonEntity = mock(Salon.class);
            when(salonEntity.getId()).thenReturn(salon);
            when(master.getSalon()).thenReturn(salonEntity);
        }
        return master;
    }
}
