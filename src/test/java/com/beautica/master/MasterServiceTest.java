package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private CacheManager cacheManager;
    // CRITICAL: must be declared so @InjectMocks can satisfy the CityRepository constructor
    // parameter — without it the field receives null and resolveOblastId throws NPE whenever
    // getCityId() returns a non-null value.
    @Mock private CityRepository cityRepository;

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

        User user = mock(User.class);
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
        // HIGH-2: cityId must be null when user.getCityId() returns null (fast-path)
        assertThat(response.cityId()).isNull();
        // CRITICAL: cityRepository must never be called when cityId is null
        verifyNoInteractions(cityRepository);
    }

    // ── resolveOblastId paths ─────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnOblastId_when_cityIdIsPresent")
    void should_returnOblastId_when_cityIdIsPresent() {
        UUID masterId = UUID.randomUUID();
        UUID cityUuid = UUID.randomUUID();
        UUID oblastUuid = UUID.randomUUID();

        Oblast oblast = mock(Oblast.class);
        when(oblast.getId()).thenReturn(oblastUuid);

        City city = mock(City.class);
        when(city.getOblast()).thenReturn(oblast);

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(cityUuid);
        when(user.getDistrictId()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());
        when(cityRepository.findByIdWithOblast(cityUuid)).thenReturn(Optional.of(city));

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.cityId()).isEqualTo(cityUuid);
        assertThat(response.oblastId()).isEqualTo(oblastUuid);
        verify(cityRepository).findByIdWithOblast(cityUuid);
    }

    @Test
    @DisplayName("should_returnNullOblastId_when_cityIdIsNull")
    void should_returnNullOblastId_when_cityIdIsNull() {
        UUID masterId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.cityId()).isNull();
        assertThat(response.oblastId()).isNull();
        verify(cityRepository, never()).findByIdWithOblast(any());
    }

    @Test
    @DisplayName("should_returnNullOblastId_when_cityIdPresentButCityRowMissing")
    void should_returnNullOblastId_when_cityIdPresentButCityRowMissing() {
        UUID masterId = UUID.randomUUID();
        UUID cityUuid = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(cityUuid);
        when(user.getDistrictId()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());
        when(cityRepository.findByIdWithOblast(cityUuid)).thenReturn(Optional.empty());

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.oblastId()).isNull();
        verify(cityRepository).findByIdWithOblast(cityUuid);
    }

    // ── deactivateMaster — cache eviction ─────────────────────────────────────

    @Test
    @DisplayName("should_evictMasterDetailCache_when_deactivateMasterCalled")
    void should_evictMasterDetailCache_when_deactivateMasterCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "user", user);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

        // Stub both caches so the afterCommit eviction path does not NPE.
        Cache masterDetailCache = mock(Cache.class);
        Cache masterByUserCache = mock(Cache.class);
        when(cacheManager.getCache("master-detail")).thenReturn(masterDetailCache);
        when(cacheManager.getCache("master-by-user")).thenReturn(masterByUserCache);
        // master-calendar is also evicted by deactivateMaster via evictMasterCalendarAfterCommit.
        Cache masterCalendarCache = mock(Cache.class);
        when(cacheManager.getCache("master-calendar")).thenReturn(masterCalendarCache);

        // deactivateMaster guards eviction registration with isSynchronizationActive().
        // Manually initialise Spring transaction synchronization so the guard passes in this
        // non-transactional unit test, then capture and replay afterCommit().
        TransactionSynchronizationManager.initSynchronization();
        try {
            masterService.deactivateMaster(ownerId, masterId);

            // Capture all registered synchronizations and invoke afterCommit() on each.
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            syncs.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // The master-detail cache must have been asked to evict the deactivated master's key.
        verify(masterDetailCache).evict(masterId);
        // The master-by-user cache must also have been evicted for the user.
        verify(masterByUserCache).evict(userId);
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
        // upsert merge map is built from ALL rows (incl. inactive) via findByMasterId, not the
        // active-only finder — matching production after the 23505 duplicate-INSERT fix.
        when(workingHoursRepository.findByMasterId(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of(saved));

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(ownerId, masterId, List.of(request));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dayOfWeek()).isEqualTo(1);
        verify(workingHoursRepository).saveAll(anyList());
    }

    // ── upsertWorkingHours — inactive-row reuse (regression: UNIQUE(master_id, day_of_week) 23505) ──
    // Original bug: the merge map was built with the active-only finder
    // (findByMasterIdAndIsActiveTrue), so a pre-existing INACTIVE (master_id, day_of_week) row was
    // invisible. The merge treated that weekday as new → built a WorkingHours with a null @Id →
    // saveAll emitted an INSERT that violated UNIQUE(master_id, day_of_week)
    // (DataIntegrityViolationException, SQLState 23505).
    // Fix: build the merge map from findByMasterId (ALL rows, incl. inactive) so the existing row
    // is matched and UPDATEd in place (same @Id, setActive(true)) instead of re-INSERTed.

    @Test
    @DisplayName("should_updateInactiveRowInPlace_when_upsertWorkingHoursForPreviouslyDeactivatedDay")
    void should_updateInactiveRowInPlace_when_upsertWorkingHoursForPreviouslyDeactivatedDay() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID existingRowId = UUID.randomUUID();
        Master master = mock(Master.class);

        // Pre-seed an EXISTING, INACTIVE working-hours row for day 2 with a non-null @Id.
        // This is the row the active-only finder would have hidden, causing the duplicate INSERT.
        WorkingHours existingInactiveRow = WorkingHours.builder()
                .master(master)
                .dayOfWeek(2)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(12, 0))
                .isActive(false)
                .build();
        ReflectionTestUtils.setField(existingInactiveRow, "id", existingRowId);

        // Re-enable day 2 with new hours and isActive = true.
        var request = new WorkingHoursRequest(2, LocalTime.of(9, 0), LocalTime.of(17, 0), true);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        // The merge map MUST be built from the all-rows finder so the inactive row is visible.
        when(workingHoursRepository.findByMasterId(masterId))
                .thenReturn(List.of(existingInactiveRow));
        when(workingHoursRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        masterService.upsertWorkingHours(actorId, masterId, List.of(request));

        // Capture exactly what was handed to saveAll — the merge result.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkingHours>> captor = ArgumentCaptor.forClass(List.class);
        verify(workingHoursRepository).saveAll(captor.capture());
        List<WorkingHours> persisted = captor.getValue();

        // Exactly ONE row for day 2 — no second (duplicate) row was created.
        assertThat(persisted)
                .as("merge must produce a single row for day 2, not a duplicate INSERT")
                .hasSize(1);

        WorkingHours mergedRow = persisted.get(0);
        // Updated IN PLACE: the SAME @Id is retained (no new row → no UNIQUE violation).
        assertThat(mergedRow.getId())
                .as("the existing inactive row must be reused, retaining its @Id")
                .isEqualTo(existingRowId);
        // The previously-inactive row is now active with the requested hours.
        assertThat(mergedRow.isActive())
                .as("the reused row must be re-activated")
                .isTrue();
        assertThat(mergedRow.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(mergedRow.getEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(mergedRow.getDayOfWeek()).isEqualTo(2);

        // The merge map is built from the all-rows finder; the active-only finder must NOT be used
        // on the upsert path (that finder would hide the inactive row and reintroduce the bug).
        verify(workingHoursRepository).findByMasterId(masterId);
        verify(workingHoursRepository, never()).findByMasterIdAndIsActiveTrue(any());
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

    // ── upsertWorkingHours — duplicate-dayOfWeek guard (regression) ────────────
    // A payload with two entries sharing the same dayOfWeek must be rejected with a
    // BusinessException(BAD_REQUEST) BEFORE any DB work. Without this guard the
    // byDay collector would silently collapse duplicates to the last-wins entry.
    // The descriptive message stays server-side; GlobalExceptionHandler.handleBusiness
    // rewrites it to the generic "Invalid request" 400 on the wire (asserted at the
    // controller layer below).

    @Test
    @DisplayName("should_throwBadRequest_when_upsertWorkingHours_payloadHasDuplicateDay")
    void should_throwBadRequest_when_upsertWorkingHoursPayloadHasDuplicateDay() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        // Two entries both targeting dayOfWeek=1 — the guard must trip on the second.
        var first = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(13, 0), true);
        var duplicate = new WorkingHoursRequest(1, LocalTime.of(14, 0), LocalTime.of(18, 0), true);

        assertThatThrownBy(() ->
                masterService.upsertWorkingHours(actorId, masterId, List.of(first, duplicate)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate working-hours entry for the same day");

        // Guard runs before any repository access — no master lookup, no merge-map fetch, no save.
        verify(masterRepository, never()).findByIdWithSalonAndOwner(any());
        verify(workingHoursRepository, never()).findByMasterId(any());
        verify(workingHoursRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("should_upsertWorkingHours_when_payloadHasTwoDistinctDays")
    void should_upsertWorkingHours_when_payloadHasTwoDistinctDays() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = mock(Master.class);

        var monday = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);
        var tuesday = new WorkingHoursRequest(2, LocalTime.of(10, 0), LocalTime.of(18, 0), true);

        WorkingHours savedMon = WorkingHours.builder()
                .master(master).dayOfWeek(1)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).isActive(true).build();
        WorkingHours savedTue = WorkingHours.builder()
                .master(master).dayOfWeek(2)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(18, 0)).isActive(true).build();
        ReflectionTestUtils.setField(savedMon, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(savedTue, "id", UUID.randomUUID());

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        // upsert merge map uses the all-rows finder (incl. inactive) — see production fix.
        when(workingHoursRepository.findByMasterId(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of(savedMon, savedTue));

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(actorId, masterId, List.of(monday, tuesday));

        // Distinct days never trip the guard — both entries persisted.
        assertThat(result).hasSize(2);
        assertThat(result).extracting(WorkingHoursResponse::dayOfWeek).containsExactly(1, 2);
        verify(workingHoursRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("should_returnEmptyListAsNoOp_when_upsertWorkingHoursPayloadIsEmpty")
    void should_returnEmptyListAsNoOp_when_upsertWorkingHoursPayloadIsEmpty() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = mock(Master.class);

        // Empty payload must never trip the duplicate guard; it stays a clean no-op
        // (master is loaded, saveAll receives an empty list, an empty list is returned).
        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        // upsert merge map uses the all-rows finder (incl. inactive) — see production fix.
        when(workingHoursRepository.findByMasterId(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of());

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(actorId, masterId, List.of());

        assertThat(result).isEmpty();
        verify(workingHoursRepository).saveAll(anyList());
    }

    // ── deactivateMaster ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_deactivateMaster_when_masterExists")
    void should_deactivateMaster_when_authorizedActorRequests() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        // save() is no longer called — Hibernate dirty-checking flushes the mutation on commit.
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "user", user);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

        masterService.deactivateMaster(ownerId, masterId);

        assertThat(master.isActive()).isFalse();
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_deactivateMaster_masterMissing")
    void should_throwNotFound_when_deactivateMaster_masterMissing() {
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

    // ── getMasterByUserId ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnMaster_when_getMasterByUserIdAndMasterExists")
    void should_returnMaster_when_getMasterByUserIdAndMasterExists() {
        UUID userId = UUID.randomUUID();
        Master master = Master.builder()
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.of(master));

        Master result = masterService.getMasterByUserId(userId);

        assertThat(result).isEqualTo(master);
        assertThat(result.getMasterType()).isEqualTo(MasterType.INDEPENDENT_MASTER);
    }

    @Test
    @DisplayName("should_throwNotFound_when_getMasterByUserIdAndNoMasterRecord")
    void should_throwNotFound_when_getMasterByUserIdAndNoMasterRecord() {
        UUID userId = UUID.randomUUID();

        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.getMasterByUserId(userId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_throwNotFound_when_getMasterByUserId_andMasterIsDeactivated")
    void should_throwNotFound_when_getMasterByUserId_andMasterIsDeactivated() {
        UUID userId = UUID.randomUUID();

        // findActiveByUserIdWithUserAndSalon filters isActive=true at the DB level —
        // a deactivated master returns empty, which must surface as 404.
        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.getMasterByUserId(userId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_throwNotFound_when_getMyMasterDetail_andMasterIsDeactivated")
    void should_throwNotFound_when_getMyMasterDetail_andMasterIsDeactivated() {
        UUID userId = UUID.randomUUID();

        // DB-level isActive=true filter: deactivated master returns empty Optional,
        // preventing a master with a valid JWT from accessing GET /masters/me.
        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.getMyMasterDetail(userId))
                .isInstanceOf(NotFoundException.class);
    }

    // ── createMasterForOwner (entity overload) ────────────────────────────────
    // Tests target the entity overload directly — no repo mocking required for
    // user/salon loading since both are passed in already (Findings 3 + 4 fix).

    @Test
    @DisplayName("should_createOwnerMaster_when_ownerHasNoExistingMasterRow")
    void should_createOwnerMaster_when_ownerHasNoExistingMasterRow() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);

        Master saved = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon to avoid a lazy SELECT on
        // getSalon().getId() (MEDIUM F3 fix) — stub the new graph method.
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.empty());
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        Master result = masterService.createMasterForOwner(user, salon);

        assertThat(result.getMasterType()).isEqualTo(MasterType.SALON_OWNER);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getAvgRating()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(masterRepository).save(any(Master.class));
        verify(userRepository, never()).findById(any());
        verify(salonRepository, never()).findById(any());
    }

    @Test
    @DisplayName("should_returnExistingActiveRow_idempotent")
    void should_returnExistingActiveRow_idempotent() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);
        when(salon.getId()).thenReturn(salonId);

        Master existing = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(existing));

        Master result = masterService.createMasterForOwner(user, salon);

        assertThat(result).isSameAs(existing);
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_reactivate_when_existingOwnerMasterIsInactive")
    void should_reactivate_when_existingOwnerMasterIsInactive() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);
        when(salon.getId()).thenReturn(salonId);

        Master inactive = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(false)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(inactive));

        Master result = masterService.createMasterForOwner(user, salon);

        assertThat(result.isActive()).isTrue();
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwConflict_when_userHasIndependentMasterRow")
    void should_throwConflict_when_userHasIndependentMasterRow() {
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);

        Master independentMaster = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(independentMaster));

        assertThatThrownBy(() -> masterService.createMasterForOwner(user, salon))
                .isInstanceOf(ConflictException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwConflict_when_ownerMasterExistsInDifferentSalon")
    void should_throwConflict_when_ownerMasterExistsInDifferentSalon() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon requestedSalon = mock(Salon.class);
        when(requestedSalon.isActive()).thenReturn(true);
        when(requestedSalon.getOwner()).thenReturn(user);
        when(requestedSalon.getId()).thenReturn(salonId);

        Salon otherSalon = mock(Salon.class);
        when(otherSalon.getId()).thenReturn(otherSalonId);

        Master ownerMasterInOtherSalon = Master.builder()
                .user(user)
                .salon(otherSalon)
                .masterType(MasterType.SALON_OWNER)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(ownerMasterInOtherSalon));

        assertThatThrownBy(() -> masterService.createMasterForOwner(user, requestedSalon))
                .isInstanceOf(ConflictException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwForbidden_when_actorRoleIsNotSalonOwner")
    void should_throwForbidden_when_actorRoleIsNotSalonOwner() {
        User client = mock(User.class);
        when(client.getRole()).thenReturn(Role.CLIENT);

        Salon salon = mock(Salon.class);

        assertThatThrownBy(() -> masterService.createMasterForOwner(client, salon))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).findById(any());
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwForbidden_when_actorDoesNotOwnSalon")
    void should_throwForbidden_when_actorDoesNotOwnSalon() {
        UUID actorId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID();

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_OWNER);
        when(actor.getId()).thenReturn(actorId);

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(realOwner);

        assertThatThrownBy(() -> masterService.createMasterForOwner(actor, salon))
                .isInstanceOf(ForbiddenException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwBusiness_when_salonIsInactive")
    void should_throwBusiness_when_salonIsInactive() {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);

        Salon inactiveSalon = mock(Salon.class);
        when(inactiveSalon.isActive()).thenReturn(false);

        assertThatThrownBy(() -> masterService.createMasterForOwner(user, inactiveSalon))
                .isInstanceOf(BusinessException.class);

        verify(masterRepository, never()).save(any());
    }

    // ── createMasterForOwner (UUID overload) — delegates to entity overload ──

    @Test
    @DisplayName("should_throwNotFound_when_salonMissing")
    void should_throwNotFound_when_salonMissing() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        // UUID overload loads user then salon — role is not checked until inside the entity
        // overload, but we never reach it because salonRepository returns empty first.
        User user = mock(User.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterForOwner(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_userMissing")
    void should_throwNotFound_when_userMissing() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterForOwner(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(salonRepository, never()).findById(any());
        verify(masterRepository, never()).save(any());
    }

    // ── getMastersByPage ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnPagedMasters_when_getMastersByPageCalled")
    void should_returnPagedMasters_when_getMastersByPageCalled() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Pageable pageable = Pageable.ofSize(10);

        User user = mock(User.class);
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
        assertThat(result.getContent().get(0).masterType()).isEqualTo(MasterType.SALON_MASTER);
        verify(masterRepository).findBySalonIdAndIsActiveTrueWithUser(salonId, pageable);
    }
}
