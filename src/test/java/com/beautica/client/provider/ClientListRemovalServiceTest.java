package com.beautica.client.provider;

import com.beautica.client.provider.repository.ClientListRemovalRepository;
import com.beautica.client.provider.service.ClientListRemovalService;
import com.beautica.client.provider.service.ClientListRemovalService.ListIdentity;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** B564 — {@link ClientListRemovalService}: the scope key (D1) picks the statement; the clock stamps the removal. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientListRemovalService — B564")
class ClientListRemovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final String PHONE = "+380501112233";

    @Mock private ClientListRemovalRepository repository;

    private ClientListRemovalService service;

    private final UUID salonId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ClientListRemovalService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("D1: a salon scope narrowed to a master still writes the SALON row (owner + admins share it)")
    void should_upsertSalonRow_when_salonScopeHasMasterNarrowing() {
        service.remove(new SalonScope(salonId, masterId), new ListIdentity.User(userId), actorId);

        verify(repository).upsertSalonUser(any(UUID.class), eq(salonId), eq(userId), eq(NOW), eq(actorId));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void should_upsertSalonGuestRow_when_guestRemovedFromSalonList() {
        service.remove(new SalonScope(salonId, null), new ListIdentity.Guest(PHONE), actorId);

        verify(repository).upsertSalonGuest(any(UUID.class), eq(salonId), eq(PHONE), eq(NOW), eq(actorId));
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("D1: a master scope writes the master row, never the master's salon")
    void should_upsertMasterRows_when_masterScope() {
        MasterScope scope = new MasterScope(masterId, salonId);

        service.remove(scope, new ListIdentity.User(userId), actorId);
        service.remove(scope, new ListIdentity.Guest(PHONE), actorId);

        verify(repository).upsertMasterUser(any(UUID.class), eq(masterId), eq(userId), eq(NOW), eq(actorId));
        verify(repository).upsertMasterGuest(any(UUID.class), eq(masterId), eq(PHONE), eq(NOW), eq(actorId));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void should_deleteScopeRow_when_restoring() {
        service.restore(new SalonScope(salonId, null), new ListIdentity.User(userId));
        service.restore(new SalonScope(salonId, null), new ListIdentity.Guest(PHONE));
        service.restore(new MasterScope(masterId, null), new ListIdentity.User(userId));
        service.restore(new MasterScope(masterId, null), new ListIdentity.Guest(PHONE));

        verify(repository).deleteSalonUser(salonId, userId);
        verify(repository).deleteSalonGuest(salonId, PHONE);
        verify(repository).deleteMasterUser(masterId, userId);
        verify(repository).deleteMasterGuest(masterId, PHONE);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void should_deleteRowsWithoutOpenBookings_when_purgingPhones() {
        when(repository.deleteGuestPhonesWithoutOpenBookings(List.of(PHONE))).thenReturn(2);

        int deleted = service.purgeGuestPhones(List.of(PHONE));

        assertThat(deleted).isEqualTo(2);
    }

    @Test
    void should_runNoStatement_when_purgingNoPhone() {
        int deleted = service.purgeGuestPhones(List.of());

        assertThat(deleted).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a guest identity never prints its phone")
    void should_redactPhone_when_guestIdentityPrinted() {
        assertThat(new ListIdentity.Guest(PHONE).toString()).doesNotContain("380");
    }
}
