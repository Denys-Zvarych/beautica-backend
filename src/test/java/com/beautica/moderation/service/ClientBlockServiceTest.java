package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBlockRateLimitedException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.ActorSalonAssignmentMemo;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.moderation.dto.BlockKind;
import com.beautica.moderation.dto.BlockResponse;
import com.beautica.moderation.dto.ClientBlockState;
import com.beautica.moderation.repository.ClientBlockListRow;
import com.beautica.moderation.repository.ClientBlockRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClientBlockService — phase 397")
class ClientBlockServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final String PEPPER = "unit-test-phone-hash-pepper-min-32-chars!";

    @Mock private ClientBlockRepository clientBlockRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private UserRepository userRepository;

    private final PhoneHasher phoneHasher = new PhoneHasher(new PhoneHashPepperConfig(PEPPER));
    private ClientBlockService service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID salonId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = serviceWithBudget(30);
        when(clientBlockRepository.insertIgnoringDuplicate(any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(1);
        when(clientBlockRepository.findIdByBlockedUser(any(), any(), any())).thenReturn(Optional.empty());
        when(clientBlockRepository.findIdByGuestPhone(any(), any(), any())).thenReturn(Optional.empty());
        when(clientBlockRepository.findListRowById(any()))
                .thenAnswer(inv -> Optional.of(clientRow(inv.getArgument(0))));
    }

    // ── role matrix ──────────────────────────────────────────────────────────

    @Test
    void should_blockInOwnMasterScope_when_independentMasterOwnsBooking() {
        Booking booking = appBooking(independentMaster(actorId), null);
        given(booking);

        ClientBlockService.CreateBlockResult result = service.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        assertThat(result.created()).isTrue();
        verify(clientBlockRepository).insertIgnoringDuplicate(any(), eq(masterId), isNull(), eq(clientId), any(),
                isNull(), eq("Олена К."), eq(actorId), eq(NOW));
    }

    @Test
    void should_blockInSalonScope_when_ownerOwnsBookingSalon() {
        Booking booking = appBooking(salonMaster(), salon(actorId));
        given(booking);

        service.block(actorId, Role.SALON_OWNER, bookingId);

        verify(clientBlockRepository).insertIgnoringDuplicate(any(), isNull(), eq(salonId), eq(clientId), any(),
                isNull(), any(), eq(actorId), any());
    }

    @Test
    void should_blockInSalonScope_when_adminAssignedToBookingSalon() {
        Booking booking = appBooking(salonMaster(), salon(UUID.randomUUID()));
        given(booking);
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        service.block(actorId, Role.SALON_ADMIN, bookingId);

        verify(clientBlockRepository).insertIgnoringDuplicate(any(), isNull(), eq(salonId), eq(clientId), any(),
                isNull(), any(), eq(actorId), any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLIENT", "SALON_MASTER"})
    void should_throwForbidden_when_roleCannotBlock(Role role) {
        assertThatThrownBy(() -> service.block(actorId, role, bookingId)).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(bookingRepository, clientBlockRepository);
    }

    // ── scope → 404 ──────────────────────────────────────────────────────────

    @Test
    void should_throwNotFound_when_bookingMissing() {
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.block(actorId, Role.SALON_OWNER, bookingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void should_throwNotFound_when_independentMasterBlocksForeignBooking() {
        given(appBooking(independentMaster(UUID.randomUUID()), null));

        assertThatThrownBy(() -> service.block(actorId, Role.INDEPENDENT_MASTER, bookingId))
                .isInstanceOf(NotFoundException.class);
        verify(clientBlockRepository, never()).insertIgnoringDuplicate(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void should_throwNotFound_when_ownerBlocksOtherSalonBooking() {
        given(appBooking(salonMaster(), salon(UUID.randomUUID())));

        assertThatThrownBy(() -> service.block(actorId, Role.SALON_OWNER, bookingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void should_throwNotFound_when_adminOfAnotherSalon() {
        given(appBooking(salonMaster(), salon(UUID.randomUUID())));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.block(actorId, Role.SALON_ADMIN, bookingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void should_throwNotFound_when_ownerBlocksOnInactiveSalon() {
        Salon salon = salon(actorId);
        when(salon.isActive()).thenReturn(false);
        given(appBooking(salonMaster(), salon));

        assertThatThrownBy(() -> service.block(actorId, Role.SALON_OWNER, bookingId))
                .isInstanceOf(NotFoundException.class);
        verify(clientBlockRepository, never()).insertIgnoringDuplicate(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    // ── target derivation ────────────────────────────────────────────────────

    @Test
    void should_storeUserOnlyWithoutPhone_when_registeredClient() {
        given(appBooking(independentMaster(actorId), null));

        service.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        verify(clientBlockRepository).insertIgnoringDuplicate(any(), any(), any(), eq(clientId), isNull(),
                isNull(), eq("Олена К."), any(), any());
    }

    @Test
    void should_storePhoneHashAndLast4Only_when_guestBooking() {
        given(guestBooking(independentMaster(actorId), "+380501234567"));
        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);

        service.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        verify(clientBlockRepository).insertIgnoringDuplicate(any(), any(), any(), isNull(), hash.capture(),
                eq("4567"), eq("Марія Ш."), any(), any());
        assertThat(hash.getValue()).isEqualTo(phoneHasher.hash("0501234567").hash());
    }

    @Test
    void should_throw400_when_staffWalkIn() {
        Booking booking = appBooking(independentMaster(actorId), null);
        when(booking.getBookingSource()).thenReturn(BookingSource.STAFF);
        given(booking);

        assertThatThrownBy(() -> service.block(actorId, Role.INDEPENDENT_MASTER, bookingId))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This client cannot be blocked");
    }

    @Test
    void should_throw400_when_guestPhoneErased() {
        given(guestBooking(independentMaster(actorId), null));

        assertThatThrownBy(() -> service.block(actorId, Role.INDEPENDENT_MASTER, bookingId))
                .isInstanceOf(BusinessException.class);
    }

    // ── idempotency (D5) — per-type key, never merged ─────────────────────────

    @Test
    void should_returnExisting_when_clientReblocked() {
        given(appBooking(independentMaster(actorId), null));
        UUID existingId = UUID.randomUUID();
        when(clientBlockRepository.findIdByBlockedUser(masterId, null, clientId)).thenReturn(Optional.of(existingId));

        ClientBlockService.CreateBlockResult result = service.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        assertThat(result.created()).isFalse();
        assertThat(result.block().id()).isEqualTo(existingId);
        verify(clientBlockRepository, never()).insertIgnoringDuplicate(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void should_returnExisting_when_guestReblockedBySamePhone() {
        given(guestBooking(independentMaster(actorId), "+380501234567"));
        UUID existingId = UUID.randomUUID();
        when(clientBlockRepository.findIdByGuestPhone(eq(masterId), isNull(), any()))
                .thenReturn(Optional.of(existingId));

        ClientBlockService.CreateBlockResult result = service.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        assertThat(result.created()).isFalse();
        assertThat(result.block().id()).isEqualTo(existingId);
        verify(clientBlockRepository, never()).findIdByBlockedUser(any(), any(), any());
    }

    @Test
    void should_notMergeIntoGuestRow_when_clientProfilePhoneMatches() {
        Booking booking = appBooking(independentMaster(actorId), null);
        given(booking);
        // A GUEST row exists for exactly the phone this client has on their profile: it must play no part.
        when(clientBlockRepository.findIdByGuestPhone(any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));

        ClientBlockService.CreateBlockResult result = service.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        assertThat(result.created()).isTrue();
        verify(clientBlockRepository, never()).findIdByGuestPhone(any(), any(), any());
        verify(clientBlockRepository).insertIgnoringDuplicate(any(), eq(masterId), isNull(), eq(clientId), isNull(),
                isNull(), any(), any(), any());
    }

    @Test
    void should_notLinkGuestBookingToClientBlock_when_profilePhoneEqualsGuestPhone() {
        Booking booking = guestBooking(independentMaster(actorId), "+380671112233");
        // Some CLIENT row exists in the scope (e.g. the account whose profile phone equals this guest phone).
        when(clientBlockRepository.findIdByBlockedUser(any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));

        ClientBlockState state = service.blockStateFor(actorId, Role.INDEPENDENT_MASTER, booking);

        assertThat(state.blockable()).isTrue();
        assertThat(state.blocked()).isFalse();
        verify(clientBlockRepository, never()).findIdByBlockedUser(any(), any(), any());
    }

    @Test
    void should_returnWinner_when_concurrentInsertLost() {
        given(appBooking(independentMaster(actorId), null));
        UUID winner = UUID.randomUUID();
        when(clientBlockRepository.insertIgnoringDuplicate(any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(0);
        when(clientBlockRepository.findIdByBlockedUser(any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        ClientBlockService.CreateBlockResult result = service.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        assertThat(result.created()).isFalse();
        assertThat(result.block().id()).isEqualTo(winner);
    }

    // ── rate limit ───────────────────────────────────────────────────────────

    @Test
    void should_throwRateLimited_when_blockBudgetExhausted() {
        service = serviceWithBudget(0);

        assertThatThrownBy(() -> service.block(actorId, Role.SALON_OWNER, bookingId))
                .isInstanceOf(ClientBlockRateLimitedException.class)
                .satisfies(e -> assertThat(((ClientBlockRateLimitedException) e).getRetryAfterSeconds())
                        .isEqualTo(3600));
        verifyNoInteractions(bookingRepository, clientBlockRepository);
    }

    @Test
    void should_throwRateLimited_when_unblockBudgetExhausted() {
        service = serviceWithBudget(0);

        assertThatThrownBy(() -> service.unblock(actorId, Role.INDEPENDENT_MASTER, UUID.randomUUID()))
                .isInstanceOf(ClientBlockRateLimitedException.class);
        verifyNoInteractions(masterRepository, clientBlockRepository);
    }

    @Test
    void should_shareOneBudget_when_blockAndUnblockAlternate() {
        service = serviceWithBudget(1);
        when(masterRepository.findIdByUserId(actorId)).thenReturn(Optional.of(masterId));
        service.unblock(actorId, Role.INDEPENDENT_MASTER, UUID.randomUUID());

        assertThatThrownBy(() -> service.block(actorId, Role.INDEPENDENT_MASTER, bookingId))
                .isInstanceOf(ClientBlockRateLimitedException.class);
    }

    @Test
    void should_notSpendToken_when_roleForbidden() {
        service = serviceWithBudget(1);

        assertThatThrownBy(() -> service.block(actorId, Role.CLIENT, bookingId)).isInstanceOf(ForbiddenException.class);
        when(masterRepository.findIdByUserId(actorId)).thenReturn(Optional.of(masterId));
        service.unblock(actorId, Role.INDEPENDENT_MASTER, UUID.randomUUID());

        verify(clientBlockRepository).deleteInMasterScope(any(), eq(masterId));
    }

    // ── list / unblock ───────────────────────────────────────────────────────

    @Test
    void should_listSalonScopes_when_owner() {
        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        ClientBlockListRow guest = new ClientBlockListRow(UUID.randomUUID(), null, "1234", "Марія Ш.", NOW,
                null, null, null);
        ClientBlockListRow client = new ClientBlockListRow(UUID.randomUUID(), clientId, null, "Стара Н.", NOW,
                "Олена", "Коваль", "https://pub.example.r2.dev/a.jpg");
        when(clientBlockRepository.findListRowsBySalonScope(eq(List.of(salonId)), any()))
                .thenReturn(List.of(guest, client));

        List<BlockResponse> result = service.list(actorId, Role.SALON_OWNER);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).kind()).isEqualTo(BlockKind.GUEST);
        assertThat(result.get(0).maskedPhone()).isEqualTo("+380 •• ••• 12 34");
        assertThat(result.get(0).avatarUrl()).isNull();
        assertThat(result.get(1).kind()).isEqualTo(BlockKind.CLIENT);
        assertThat(result.get(1).displayName()).isEqualTo("Олена К.");
        assertThat(result.get(1).avatarUrl()).isEqualTo("https://pub.example.r2.dev/a.jpg");
        assertThat(result.get(1).maskedPhone()).isNull();
    }

    @Test
    void should_returnEmpty_when_independentMasterHasNoMasterRow() {
        when(masterRepository.findIdByUserId(actorId)).thenReturn(Optional.empty());

        assertThat(service.list(actorId, Role.INDEPENDENT_MASTER)).isEmpty();
        verifyNoInteractions(clientBlockRepository);
    }

    @Test
    void should_deleteOnlyInOwnScope_when_unblocking() {
        UUID blockId = UUID.randomUUID();
        when(masterRepository.findIdByUserId(actorId)).thenReturn(Optional.of(masterId));

        service.unblock(actorId, Role.INDEPENDENT_MASTER, blockId);

        verify(clientBlockRepository).deleteInMasterScope(blockId, masterId);
        verify(clientBlockRepository, never()).deleteInSalonScope(any(), any());
    }

    @Test
    void should_noOp_when_adminHasNoSalon() {
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.empty());

        service.unblock(actorId, Role.SALON_ADMIN, UUID.randomUUID());

        verifyNoInteractions(clientBlockRepository);
    }

    @Test
    void should_throwForbidden_when_salonMasterLists() {
        assertThatThrownBy(() -> service.list(actorId, Role.SALON_MASTER)).isInstanceOf(ForbiddenException.class);
    }

    // ── detail block state ───────────────────────────────────────────────────

    @Test
    void should_reportBlockedWithId_when_blockExistsInViewerScope() {
        Booking booking = appBooking(independentMaster(actorId), null);
        UUID existingId = UUID.randomUUID();
        when(clientBlockRepository.findIdByBlockedUser(masterId, null, clientId)).thenReturn(Optional.of(existingId));

        ClientBlockState state = service.blockStateFor(actorId, Role.INDEPENDENT_MASTER, booking);

        assertThat(state.blockable()).isTrue();
        assertThat(state.blocked()).isTrue();
        assertThat(state.blockId()).isEqualTo(existingId);
    }

    @Test
    void should_reportNotBlockable_when_salonInactive() {
        Salon salon = salon(actorId);
        when(salon.isActive()).thenReturn(false);
        Booking booking = appBooking(salonMaster(), salon);

        assertThat(service.blockStateFor(actorId, Role.SALON_OWNER, booking)).isEqualTo(ClientBlockState.NOT_BLOCKABLE);
        verifyNoInteractions(clientBlockRepository);
    }

    @Test
    void should_reportNotBlockable_when_staffWalkIn() {
        Booking booking = appBooking(independentMaster(actorId), null);
        when(booking.getBookingSource()).thenReturn(BookingSource.STAFF);

        assertThat(service.blockStateFor(actorId, Role.INDEPENDENT_MASTER, booking))
                .isEqualTo(ClientBlockState.NOT_BLOCKABLE);
        verifyNoInteractions(clientBlockRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLIENT", "SALON_MASTER"})
    void should_reportNotBlockable_when_viewerIsNotBlocker(Role role) {
        Booking booking = appBooking(independentMaster(actorId), null);

        assertThat(service.blockStateFor(actorId, role, booking)).isEqualTo(ClientBlockState.NOT_BLOCKABLE);
        verifyNoInteractions(clientBlockRepository);
    }

    @Test
    void should_reportNotBlockable_when_roleUnknown() {
        assertThat(service.blockStateFor(actorId, null, mock(Booking.class)))
                .isEqualTo(ClientBlockState.NOT_BLOCKABLE);
    }

    @Test
    void should_reportBlockableNotBlocked_when_noRow() {
        Booking booking = guestBooking(independentMaster(actorId), "+380501234567");

        ClientBlockState state = service.blockStateFor(actorId, Role.INDEPENDENT_MASTER, booking);

        assertThat(state.blockable()).isTrue();
        assertThat(state.blocked()).isFalse();
    }

    // ── phase-398 probes ─────────────────────────────────────────────────────

    @Test
    void should_probeClientRowsOnly_when_isClientBlocked() {
        when(clientBlockRepository.findIdByBlockedUser(masterId, null, clientId))
                .thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(service.isClientBlocked(BlockScope.master(masterId), clientId)).isTrue();
        verify(clientBlockRepository, never()).findIdByGuestPhone(any(), any(), any());
    }

    @Test
    void should_probeGuestRowsOnly_when_isGuestPhoneBlocked() {
        byte[] hash = phoneHasher.hash("+380501234567").hash();
        when(clientBlockRepository.findIdByGuestPhone(null, salonId, hash)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(service.isGuestPhoneBlocked(BlockScope.salon(salonId), hash)).isTrue();
        verify(clientBlockRepository, never()).findIdByBlockedUser(any(), any(), any());
    }

    @Test
    void should_returnFalse_when_noGuestRowForPhone() {
        assertThat(service.isGuestPhoneBlocked(BlockScope.master(masterId), new byte[32])).isFalse();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void given(Booking booking) {
        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
    }

    private Master independentMaster(UUID userId) {
        Master master = mock(Master.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        return master;
    }

    private Master salonMaster() {
        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        return master;
    }

    private Salon salon(UUID ownerId) {
        Salon salon = mock(Salon.class);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        when(salon.getId()).thenReturn(salonId);
        when(salon.getOwner()).thenReturn(owner);
        when(salon.isActive()).thenReturn(true);
        return salon;
    }

    private Booking appBooking(Master master, Salon salon) {
        Booking booking = mock(Booking.class);
        User client = mock(User.class);
        when(client.getId()).thenReturn(clientId);
        when(client.getFirstName()).thenReturn("Олена");
        when(client.getLastName()).thenReturn("Коваль");
        when(client.getPhoneNumber()).thenReturn("+380 67 111 22 33");
        when(booking.getBookingSource()).thenReturn(BookingSource.APP);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getSalon()).thenReturn(salon);
        return booking;
    }

    private Booking guestBooking(Master master, String guestPhone) {
        Booking booking = mock(Booking.class);
        when(booking.getBookingSource()).thenReturn(BookingSource.LINK);
        when(booking.getGuestName()).thenReturn("Марія");
        when(booking.getGuestSurname()).thenReturn("Шевченко");
        when(booking.getGuestPhone()).thenReturn(guestPhone);
        when(booking.getMaster()).thenReturn(master);
        return booking;
    }

    private ClientBlockListRow clientRow(UUID id) {
        return new ClientBlockListRow(id, clientId, null, "Олена К.", NOW, "Олена", "Коваль", null);
    }

    private ClientBlockService serviceWithBudget(long capacity) {
        ClientBlockScopeResolver resolver = new ClientBlockScopeResolver(
                masterRepository, salonRepository, userRepository, new ActorSalonAssignmentMemo());
        return new ClientBlockService(clientBlockRepository, bookingRepository, resolver, phoneHasher,
                bucketsWithCapacity(capacity), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LoadingCache<String, Bucket> bucketsWithCapacity(long capacity) {
        return Caffeine.newBuilder().build(key -> {
            Bucket bucket = Bucket.builder()
                    .addLimit(BandwidthBuilder.builder().capacity(Math.max(capacity, 1))
                            .refillIntervally(Math.max(capacity, 1), Duration.ofHours(1)).build())
                    .build();
            if (capacity == 0) {
                bucket.tryConsume(1);
            }
            return bucket;
        });
    }
}
