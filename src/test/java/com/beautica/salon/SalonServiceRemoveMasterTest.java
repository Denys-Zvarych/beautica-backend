package com.beautica.salon;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.service.SalonService;
import com.beautica.salon.service.StaffClientReferenceAuditService;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalonService#removeMaster} guards that a real-DB {@code MasterRemovalIT}
 * case cannot isolate — both guards below are only reachable by calling the service directly with
 * a hand-built {@link Master}, never through real HTTP, because {@code @PreAuthorize}'s
 * {@code masterBelongsToSalon} and the SALON_OWNER role check would intercept the equivalent
 * real-world scenario first over HTTP. Mirrors {@link SalonServiceRemoveAdminTest}'s exact
 * rationale for {@code removeAdmin}'s own defense-in-depth / self-removal guards.
 *
 * <p><b>Guard-order falsification (QA audit, 2026-09-05).</b> Deleting either guard below from
 * {@code SalonService#removeMaster} was verified to leave the ENTIRE Phase 297 test scope
 * (`MasterRemovalIT`, `SalonMasterControllerSecurityTest`, `SalonMasterControllerTest`,
 * `SalonStaffHardDeleteIT`, `SalonServiceCacheTest`, `SalonServiceInviteHistoryTest`,
 * `StaffClientReferenceAuditServiceIT` — 104 tests) green. Both are structurally unreachable
 * TODAY given {@code masters.user_id} being globally UNIQUE and role being immutable (a
 * {@code SALON_OWNER}-role actor's one-and-only {@code masters} row is always
 * {@code MasterType.SALON_OWNER}, which the D6 guard two lines above always catches first) and
 * given {@code masterBelongsToSalon} being true only for a master actually inside {@code
 * #salonId} (which the {@code @PreAuthorize} arm already denies over real HTTP) — but a future
 * reorder of the five caller-supplied-id guards in this method, or a relaxation of the
 * masters.user_id uniqueness constraint, would make either reachable-and-wrong with nothing in the
 * suite to catch it. These two tests exist so that regression has a red test waiting for it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService.removeMaster — unit (defense-in-depth guards)")
class SalonServiceRemoveMasterTest {

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private MasterService masterService;

    @Mock
    private StaffClientReferenceAuditService staffClientReferenceAuditService;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private SalonService salonService;

    @Test
    @DisplayName("throws ForbiddenException when the loaded master's salon does not match the "
            + "path salonId (defense-in-depth re-check of masterBelongsToSalon)")
    void should_throwForbidden_when_masterBelongsToDifferentSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = buildAttachedMaster(masterId, MasterType.SALON_MASTER, otherSalonId,
                UUID.randomUUID());

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> salonService.removeMaster(actorId, salonId, masterId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Master does not belong to this salon");

        verifyNoInteractions(staffClientReferenceAuditService, bookingRepository);
        verify(masterService, never()).deactivateMaster(any(), any(Master.class));
    }

    @Test
    @DisplayName("throws ForbiddenException when the actor's own user id equals the target "
            + "master's user id (self-removal — reachable only if a non-owner masters row is "
            + "ever bound to the acting SALON_OWNER's own user, which masters.user_id's UNIQUE "
            + "constraint and role immutability forbid today)")
    void should_throwForbidden_when_actorTargetsOwnMasterUserId() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        // Deliberately NOT MasterType.SALON_OWNER — that would be caught by the earlier D6 guard
        // first, which is exactly the "guards 2 and 5 would have to be reordered" scenario this
        // test exists to catch.
        Master master = buildAttachedMaster(masterId, MasterType.SALON_MASTER, salonId, actorId);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> salonService.removeMaster(actorId, salonId, masterId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot remove yourself");

        verifyNoInteractions(staffClientReferenceAuditService, bookingRepository);
        verify(masterService, never()).deactivateMaster(any(), any(Master.class));
    }

    @Test
    @DisplayName("throws 409 CONFLICT when the loaded master is INDEPENDENT_MASTER even though its "
            + "salon field is (hypothetically) populated — D6 is a POSITIVE assertion "
            + "(masterType == SALON_MASTER), not merely an exclusion of SALON_OWNER, so it does not "
            + "rely on the DB-unenforced invariant that an INDEPENDENT_MASTER row never carries a "
            + "non-null salon")
    void should_reject_when_masterTypeIsIndependent_evenIfSalonSomehowPopulated() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        // Deliberately give this INDEPENDENT_MASTER a non-null salon matching salonId, so that if
        // the D6 guard were reverted to `== MasterType.SALON_OWNER` only, this row would sail
        // through D6 and then pass the D5 belongs-to-salon check too (master.getSalon().getId()
        // equals salonId) — proving the guard must be a positive SALON_MASTER assertion standing
        // on its own, not an accidental byproduct of D5.
        Master master = buildAttachedMaster(masterId, MasterType.INDEPENDENT_MASTER, salonId,
                UUID.randomUUID());

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> salonService.removeMaster(actorId, salonId, masterId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(staffClientReferenceAuditService, bookingRepository);
        verify(masterService, never()).deactivateMaster(any(), any(Master.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Master buildAttachedMaster(UUID masterId, MasterType masterType, UUID salonId, UUID userId) {
        User user = new User("master@beautica.test", "hash", com.beautica.auth.Role.SALON_MASTER,
                "Test", "Master", null);
        ReflectionTestUtils.setField(user, "id", userId);

        Salon salon = Salon.builder().id(salonId).build();

        Master master = Master.builder()
                .id(masterId)
                .user(user)
                .salon(salon)
                .masterType(masterType)
                .isActive(true)
                .build();
        return master;
    }
}
