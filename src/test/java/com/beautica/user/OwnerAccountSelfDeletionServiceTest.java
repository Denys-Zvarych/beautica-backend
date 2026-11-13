package com.beautica.user;

import com.beautica.auth.AuthService;
import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.OwnerAccountDeletionBlockedException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.repository.MediaFileKey;
import com.beautica.media.repository.MediaRepository;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.salon.service.StaffAccountDisposalService;
import com.beautica.salon.service.StaffDisposalReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OwnerAccountSelfDeletionService (Phase 488)")
class OwnerAccountSelfDeletionServiceTest {

    @Mock private SalonService salonService;
    @Mock private SalonRepository salonRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private MediaRepository mediaRepository;
    @Mock private NotificationOutboxService notificationOutboxService;
    @Mock private StaffAccountDisposalService staffAccountDisposalService;
    @Mock private AuthService authService;
    @Mock private AccountBlobPurgeRegistrar accountBlobPurgeRegistrar;

    private OwnerAccountSelfDeletionService service;
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OwnerAccountSelfDeletionService(salonService, salonRepository, masterRepository,
                mediaRepository, notificationOutboxService, staffAccountDisposalService, authService,
                accountBlobPurgeRegistrar);
    }

    @Test
    @DisplayName("refuses 403 when the locked user row is gone")
    void should_throwForbidden_when_userMissing() {
        when(salonService.lockOwnerForCreate(ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOwnAccount(ownerId, "token")).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(staffAccountDisposalService, authService, accountBlobPurgeRegistrar);
    }

    @Test
    @DisplayName("refuses 403 when the caller is not a SALON_OWNER (defence in depth)")
    void should_throwForbidden_when_roleIsNotOwner() {
        when(salonService.lockOwnerForCreate(ownerId)).thenReturn(Optional.of(user(Role.SALON_ADMIN)));

        assertThatThrownBy(() -> service.deleteOwnAccount(ownerId, "token")).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(salonRepository, staffAccountDisposalService, authService);
        verify(salonService, never()).detachDeactivatedSalonsForOwnerDeletion(any());
    }

    @Test
    @DisplayName("refuses 409 OWNER_HAS_ACTIVE_SALONS before any write when a salon is active")
    void should_throwOwnerHasActiveSalons_when_activeSalonExists() {
        when(salonService.lockOwnerForCreate(ownerId)).thenReturn(Optional.of(user(Role.SALON_OWNER)));
        when(salonRepository.existsByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteOwnAccount(ownerId, "token"))
                .isInstanceOf(OwnerAccountDeletionBlockedException.class)
                .extracting(ex -> ((OwnerAccountDeletionBlockedException) ex).getCode())
                .isEqualTo(OwnerAccountDeletionBlockedException.Code.OWNER_HAS_ACTIVE_SALONS);

        verifyNoInteractions(notificationOutboxService, masterRepository, staffAccountDisposalService,
                authService, accountBlobPurgeRegistrar);
        verify(salonService, never()).detachDeactivatedSalonsForOwnerDeletion(any());
    }

    @Test
    @DisplayName("refuses 409 OWNER_SALON_HAS_STAFF when another account is still linked to an owned salon")
    void should_throwOwnerSalonHasStaff_when_otherAccountLinked() {
        when(salonService.lockOwnerForCreate(ownerId)).thenReturn(Optional.of(user(Role.SALON_OWNER)));
        when(salonRepository.existsOtherAccountLinkedToOwnerSalons(ownerId)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteOwnAccount(ownerId, "token"))
                .isInstanceOf(OwnerAccountDeletionBlockedException.class)
                .extracting(ex -> ((OwnerAccountDeletionBlockedException) ex).getCode())
                .isEqualTo(OwnerAccountDeletionBlockedException.Code.OWNER_SALON_HAS_STAFF);

        verifyNoInteractions(notificationOutboxService, staffAccountDisposalService, authService,
                accountBlobPurgeRegistrar);
        verify(salonService, never()).detachDeactivatedSalonsForOwnerDeletion(any());
    }

    @Test
    @DisplayName("happy path: lock → discard queued invites → detach salons → dispose (OWNER_SELF_DELETE, pre-read masters) → denylist → R2 sweep")
    void should_detachThenDisposeThenDenylist_when_noActiveSalon() {
        User owner = user(Role.SALON_OWNER);
        Master ownerMaster = Master.builder().id(UUID.randomUUID()).masterType(MasterType.SALON_OWNER)
                .user(owner).isActive(false).build();
        List<MediaFileKey> media = List.of();
        when(salonService.lockOwnerForCreate(ownerId)).thenReturn(Optional.of(owner));
        when(masterRepository.findAllByUserIdInWithUser(List.of(ownerId))).thenReturn(List.of(ownerMaster));
        when(mediaRepository.findAccountMediaKeys(List.of(ownerId), List.of(ownerMaster.getId()))).thenReturn(media);
        when(salonService.detachDeactivatedSalonsForOwnerDeletion(ownerId)).thenReturn(List.of(UUID.randomUUID()));

        service.deleteOwnAccount(ownerId, "token");

        InOrder order = inOrder(salonService, notificationOutboxService, staffAccountDisposalService, authService,
                accountBlobPurgeRegistrar);
        order.verify(salonService).lockOwnerForCreate(ownerId);
        order.verify(notificationOutboxService).discardPendingInvitesForOwnerSalons(ownerId);
        order.verify(salonService).detachDeactivatedSalonsForOwnerDeletion(ownerId);
        order.verify(staffAccountDisposalService).dispose(
                ownerId, null, List.of(ownerId), StaffDisposalReason.OWNER_SELF_DELETE, List.of(ownerMaster));
        order.verify(authService).denylistAccessToken("token");
        order.verify(accountBlobPurgeRegistrar).registerAfterCommit(owner, media);
    }

    @Test
    @DisplayName("OWNER_SELF_DELETE is self-initiated: the disposal skips its own R2 sweep and actor clause")
    void should_beSelfInitiated_when_ownerSelfDeleteReason() {
        assertThat(StaffDisposalReason.OWNER_SELF_DELETE.selfInitiated()).isTrue();
        assertThat(StaffDisposalReason.SELF_DELETE.selfInitiated()).isTrue();
        assertThat(StaffDisposalReason.SALON_DELETION.selfInitiated()).isFalse();
    }

    private User user(Role role) {
        User user = new User("owner@beautica.test", "hash", role, "Тест", "Власник", null);
        ReflectionTestUtils.setField(user, "id", ownerId);
        return user;
    }
}
