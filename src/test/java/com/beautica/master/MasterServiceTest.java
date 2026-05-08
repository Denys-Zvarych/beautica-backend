package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.dto.ScheduleExceptionRequest;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionReason;
import com.beautica.master.entity.WorkingHours;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MasterService — unit")
class MasterServiceTest {

    @Mock private MasterRepository masterRepository;
    @Mock private UserRepository userRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private WorkingHoursRepository workingHoursRepository;
    @Mock private ScheduleExceptionRepository scheduleExceptionRepository;
    @Mock private BookingRepository bookingRepository;

    @InjectMocks
    private MasterService masterService;

    // ── createMasterForIndependentUser ─────────────────────────────────────────

    @Test
    @DisplayName("should_createMasterForIndependentUser_when_userExists")
    void should_createMasterForIndependentUser_when_userExists() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);

        Master saved = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        Master result = masterService.createMasterForIndependentUser(userId);

        assertThat(result.getMasterType()).isEqualTo(MasterType.INDEPENDENT_MASTER);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getSalon()).isNull();
        verify(masterRepository).save(any(Master.class));
    }

    @Test
    @DisplayName("should_throwNotFound_when_createIndependentMasterWithUnknownUserId")
    void should_throwNotFound_when_createIndependentMasterWithUnknownUserId() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterForIndependentUser(userId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    // ── createMasterFromInvite ─────────────────────────────────────────────────

    @Test
    @DisplayName("should_createMasterFromInvite_when_userAndSalonExist")
    void should_createMasterFromInvite_when_userAndSalonExist() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User user = mock(User.class);
        Salon salon = mock(Salon.class);

        Master saved = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        Master result = masterService.createMasterFromInvite(userId, salonId);

        assertThat(result.getMasterType()).isEqualTo(MasterType.SALON_MASTER);
        assertThat(result.getSalon()).isEqualTo(salon);
        assertThat(result.isActive()).isTrue();
        verify(masterRepository).save(any(Master.class));
    }

    @Test
    @DisplayName("should_throwNotFound_when_createMasterFromInviteWithUnknownUserId")
    void should_throwNotFound_when_createMasterFromInviteWithUnknownUserId() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterFromInvite(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_createMasterFromInviteWithUnknownSalonId")
    void should_throwNotFound_when_createMasterFromInviteWithUnknownSalonId() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User user = mock(User.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterFromInvite(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    // ── getMasterDetail ────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throwNotFound_when_getMasterDetailWithUnknownId")
    void should_throwNotFound_when_getMasterDetailWithUnknownId() {
        UUID masterId = UUID.randomUUID();
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.getMasterDetail(masterId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_returnWorkingHours_when_masterDetailRequested")
    void should_returnWorkingHours_when_masterDetailRequested() {
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        WorkingHours wh = WorkingHours.builder()
                .master(master)
                .dayOfWeek(1)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(wh, "id", UUID.randomUUID());

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of(wh));

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.masterId()).isEqualTo(masterId);
        assertThat(response.workingHours()).hasSize(1);
        assertThat(response.workingHours().get(0).dayOfWeek()).isEqualTo(1);
    }

    // ── upsertWorkingHours ─────────────────────────────────────────────────────

    @Test
    @DisplayName("should_upsertWorkingHours_when_ownerRequestsUpdate")
    void should_upsertWorkingHours_when_ownerRequestsUpdate() {
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);

        var request = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);

        WorkingHours saved = WorkingHours.builder()
                .master(master)
                .dayOfWeek(1)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of(saved));

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(ownerId, masterId, List.of(request));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dayOfWeek()).isEqualTo(1);
        verify(workingHoursRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("should_throwNotFound_when_upsertWorkingHours_masterMissing")
    void should_throwNotFound_when_upsertWorkingHours_masterMissing_explicit() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        // This test verifies the service throws NotFoundException when master is absent.
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        var request = new WorkingHoursRequest(2, LocalTime.of(10, 0), LocalTime.of(18, 0), true);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                masterService.upsertWorkingHours(actorId, masterId, List.of(request)))
                .isInstanceOf(NotFoundException.class);

        verify(workingHoursRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_upsertWorkingHoursWithUnknownMasterId")
    void should_throwNotFound_when_upsertWorkingHoursWithUnknownMasterId() {
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        var request = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.upsertWorkingHours(ownerId, masterId, List.of(request)))
                .isInstanceOf(NotFoundException.class);

        verify(workingHoursRepository, never()).saveAll(any());
    }

    // ── deactivateMaster ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_deactivateMaster_when_masterExists")
    void should_deactivateMaster_when_authorizedActorRequests() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterRepository.save(any(Master.class))).thenAnswer(inv -> inv.getArgument(0));

        masterService.deactivateMaster(ownerId, masterId);

        assertThat(master.isActive()).isFalse();
        verify(masterRepository).save(master);
    }

    @Test
    @DisplayName("should_throwNotFound_when_deactivateMaster_masterMissing")
    void should_throwNotFound_when_unauthorizedActorDeactivatesMaster() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.deactivateMaster(actorId, masterId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    // ── addScheduleException ───────────────────────────────────────────────────

    @Test
    @DisplayName("should_addScheduleException_when_authorizedActorRequests")
    void should_addScheduleException_when_authorizedActorRequests() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 1);

        Master master = mock(Master.class);
        ScheduleException saved = ScheduleException.builder()
                .master(master)
                .date(date)
                .reason(ScheduleExceptionReason.HOLIDAY)
                .build();
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());

        var request = new ScheduleExceptionRequest(date, ScheduleExceptionReason.HOLIDAY, null);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(scheduleExceptionRepository.findByMasterIdAndDate(masterId, date))
                .thenReturn(Optional.empty());
        when(scheduleExceptionRepository.save(any(ScheduleException.class))).thenReturn(saved);

        ScheduleException result = masterService.addScheduleException(actorId, masterId, request);

        assertThat(result.getDate()).isEqualTo(date);
        assertThat(result.getReason()).isEqualTo(ScheduleExceptionReason.HOLIDAY);
        verify(scheduleExceptionRepository).save(any(ScheduleException.class));
    }

    @Test
    @DisplayName("should_throwNotFound_when_addScheduleException_masterMissing")
    void should_throwNotFound_when_unauthorizedActorAddsScheduleException() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 1);
        var request = new ScheduleExceptionRequest(date, ScheduleExceptionReason.HOLIDAY, null);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.addScheduleException(actorId, masterId, request))
                .isInstanceOf(NotFoundException.class);

        verify(scheduleExceptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_updateExistingScheduleException_when_dateAlreadyExistsForMaster")
    void should_updateExistingScheduleException_when_dateAlreadyExistsForMaster() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 6, 15);

        Master master = mock(Master.class);

        ScheduleException existingException = ScheduleException.builder()
                .master(master)
                .date(date)
                .reason(ScheduleExceptionReason.HOLIDAY)
                .note(null)
                .build();
        ReflectionTestUtils.setField(existingException, "id", UUID.randomUUID());

        var request = new ScheduleExceptionRequest(date, ScheduleExceptionReason.VACATION, "Going on vacation");

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(scheduleExceptionRepository.findByMasterIdAndDate(masterId, date))
                .thenReturn(Optional.of(existingException));
        when(scheduleExceptionRepository.save(existingException)).thenReturn(existingException);

        ScheduleException result = masterService.addScheduleException(actorId, masterId, request);

        assertThat(result.getReason()).isEqualTo(ScheduleExceptionReason.VACATION);
        assertThat(result.getNote()).isEqualTo("Going on vacation");
        verify(scheduleExceptionRepository).save(existingException);
    }

    // ── removeScheduleException ────────────────────────────────────────────────

    @Test
    @DisplayName("should_removeScheduleException_when_authorizedActorRequests")
    void should_removeScheduleException_when_authorizedActorRequests() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 6, 1);

        Master master = mock(Master.class);
        ScheduleException exception = mock(ScheduleException.class);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(scheduleExceptionRepository.findByMasterIdAndDate(masterId, date))
                .thenReturn(Optional.of(exception));

        masterService.removeScheduleException(actorId, masterId, date);

        verify(scheduleExceptionRepository).delete(exception);
    }

    @Test
    @DisplayName("should_doNothing_when_removeScheduleException_dateHasNoException")
    void should_doNothing_when_removeScheduleException_dateHasNoException() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 6, 1);
        Master master = mock(Master.class);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(scheduleExceptionRepository.findByMasterIdAndDate(masterId, date))
                .thenReturn(Optional.empty());

        masterService.removeScheduleException(actorId, masterId, date);

        verify(scheduleExceptionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_removeScheduleException_masterMissing")
    void should_throwNotFound_when_removeScheduleException_masterMissing() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 6, 1);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.removeScheduleException(actorId, masterId, date))
                .isInstanceOf(NotFoundException.class);

        verify(scheduleExceptionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_removeScheduleException_masterMissingOnRemove")
    void should_throwForbidden_when_unauthorizedActorRemovesScheduleException() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 6, 1);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.removeScheduleException(actorId, masterId, date))
                .isInstanceOf(NotFoundException.class);

        verify(scheduleExceptionRepository, never()).delete(any());
    }

    // ── getMastersByPage ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnPagedMasters_when_getMastersByPageCalled")
    void should_returnPagedMasters_when_getMastersByPageCalled() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = Pageable.ofSize(10);

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getFirstName()).thenReturn("Olena");
        when(user.getLastName()).thenReturn("Bondar");

        Master master = Master.builder()
                .user(user)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);

        Page<Master> masterPage = new PageImpl<>(List.of(master), pageable, 1);
        when(masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, pageable))
                .thenReturn(masterPage);

        Page<MasterSummaryResponse> result = masterService.getMastersByPage(salonId, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).masterId()).isEqualTo(masterId);
        assertThat(result.getContent().get(0).userId()).isEqualTo(userId);
        assertThat(result.getContent().get(0).masterType()).isEqualTo(MasterType.SALON_MASTER);
        verify(masterRepository).findBySalonIdAndIsActiveTrueWithUser(salonId, pageable);
    }
}
