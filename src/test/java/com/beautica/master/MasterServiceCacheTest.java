package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.CacheConfig;
import com.beautica.master.dto.ScheduleExceptionRequest;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionReason;
import com.beautica.master.entity.WorkingHours;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {MasterService.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(MasterServiceCacheTest.TransactionConfig.class)
@DisplayName("MasterService — @CacheEvict afterCommit behaviour")
class MasterServiceCacheTest {

    @TestConfiguration
    static class TransactionConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() { return new Object(); }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                @Override
                protected void doCommit(DefaultTransactionStatus status) {}

                @Override
                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
        }
    }

    @MockBean MasterRepository masterRepository;
    @MockBean UserRepository userRepository;
    @MockBean SalonRepository salonRepository;
    @MockBean WorkingHoursRepository workingHoursRepository;
    @MockBean ScheduleExceptionRepository scheduleExceptionRepository;
    @MockBean BookingRepository bookingRepository;

    @Autowired MasterService masterService;
    @Autowired CacheManager cacheManager;
    @Autowired TransactionTemplate transactionTemplate;

    private static final UUID ACTOR_ID  = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("master-calendar");
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("upsertWorkingHours evicts the master-calendar cache after commit")
    void should_evictMasterCalendarCache_when_upsertWorkingHours() {
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        cache.put("sentinel", "value");
        assertThat(cache.get("sentinel")).isNotNull();

        Master master = mock(Master.class);
        when(masterRepository.findByIdWithSalonAndOwner(MASTER_ID)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(MASTER_ID)).thenReturn(List.of());
        WorkingHours savedWh = mock(WorkingHours.class);
        when(workingHoursRepository.saveAll(any())).thenReturn(List.of(savedWh));
        when(savedWh.getDayOfWeek()).thenReturn(1);
        when(savedWh.getStartTime()).thenReturn(LocalTime.of(9, 0));
        when(savedWh.getEndTime()).thenReturn(LocalTime.of(18, 0));
        when(savedWh.isActive()).thenReturn(true);

        WorkingHoursRequest request = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(18, 0), true);

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            masterService.upsertWorkingHours(ACTOR_ID, MASTER_ID, List.of(request));
            return null;
        });

        assertThat(cache.get("sentinel"))
                .as("master-calendar cache must be evicted after upsertWorkingHours commits")
                .isNull();
    }

    @Test
    @DisplayName("addScheduleException evicts the master-calendar cache after commit")
    void should_evictMasterCalendarCache_when_addScheduleException() {
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        cache.put("sentinel", "value");
        assertThat(cache.get("sentinel")).isNotNull();

        Master master = mock(Master.class);
        when(masterRepository.findByIdWithSalonAndOwner(MASTER_ID)).thenReturn(Optional.of(master));

        LocalDate exceptionDate = LocalDate.now().plusDays(3);
        ScheduleExceptionRequest request = new ScheduleExceptionRequest(
                exceptionDate, ScheduleExceptionReason.SICK_DAY, null);

        when(scheduleExceptionRepository.findByMasterIdAndDate(MASTER_ID, exceptionDate))
                .thenReturn(Optional.empty());
        ScheduleException saved = mock(ScheduleException.class);
        when(scheduleExceptionRepository.save(any())).thenReturn(saved);

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            masterService.addScheduleException(ACTOR_ID, MASTER_ID, request);
            return null;
        });

        assertThat(cache.get("sentinel"))
                .as("master-calendar cache must be evicted after addScheduleException commits")
                .isNull();
    }

    @Test
    @DisplayName("removeScheduleException evicts the master-calendar cache after commit")
    void should_evictMasterCalendarCache_when_removeScheduleException() {
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        cache.put("sentinel", "value");
        assertThat(cache.get("sentinel")).isNotNull();

        Master master = mock(Master.class);
        when(masterRepository.findByIdWithSalonAndOwner(MASTER_ID)).thenReturn(Optional.of(master));

        LocalDate exceptionDate = LocalDate.now().plusDays(5);
        when(scheduleExceptionRepository.findByMasterIdAndDate(MASTER_ID, exceptionDate))
                .thenReturn(Optional.empty());

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            masterService.removeScheduleException(ACTOR_ID, MASTER_ID, exceptionDate);
            return null;
        });

        assertThat(cache.get("sentinel"))
                .as("master-calendar cache must be evicted after removeScheduleException commits")
                .isNull();
    }

    @Test
    @DisplayName("deactivateMaster evicts the master-calendar cache after commit")
    void should_evictMasterCalendarCache_when_deactivateMaster() {
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        cache.put("sentinel", "value");
        assertThat(cache.get("sentinel")).isNotNull();

        Master master = mock(Master.class);
        when(masterRepository.findByIdWithSalonAndOwner(MASTER_ID)).thenReturn(Optional.of(master));
        when(masterRepository.save(master)).thenReturn(master);

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            masterService.deactivateMaster(ACTOR_ID, MASTER_ID);
            return null;
        });

        assertThat(cache.get("sentinel"))
                .as("master-calendar cache must be evicted after deactivateMaster commits")
                .isNull();
    }
}
