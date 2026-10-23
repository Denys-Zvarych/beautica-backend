package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinkStatus;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingLinkStateRow;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 378 — {@link BookingLinkRetirementService}: kill-switch, D6 authorization on the stored rows, the
 * early D7 stale check, the D11d single retry and the response shape. The transactional write itself is
 * {@code BookingLinkRetirementWriterTest}; persistence and races are {@code BookingLinkRetirementIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingLinkRetirementService — unit (phase 378)")
class BookingLinkRetirementServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MASTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALON_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String OLD = "olena-koval-x7k2";
    private static final String NEW = "olena-koval-p3qd";

    @Mock private MasterRepository masterRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private AuthorizationService authorizationService;
    @Mock private BookingLinkRetirementWriter writer;
    @Mock private BookingLinkService bookingLinkService;

    private BookingLinksProperties properties;
    private BookingLinkRetirementService service;

    @BeforeEach
    void setUp() {
        properties = new BookingLinksProperties();
        properties.setShareEnabled(true);
        service = new BookingLinkRetirementService(
                properties, masterRepository, salonRepository, authorizationService, writer, bookingLinkService);
    }

    private static Master master(MasterType type, String slug) {
        User user = new User("m@beautica.test", "hash", Role.INDEPENDENT_MASTER, "Олена", "Коваль", null);
        return Master.builder().id(MASTER_ID).user(user).masterType(type).isActive(true).bookingSlug(slug).build();
    }

    private static SalonBookingLinkStateRow salon(UUID ownerId, boolean active, String slug, boolean revoked) {
        return new SalonBookingLinkStateRow() {
            @Override public UUID getId() { return SALON_ID; }
            @Override public String getName() { return "Alfa"; }
            @Override public String getBookingSlug() { return slug; }
            @Override public Boolean getRevoked() { return revoked; }
            @Override public UUID getOwnerId() { return ownerId; }
            @Override public Boolean getActive() { return active; }
        };
    }

    // ── kill-switch (D9) ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D9: switch off → 404 on every mutation before any repository or writer call")
    void should_throwNotFoundWithoutTouchingData_when_shareSwitchedOff() {
        properties.setShareEnabled(false);

        assertThatThrownBy(() -> service.regenerateMaster(USER_ID, OLD)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.revokeMaster(USER_ID, OLD)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.regenerateSalon(USER_ID, Role.SALON_OWNER, SALON_ID, OLD))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.revokeSalon(USER_ID, Role.SALON_ADMIN, SALON_ID, OLD))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(masterRepository, salonRepository, authorizationService, writer, bookingLinkService);
    }

    // ── MASTER ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("master regenerate → writer called with the loaded row + current slug; response built from the new slug")
    void should_regenerateAndAnswerWithNewSlug_when_independentMasterCurrentSlugMatches() {
        Master master = master(MasterType.INDEPENDENT_MASTER, OLD);
        BookingLinkResponse expected = new BookingLinkResponse(
                BookingLinkKind.MASTER, NEW, "https://beautica.app/book/" + NEW, "Олена Коваль", null, true);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(writer.regenerateMaster(master, OLD, USER_ID)).thenReturn(NEW);
        when(bookingLinkService.masterLinkWith(master, NEW)).thenReturn(expected);

        BookingLinkResponse response = service.regenerateMaster(USER_ID, OLD);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    @DisplayName("master revoke → writer revokes; response is the REVOKED link")
    void should_revokeAndAnswerRevoked_when_independentMasterCurrentSlugMatches() {
        Master master = master(MasterType.INDEPENDENT_MASTER, OLD);
        BookingLinkResponse revoked = BookingLinkResponse.revoked(BookingLinkKind.MASTER, "Олена Коваль", null);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(bookingLinkService.masterLinkWith(master, null)).thenReturn(revoked);

        BookingLinkResponse response = service.revokeMaster(USER_ID, OLD);

        verify(writer).revokeMaster(MASTER_ID, OLD, USER_ID);
        assertThat(response.status()).isEqualTo(BookingLinkStatus.REVOKED);
    }

    @Test
    @DisplayName("D7 early check: stale currentSlug → 409, writer never called")
    void should_throwConflictWithoutWriting_when_currentSlugStale() {
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID))
                .thenReturn(Optional.of(master(MasterType.INDEPENDENT_MASTER, NEW)));

        assertThatThrownBy(() -> service.regenerateMaster(USER_ID, OLD)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.revokeMaster(USER_ID, null)).isInstanceOf(ConflictException.class);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("stored master row is not INDEPENDENT_MASTER (stale token role) → 403, writer never called")
    void should_throwForbidden_when_storedMasterTypeNotIndependent() {
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID))
                .thenReturn(Optional.of(master(MasterType.SALON_MASTER, OLD)));

        assertThatThrownBy(() -> service.regenerateMaster(USER_ID, OLD)).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("no active master row → 404")
    void should_throwNotFound_when_noActiveMasterRow() {
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeMaster(USER_ID, OLD)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("D11d: new slug hits the unique index → ONE retry in a fresh writer call")
    void should_retryOnce_when_firstRegenerateCollides() {
        Master master = master(MasterType.INDEPENDENT_MASTER, OLD);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(writer.regenerateMaster(master, OLD, USER_ID))
                .thenThrow(new DataIntegrityViolationException("idx_masters_booking_slug"))
                .thenReturn(NEW);

        service.regenerateMaster(USER_ID, OLD);

        verify(writer, times(2)).regenerateMaster(master, OLD, USER_ID);
        verify(bookingLinkService).masterLinkWith(master, NEW);
    }

    @Test
    @DisplayName("D11d: a second collision propagates")
    void should_propagate_when_retryCollidesAgain() {
        Master master = master(MasterType.INDEPENDENT_MASTER, OLD);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(writer.regenerateMaster(master, OLD, USER_ID))
                .thenThrow(new DataIntegrityViolationException("idx_masters_booking_slug"));

        assertThatThrownBy(() -> service.regenerateMaster(USER_ID, OLD))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(writer, times(2)).regenerateMaster(master, OLD, USER_ID);
    }

    @Test
    @DisplayName("378 audit INFO-4: a lock-order deadlock in the master regenerate → 409 stale, not retried, not a 500")
    void should_mapToConflict_when_masterRegenerateDeadlocks() {
        Master master = master(MasterType.INDEPENDENT_MASTER, OLD);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(writer.regenerateMaster(master, OLD, USER_ID))
                .thenThrow(new CannotAcquireLockException("deadlock detected"));

        assertThatThrownBy(() -> service.regenerateMaster(USER_ID, OLD))
                .isInstanceOf(ConflictException.class)
                .hasMessage(BookingLinkRetirementWriter.STALE_MESSAGE);

        verify(writer, times(1)).regenerateMaster(master, OLD, USER_ID);
        verifyNoInteractions(bookingLinkService);
    }

    @Test
    @DisplayName("378 audit INFO-4: a lock failure on the collision RETRY is a 409 too")
    void should_mapToConflict_when_regenerateRetryHitsLockFailure() {
        Master master = master(MasterType.INDEPENDENT_MASTER, OLD);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(writer.regenerateMaster(master, OLD, USER_ID))
                .thenThrow(new DataIntegrityViolationException("idx_masters_booking_slug"))
                .thenThrow(new PessimisticLockingFailureException("lock_timeout"));

        assertThatThrownBy(() -> service.regenerateMaster(USER_ID, OLD))
                .isInstanceOf(ConflictException.class)
                .hasMessage(BookingLinkRetirementWriter.STALE_MESSAGE);
    }

    @Test
    @DisplayName("378 audit INFO-4: a lock-order deadlock in the salon regenerate → 409 stale")
    void should_mapToConflict_when_salonRegenerateDeadlocks() {
        when(salonRepository.findBookingLinkStateById(SALON_ID)).thenReturn(Optional.of(salon(USER_ID, true, OLD, false)));
        when(writer.regenerateSalon(SALON_ID, "Alfa", OLD, USER_ID))
                .thenThrow(new CannotAcquireLockException("deadlock detected"));

        assertThatThrownBy(() -> service.regenerateSalon(USER_ID, Role.SALON_OWNER, SALON_ID, OLD))
                .isInstanceOf(ConflictException.class)
                .hasMessage(BookingLinkRetirementWriter.STALE_MESSAGE);

        verifyNoInteractions(bookingLinkService);
    }

    // ── SALON (D6)───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner of the salon regenerates → writer called with the salon name; ACTIVE response with bookability")
    void should_regenerate_when_ownerOwnsSalon() {
        when(salonRepository.findBookingLinkStateById(SALON_ID)).thenReturn(Optional.of(salon(USER_ID, true, OLD, false)));
        when(writer.regenerateSalon(SALON_ID, "Alfa", OLD, USER_ID)).thenReturn(NEW);
        when(bookingLinkService.isSalonBookable(SALON_ID)).thenReturn(true);

        service.regenerateSalon(USER_ID, Role.SALON_OWNER, SALON_ID, OLD);

        verify(bookingLinkService).salonLinkWith(SALON_ID, "Alfa", NEW, true);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("bound admin revokes → REVOKED salon link; membership from the stored users row")
    void should_revoke_when_adminBoundToSalon() {
        when(salonRepository.findBookingLinkStateById(SALON_ID))
                .thenReturn(Optional.of(salon(UUID.randomUUID(), true, OLD, false)));
        when(authorizationService.adminBelongsToSalon(USER_ID, SALON_ID)).thenReturn(true);

        BookingLinkResponse response = service.revokeSalon(USER_ID, Role.SALON_ADMIN, SALON_ID, OLD);

        verify(writer).revokeSalon(SALON_ID, OLD, USER_ID);
        assertThat(response).isEqualTo(BookingLinkResponse.revoked(BookingLinkKind.SALON, "Alfa", SALON_ID));
    }

    @Test
    @DisplayName("owner of ANOTHER salon → 403, writer never called")
    void should_throwForbidden_when_ownerDoesNotOwnSalon() {
        when(salonRepository.findBookingLinkStateById(SALON_ID))
                .thenReturn(Optional.of(salon(UUID.randomUUID(), true, OLD, false)));

        assertThatThrownBy(() -> service.regenerateSalon(USER_ID, Role.SALON_OWNER, SALON_ID, OLD))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("admin NOT bound to the salon → 403, writer never called")
    void should_throwForbidden_when_adminNotBoundToSalon() {
        when(salonRepository.findBookingLinkStateById(SALON_ID))
                .thenReturn(Optional.of(salon(UUID.randomUUID(), true, OLD, false)));
        when(authorizationService.adminBelongsToSalon(USER_ID, SALON_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.revokeSalon(USER_ID, Role.SALON_ADMIN, SALON_ID, OLD))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(writer);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_MASTER", "CLIENT", "INDEPENDENT_MASTER"})
    @DisplayName("any role other than owner/admin reaching the service → 403 (defence behind @PreAuthorize)")
    void should_throwForbidden_when_roleNotOwnerOrAdmin(Role role) {
        when(salonRepository.findBookingLinkStateById(SALON_ID)).thenReturn(Optional.of(salon(USER_ID, true, OLD, false)));

        assertThatThrownBy(() -> service.regenerateSalon(USER_ID, role, SALON_ID, OLD))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("unknown salon → 403 (never an existence oracle)")
    void should_throwForbidden_when_salonUnknown() {
        when(salonRepository.findBookingLinkStateById(SALON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regenerateSalon(USER_ID, Role.SALON_OWNER, SALON_ID, OLD))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("own INACTIVE salon → 404")
    void should_throwNotFound_when_ownSalonInactive() {
        when(salonRepository.findBookingLinkStateById(SALON_ID)).thenReturn(Optional.of(salon(USER_ID, false, OLD, false)));

        assertThatThrownBy(() -> service.revokeSalon(USER_ID, Role.SALON_OWNER, SALON_ID, OLD))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("salon D7 early check: currentSlug null while a slug is live → 409")
    void should_throwConflict_when_salonCurrentSlugStale() {
        when(salonRepository.findBookingLinkStateById(SALON_ID)).thenReturn(Optional.of(salon(USER_ID, true, OLD, false)));

        assertThatThrownBy(() -> service.regenerateSalon(USER_ID, Role.SALON_OWNER, SALON_ID, null))
                .isInstanceOf(ConflictException.class);

        verify(writer, never()).regenerateSalon(any(), anyString(), any(), any());
    }
}
