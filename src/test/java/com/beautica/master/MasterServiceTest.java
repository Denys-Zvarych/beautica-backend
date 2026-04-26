package com.beautica.master;

import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.dto.MasterDetailResponse;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
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
    @Mock private AuthorizationService authorizationService;

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
        when(workingHoursRepository.findByMasterId(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of(saved));

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(ownerId, masterId, List.of(request));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dayOfWeek()).isEqualTo(1);
        verify(workingHoursRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("should_throwForbidden_when_salonMasterTriesToEditOwnSchedule")
    void should_throwForbidden_when_salonMasterTriesToEditOwnSchedule() {
        UUID salonMasterUserId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);

        var request = new WorkingHoursRequest(2, LocalTime.of(10, 0), LocalTime.of(18, 0), true);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        doThrow(new ForbiddenException("Access denied"))
                .when(authorizationService).enforceCanManageMasterSchedule(salonMasterUserId, master);

        // actorId is the salon master's user id, not the owner — should be rejected
        assertThatThrownBy(() ->
                masterService.upsertWorkingHours(salonMasterUserId, masterId, List.of(request)))
                .isInstanceOf(ForbiddenException.class);

        verify(workingHoursRepository, never()).saveAll(any());
    }

    // ── deactivateMaster ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_deactivateMaster_when_authorizedActorRequests")
    void should_deactivateMaster_when_authorizedActorRequests() {
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(masterRepository.save(any(Master.class))).thenAnswer(inv -> inv.getArgument(0));
        // authorizationService is a mock — enforceCanManageMaster is a no-op by default

        masterService.deactivateMaster(ownerId, masterId);

        assertThat(master.isActive()).isFalse();
        verify(masterRepository).save(master);
        verify(authorizationService).enforceCanManageMaster(ownerId, master);
    }

    @Test
    @DisplayName("should_throwForbidden_when_unauthorizedActorDeactivatesMaster")
    void should_throwForbidden_when_unauthorizedActorDeactivatesMaster() {
        UUID attackerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        doThrow(new ForbiddenException("Access denied"))
                .when(authorizationService).enforceCanManageMaster(attackerId, master);

        assertThatThrownBy(() -> masterService.deactivateMaster(attackerId, masterId))
                .isInstanceOf(ForbiddenException.class);

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
        verify(authorizationService).enforceCanManageMasterSchedule(actorId, master);
        verify(scheduleExceptionRepository).save(any(ScheduleException.class));
    }
}
