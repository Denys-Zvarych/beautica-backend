package com.beautica.booking.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.CacheConfig;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.User;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {BookingService.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(BookingServiceCacheTest.FixedClockConfig.class)
@DisplayName("BookingService — @CacheEvict behaviour")
class BookingServiceCacheTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneId.of("Europe/Kyiv"));
        }
    }

    @MockBean BookingRepository bookingRepository;
    @MockBean MasterRepository masterRepository;
    @MockBean MasterServiceRepository masterServiceRepository;
    @MockBean UserRepository userRepository;
    @MockBean AuthorizationService authz;
    @MockBean NotificationOutboxService outboxService;
    @MockBean SlotCalculationService slotCalculationService;

    @Autowired BookingService bookingService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("master-calendar");
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("confirmBooking evicts the master-calendar cache")
    void should_evictMasterCalendarCache_when_confirmBookingCalled() {
        UUID actorUserId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // Arrange — put a sentinel entry in master-calendar to confirm eviction
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        cache.put("sentinel", "value");
        assertThat(cache.get("sentinel")).isNotNull();

        // Build a mock Booking in PENDING status with all fields BookingResponse.from() needs
        Booking booking = mock(Booking.class);
        User client = mock(User.class);
        Master master = mock(Master.class);
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        ServiceDefinition serviceDef = mock(ServiceDefinition.class);

        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getMasterService()).thenReturn(msa);
        when(msa.getServiceDefinition()).thenReturn(serviceDef);
        when(msa.getId()).thenReturn(UUID.randomUUID());
        when(client.getId()).thenReturn(UUID.randomUUID());
        when(master.getId()).thenReturn(UUID.randomUUID());
        when(serviceDef.getName()).thenReturn("Test Service");
        when(booking.getStartsAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));
        when(booking.getEndsAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).plusHours(3));
        when(booking.getPriceAtBooking()).thenReturn(new BigDecimal("200.00"));
        when(booking.getDurationMinutesAtBooking()).thenReturn(60);
        when(booking.getCreatedAt()).thenReturn(Instant.now());

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        doNothing().when(authz).enforceCanManageBooking(actorUserId, booking);

        // Act
        bookingService.confirmBooking(actorUserId, bookingId);

        // Assert — sentinel must be gone: allEntries=true evicts the entire cache
        assertThat(cache.get("sentinel"))
                .as("master-calendar cache must be fully evicted after confirmBooking")
                .isNull();
    }
}
