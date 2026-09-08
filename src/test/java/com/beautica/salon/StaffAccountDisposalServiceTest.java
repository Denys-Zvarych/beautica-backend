package com.beautica.salon;

import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.service.StaffAccountDisposalService;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Closes the unit-pin gap the phase-301 build-verifier flagged: two rewritten tests
 * ({@code SalonServiceRemoveAdminTest}, {@code ClientAccountDeletionServiceTest}) carry comments
 * claiming the promoted {@code SalonService#disposeStaffAccounts} body is "pinned by
 * StaffAccountDisposalServiceTest" — this class is that pin, written for the first time here.
 *
 * <p>Mirrors {@link StaffAccountDisposalService#dispose}'s own javadoc "The order is not a
 * preference — it is the only representable one" section: Postgres cannot defer
 * {@code chk_masters_detachment_coherent} (only UNIQUE/PK/FK/EXCLUDE are deferrable), so the
 * {@code masterRepository.flush()} BEFORE {@code userRepository.deleteAllByIdInBatch} ordering is a
 * production-correctness requirement, not a style preference — a reorder is a live 500 in
 * production, caught here at the unit level rather than only by the (real-Postgres)
 * {@code StaffDetachCoherenceIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffAccountDisposalService.dispose — unit")
class StaffAccountDisposalServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private Clock clock;

    @Mock
    private TokensValidAfterCache tokensValidAfterCache;

    @Mock
    private UserProfileCacheEvictor userProfileCacheEvictor;

    private StaffAccountDisposalService service;

    private StaffAccountDisposalService newService() {
        return new StaffAccountDisposalService(
                userRepository, inviteTokenRepository, masterRepository, clock,
                tokensValidAfterCache, userProfileCacheEvictor);
    }

    private static Master masterWithUser(UUID masterId, UUID userId, String firstName, String lastName) {
        User user = new User("staff@beautica.test", "hash", Role.SALON_MASTER, firstName, lastName, null);
        ReflectionTestUtils.setField(user, "id", userId);
        return Master.builder()
                .id(masterId)
                .user(user)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("an empty staffUserIds list writes nothing — no repository is touched at all "
            + "(idempotent by construction, D4)")
    void should_writeNothing_when_staffUserIdsIsEmpty() {
        service = newService();

        service.dispose(UUID.randomUUID(), UUID.randomUUID(), List.of());

        verifyNoInteractions(userRepository, inviteTokenRepository, masterRepository,
                tokensValidAfterCache, userProfileCacheEvictor);
    }

    @Test
    @DisplayName("salonId != null runs the invite-token cleanup, scoped to the given salon and "
            + "staff ids")
    void should_deleteInviteTokens_when_salonIdIsNotNull() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID staffUserId = UUID.randomUUID();
        when(masterRepository.findAllByUserIdInWithUser(List.of(staffUserId))).thenReturn(List.of());
        service = newService();

        service.dispose(actorId, salonId, List.of(staffUserId));

        verify(inviteTokenRepository).deleteBySalonIdAndStaffUserIds(salonId, List.of(staffUserId));
    }

    @Test
    @DisplayName("Phase 301 §3a — salonId == null (an INDEPENDENT_MASTER self-delete) skips the "
            + "invite-token cleanup ENTIRELY and touches nothing else differently")
    void should_skipInviteTokenCleanup_when_salonIdIsNull() {
        UUID actorId = UUID.randomUUID();
        UUID staffUserId = UUID.randomUUID();
        when(masterRepository.findAllByUserIdInWithUser(List.of(staffUserId))).thenReturn(List.of());
        service = newService();

        service.dispose(actorId, null, List.of(staffUserId));

        verify(inviteTokenRepository, never()).deleteBySalonIdAndStaffUserIds(any(), any(List.class));
        // Every other statement in the body is keyed on user/master ids, never on salonId — this
        // caller still reaches the flush + hard-delete below, exactly as a salon-scoped caller would.
        verify(masterRepository).flush();
        verify(userRepository).deleteAllByIdInBatch(List.of(staffUserId));
    }

    @Test
    @DisplayName("a masters row with NO historical reference is DELETEd outright, never detached")
    void should_deleteMasterRow_when_noHistoricalReferenceExists() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID staffUserId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = masterWithUser(masterId, staffUserId, "Тест", "Майстер");
        when(masterRepository.findAllByUserIdInWithUser(List.of(staffUserId))).thenReturn(List.of(master));
        when(masterRepository.findIdsWithHistoricalReferences(List.of(masterId))).thenReturn(List.of());
        service = newService();

        service.dispose(actorId, salonId, List.of(staffUserId));

        verify(masterRepository).delete(master);
        assertThat(master.isDetached())
                .as("the delete branch must not also detach the row")
                .isFalse();
    }

    @Test
    @DisplayName("a masters row WITH a historical reference is DETACHed — name snapshot, "
            + "user_id nulled, is_active=false — never physically deleted")
    void should_detachMasterRow_when_historicalReferenceExists() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID staffUserId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = masterWithUser(masterId, staffUserId, "Тест", "Майстер");
        Instant now = Instant.parse("2026-09-08T10:00:00Z");
        when(masterRepository.findAllByUserIdInWithUser(List.of(staffUserId))).thenReturn(List.of(master));
        when(masterRepository.findIdsWithHistoricalReferences(List.of(masterId)))
                .thenReturn(List.of(masterId));
        when(clock.instant()).thenReturn(now);
        service = newService();

        service.dispose(actorId, salonId, List.of(staffUserId));

        verify(masterRepository, never()).delete(any(Master.class));
        assertThat(master.getDetachedFirstName()).isEqualTo("Тест");
        assertThat(master.getDetachedLastName()).isEqualTo("Майстер");
        assertThat(master.getDetachedAt()).isEqualTo(now);
        assertThat(master.getUser()).isNull();
        assertThat(master.isActive()).isFalse();
    }

    @Test
    @DisplayName("mixed batch: masters with history are detached, masters without are deleted — the "
            + "history predicate is resolved ONCE for the whole batch, never per master")
    void should_branchPerMaster_when_batchIsMixed() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID userWithHistory = UUID.randomUUID();
        UUID userWithoutHistory = UUID.randomUUID();
        UUID masterWithHistoryId = UUID.randomUUID();
        UUID masterWithoutHistoryId = UUID.randomUUID();
        Master withHistory = masterWithUser(masterWithHistoryId, userWithHistory, "Оля", "Ковальчук");
        Master withoutHistory = masterWithUser(masterWithoutHistoryId, userWithoutHistory, "Іван", "Бондар");
        when(masterRepository.findAllByUserIdInWithUser(List.of(userWithHistory, userWithoutHistory)))
                .thenReturn(List.of(withHistory, withoutHistory));
        when(masterRepository.findIdsWithHistoricalReferences(
                List.of(masterWithHistoryId, masterWithoutHistoryId)))
                .thenReturn(List.of(masterWithHistoryId));
        when(clock.instant()).thenReturn(Instant.EPOCH);
        service = newService();

        service.dispose(actorId, salonId, List.of(userWithHistory, userWithoutHistory));

        assertThat(withHistory.isDetached()).isTrue();
        verify(masterRepository).delete(withoutHistory);
        verify(masterRepository, never()).delete(withHistory);
    }

    @Test
    @DisplayName("a staffUserIds list resolving to ZERO masters rows (e.g. all SALON_ADMINs) skips "
            + "the historical-reference probe entirely — no query issued for an empty list")
    void should_skipHistoricalReferenceProbe_when_noMastersRowsExist() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID adminUserId = UUID.randomUUID();
        when(masterRepository.findAllByUserIdInWithUser(List.of(adminUserId))).thenReturn(List.of());
        service = newService();

        service.dispose(actorId, salonId, List.of(adminUserId));

        verify(masterRepository, never()).findIdsWithHistoricalReferences(anyCollection());
        verify(masterRepository).flush();
        verify(userRepository).deleteAllByIdInBatch(List.of(adminUserId));
    }

    @Test
    @DisplayName("LOAD-BEARING ORDER: masterRepository.flush() runs BEFORE "
            + "userRepository.deleteAllByIdInBatch — Postgres cannot defer "
            + "chk_masters_detachment_coherent, so a reorder is a production CHECK-violation, not "
            + "a style regression")
    void should_flushMastersBeforeDeletingUsers_inThatOrder() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID staffUserId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = masterWithUser(masterId, staffUserId, "Тест", "Майстер");
        when(masterRepository.findAllByUserIdInWithUser(List.of(staffUserId))).thenReturn(List.of(master));
        when(masterRepository.findIdsWithHistoricalReferences(List.of(masterId)))
                .thenReturn(List.of(masterId));
        when(clock.instant()).thenReturn(Instant.EPOCH);
        service = newService();

        service.dispose(actorId, salonId, List.of(staffUserId));

        InOrder order = inOrder(masterRepository, userRepository);
        order.verify(masterRepository).flush();
        order.verify(userRepository).deleteAllByIdInBatch(List.of(staffUserId));
    }

    @Test
    @DisplayName("cache eviction fires per staff user id — TokensValidAfterCache invalidation AND "
            + "UserProfileCacheEvictor eviction, both after-commit, for every id in the batch")
    void should_evictBothCachesPerStaffUserId() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        when(masterRepository.findAllByUserIdInWithUser(List.of(userA, userB))).thenReturn(List.of());
        service = newService();

        service.dispose(actorId, salonId, List.of(userA, userB));

        verify(tokensValidAfterCache).invalidateAfterCommit(userA);
        verify(tokensValidAfterCache).invalidateAfterCommit(userB);
        verify(userProfileCacheEvictor).evictAfterCommit(userA);
        verify(userProfileCacheEvictor).evictAfterCommit(userB);
    }

    @Test
    @DisplayName("the users hard-delete targets exactly the caller-supplied staffUserIds batch — "
            + "never a master-derived id list")
    void should_deleteExactlyTheSuppliedStaffUserIds() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID staffUserId = UUID.randomUUID();
        lenient().when(masterRepository.findAllByUserIdInWithUser(List.of(staffUserId)))
                .thenReturn(List.of());
        service = newService();

        service.dispose(actorId, salonId, List.of(staffUserId));

        verify(userRepository).deleteAllByIdInBatch(eq(List.of(staffUserId)));
        verifyNoMoreInteractions(userRepository);
    }
}
