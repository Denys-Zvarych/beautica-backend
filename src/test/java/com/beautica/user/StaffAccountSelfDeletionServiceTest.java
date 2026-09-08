package com.beautica.user;

import com.beautica.auth.AuthService;
import com.beautica.auth.Role;
import com.beautica.booking.service.BookingService;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.repository.MediaRepository;
import com.beautica.salon.audit.AuditOutcome;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.audit.StaffClientReferenceType;
import com.beautica.salon.audit.StaffClientReferenceViolation;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.StaffAccountDisposalService;
import com.beautica.salon.service.StaffDisposalReason;
import com.beautica.salon.service.StaffClientReferenceAuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StaffAccountSelfDeletionService#deleteOwnAccount} (Phase 301) — the
 * {@code SALON_ADMIN}/{@code SALON_MASTER}/{@code INDEPENDENT_MASTER} sibling of {@code
 * ClientAccountDeletionServiceTest}. Mirrors that class's shape: role/salonId scoping is already
 * enforced by {@code @PreAuthorize} on {@code UserController#deleteMyAccount}; these tests cover
 * the service-layer behaviour SpEL cannot express — defence-in-depth role re-check, the R9
 * owner-of-salon precondition, the fail-closed client-residue audit, the master-role booking
 * cascade ORDER (lock BEFORE read, per the 2026-09 residual-race fix), both cap boundaries, both
 * verbatim 422 messages, and delegation to the promoted {@link StaffAccountDisposalService} seam
 * rather than a parallel implementation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffAccountSelfDeletionService.deleteOwnAccount — unit")
class StaffAccountSelfDeletionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private BookingService bookingService;

    @Mock
    private StaffAccountDisposalService staffAccountDisposalService;

    @Mock
    private StaffClientReferenceAuditService staffClientReferenceAuditService;

    @Mock
    private AuthService authService;

    @Mock
    private AccountBlobPurgeRegistrar accountBlobPurgeRegistrar;

    private StaffAccountSelfDeletionService service;

    private StaffAccountSelfDeletionService newService() {
        return new StaffAccountSelfDeletionService(
                userRepository, masterRepository, salonRepository, mediaRepository, bookingService,
                staffAccountDisposalService, staffClientReferenceAuditService, authService,
                accountBlobPurgeRegistrar);
    }

    private static User buildUser(UUID id, Role role, UUID salonId) {
        User user = new User("staff@beautica.test", "hash", role, "Тест", "Персонал", null, salonId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Master buildMaster(UUID masterId, UUID userId) {
        return Master.builder().id(masterId).masterType(MasterType.SALON_MASTER).isActive(true).build();
    }

    private void stubCleanPreconditions(UUID userId) {
        lenient().when(salonRepository.existsByOwnerId(userId)).thenReturn(false);
        lenient().when(staffClientReferenceAuditService.runAuditForStaffUserIds(List.of(userId)))
                .thenReturn(StaffClientReferenceAuditResult.of(List.of(), Instant.EPOCH));
        lenient().when(mediaRepository.findByUploaderId(userId)).thenReturn(List.of());
    }

    // ── role guard ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("throws ForbiddenException when the caller's own user row does not exist")
    void should_throwForbidden_when_userDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());
        service = newService();

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(salonRepository, staffClientReferenceAuditService, mediaRepository,
                bookingService, staffAccountDisposalService, authService, accountBlobPurgeRegistrar);
    }

    @ParameterizedTest(name = "role {0} is refused as defence-in-depth even if it somehow reaches "
            + "this service")
    @EnumSource(value = Role.class, names = {"CLIENT", "SALON_OWNER"})
    @DisplayName("throws ForbiddenException for any role outside the three self-deletable roles")
    void should_throwForbidden_when_roleIsNotSelfDeletable(Role role) {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, role, null);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        service = newService();

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(salonRepository, staffClientReferenceAuditService, bookingService,
                staffAccountDisposalService, authService, accountBlobPurgeRegistrar);
    }

    // ── R9: owner-of-salon precondition ────────────────────────────────────────────────────

    @Test
    @DisplayName("R9 — throws 409 BEFORE any write when the caller (structurally unreachable, but "
            + "enforced) owns a salon")
    void should_throwConflict_when_callerOwnsASalon() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_ADMIN, UUID.randomUUID());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(salonRepository.existsByOwnerId(userId)).thenReturn(true);
        service = newService();

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verifyNoInteractions(staffClientReferenceAuditService, mediaRepository, bookingService,
                staffAccountDisposalService, authService, accountBlobPurgeRegistrar);
    }

    // ── fail-closed client-residue audit ───────────────────────────────────────────────────

    @Test
    @DisplayName("throws 409 and writes nothing when the client-residue audit finds a violation")
    void should_throwConflict_when_clientResidueAuditFindsViolations() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_ADMIN, UUID.randomUUID());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(salonRepository.existsByOwnerId(userId)).thenReturn(false);
        when(staffClientReferenceAuditService.runAuditForStaffUserIds(List.of(userId)))
                .thenReturn(StaffClientReferenceAuditResult.of(
                        List.of(new StaffClientReferenceViolation(
                                userId, Role.SALON_ADMIN, StaffClientReferenceType.BOOKING_CLIENT, 1L)),
                        Instant.EPOCH));
        service = newService();

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verifyNoInteractions(mediaRepository, bookingService, staffAccountDisposalService, authService,
                accountBlobPurgeRegistrar);
    }

    // ── SALON_ADMIN happy path — no masters row, no booking cascade ───────────────────────────

    @Test
    @DisplayName("SALON_ADMIN: no masters row is looked up, no booking cascade runs, and the "
            + "promoted disposal seam is delegated to with salonId and List.of(userId)")
    void should_deleteAdminAccount_withNoBookingCascade() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_ADMIN, salonId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        service = newService();

        service.deleteOwnAccount(userId, "token-123");

        verifyNoInteractions(bookingService);
        verify(masterRepository, never()).findByUserId(any());
        verify(staffAccountDisposalService)
                .dispose(userId, salonId, List.of(userId), StaffDisposalReason.SELF_DELETE);
        verify(authService).denylistAccessToken("token-123");
        verify(accountBlobPurgeRegistrar).registerAfterCommit(eq(userId), any(), eq(List.of()));
    }

    // ── SALON_MASTER / INDEPENDENT_MASTER happy paths — booking cascade ────────────────────────

    @Test
    @DisplayName("SALON_MASTER: acquires the master advisory lock, disposes future bookings through "
            + "the master-scoped cascade with the salon id, then delegates the account hard-delete")
    void should_deleteMasterAccount_disposingFutureBookings() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_MASTER, salonId);
        Master master = buildMaster(masterId, userId);
        List<UUID> futureBookingIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(bookingService.findFutureConfirmedBookingIdsForMaster(masterId)).thenReturn(futureBookingIds);
        service = newService();

        service.deleteOwnAccount(userId, "token");

        verify(bookingService).acquireMasterLockForSelfDelete(masterId);
        verify(bookingService).disposeFutureConfirmedForMasterSelfDelete(
                userId, masterId, salonId, futureBookingIds);
        verify(staffAccountDisposalService)
                .dispose(userId, salonId, List.of(userId), StaffDisposalReason.SELF_DELETE);
    }

    @Test
    @DisplayName("residual-race fix (2026-09): acquireMasterLockForSelfDelete is called BEFORE "
            + "findFutureConfirmedBookingIdsForMaster — not merely before the write — closing the "
            + "gap where a booking committed between an unlocked read and a later lock would "
            + "silently survive the cascade")
    void should_acquireMasterLock_beforeReadingFutureBookingIds() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_MASTER, salonId);
        Master master = buildMaster(masterId, userId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(bookingService.findFutureConfirmedBookingIdsForMaster(masterId)).thenReturn(List.of());
        service = newService();

        service.deleteOwnAccount(userId, "token");

        InOrder order = inOrder(bookingService);
        order.verify(bookingService).acquireMasterLockForSelfDelete(masterId);
        order.verify(bookingService).findFutureConfirmedBookingIdsForMaster(masterId);
        order.verify(bookingService).disposeFutureConfirmedForMasterSelfDelete(
                eq(userId), eq(masterId), eq(salonId), any());
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER: the booking cascade and the account disposal both receive a "
            + "null salonId — never invited into any salon")
    void should_deleteIndependentMasterAccount_withNullSalonId() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = buildUser(userId, Role.INDEPENDENT_MASTER, null);
        Master master = buildMaster(masterId, userId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(bookingService.findFutureConfirmedBookingIdsForMaster(masterId)).thenReturn(List.of());
        service = newService();

        service.deleteOwnAccount(userId, "token");

        verify(bookingService).disposeFutureConfirmedForMasterSelfDelete(
                eq(userId), eq(masterId), eq(null), any());
        verify(staffAccountDisposalService)
                .dispose(userId, null, List.of(userId), StaffDisposalReason.SELF_DELETE);
    }

    @Test
    @DisplayName("SALON_MASTER/INDEPENDENT_MASTER with zero future bookings still deletes cleanly — "
            + "the write-seam call happens with an empty list, not skipped")
    void should_stillCallDisposal_when_noFutureBookingsExist() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = buildUser(userId, Role.INDEPENDENT_MASTER, null);
        Master master = buildMaster(masterId, userId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(bookingService.findFutureConfirmedBookingIdsForMaster(masterId)).thenReturn(List.of());
        service = newService();

        service.deleteOwnAccount(userId, "token");

        verify(bookingService).disposeFutureConfirmedForMasterSelfDelete(userId, masterId, null, List.of());
    }

    @Test
    @DisplayName("defence-in-depth: a SALON_MASTER/INDEPENDENT_MASTER whose masters row cannot be "
            + "found is refused (unreachable in production — the only path that detaches a masters "
            + "row also hard-deletes the account in the same statement)")
    void should_throwForbidden_when_masterRowMissingForMasterRole() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_MASTER, UUID.randomUUID());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.empty());
        service = newService();

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(bookingService, staffAccountDisposalService, authService,
                accountBlobPurgeRegistrar);
    }

    // ── cap boundary + the two verbatim 422 messages ───────────────────────────────────────────

    @Test
    @DisplayName("exactly 500 future bookings (the cap) is accepted, not rejected — the boundary is "
            + "inclusive")
    void should_succeed_when_futureBookingCountIsExactlyAtCap() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_MASTER, salonId);
        Master master = buildMaster(masterId, userId);
        List<UUID> exactlyAtCap = fixedSizeIdList(500);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(bookingService.findFutureConfirmedBookingIdsForMaster(masterId)).thenReturn(exactlyAtCap);
        service = newService();

        service.deleteOwnAccount(userId, "token");

        verify(bookingService).disposeFutureConfirmedForMasterSelfDelete(
                userId, masterId, salonId, exactlyAtCap);
        verify(staffAccountDisposalService)
                .dispose(userId, salonId, List.of(userId), StaffDisposalReason.SELF_DELETE);
    }

    @Test
    @DisplayName("501 future bookings for a SALON_MASTER throws 422 BEFORE any write, with the "
            + "owner-directed message VERBATIM — a SALON_MASTER cannot cancel their own bookings")
    void should_throwCapExceeded_withOwnerDirectedMessage_when_salonMasterOver501() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_MASTER, UUID.randomUUID());
        Master master = buildMaster(masterId, userId);
        List<UUID> overCap = fixedSizeIdList(501);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(bookingService.findFutureConfirmedBookingIdsForMaster(masterId)).thenReturn(overCap);
        service = newService();

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getMessage())
                            .isEqualTo("Забагато майбутніх записів (501). Зверніться до власника "
                                    + "салону, щоб він видалив вас у розділі «Команда».");
                });

        verify(bookingService, never()).disposeFutureConfirmedForMasterSelfDelete(any(), any(), any(), any());
        verify(staffAccountDisposalService, never()).dispose(any(), any(), any(), any());
    }

    @Test
    @DisplayName("501 future bookings for an INDEPENDENT_MASTER throws 422 with the self-remedy "
            + "message VERBATIM — distinct from the SALON_MASTER copy, since this role CAN cancel "
            + "its own bookings")
    void should_throwCapExceeded_withSelfRemedyMessage_when_independentMasterOver501() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = buildUser(userId, Role.INDEPENDENT_MASTER, null);
        Master master = buildMaster(masterId, userId);
        List<UUID> overCap = fixedSizeIdList(501);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(bookingService.findFutureConfirmedBookingIdsForMaster(masterId)).thenReturn(overCap);
        service = newService();

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Забагато майбутніх записів (501). Спочатку скасуйте або завершіть "
                        + "майбутні записи (максимум 500) і спробуйте ще раз.");

        verify(bookingService, never()).disposeFutureConfirmedForMasterSelfDelete(any(), any(), any(), any());
        verify(staffAccountDisposalService, never()).dispose(any(), any(), any(), any());
    }

    // ── after-commit registration + denylist ───────────────────────────────────────────────────

    @Test
    @DisplayName("the R2 blob purge is registered with the pre-read avatar key and media rows, "
            + "BEFORE any cascade could have already destroyed them")
    void should_registerBlobPurge_withPreReadPointers() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_ADMIN, UUID.randomUUID());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        ReflectionTestUtils.setField(user, "avatarR2Key", "avatars/staff-key");
        service = newService();

        service.deleteOwnAccount(userId, "token");

        verify(accountBlobPurgeRegistrar).registerAfterCommit(userId, "avatars/staff-key", List.of());
    }

    @Test
    @DisplayName("a null access token (extraction failed defensively) is passed through to the "
            + "denylist call as-is, mirroring AuthService#logout's contract")
    void should_passThroughNullAccessToken() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, Role.SALON_ADMIN, UUID.randomUUID());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubCleanPreconditions(userId);
        service = newService();

        service.deleteOwnAccount(userId, null);

        verify(authService).denylistAccessToken(null);
    }

    private static List<UUID> fixedSizeIdList(int size) {
        List<UUID> ids = new ArrayList<>(size);
        Stream.generate(UUID::randomUUID).limit(size).forEach(ids::add);
        return ids;
    }
}
