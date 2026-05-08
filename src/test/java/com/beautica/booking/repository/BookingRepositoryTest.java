package com.beautica.booking.repository;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BookingRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private TestEntityManager em;

    private User clientUser;
    private Master master;
    private MasterServiceAssignment masterService;

    @BeforeEach
    void setUp() {
        clientUser = new User(
                "client-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.CLIENT,
                "Client",
                "User",
                "+380501111111"
        );
        em.persist(clientUser);

        User masterUser = new User(
                "master-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.INDEPENDENT_MASTER,
                "Master",
                "User",
                "+380502222222"
        );
        em.persist(masterUser);

        master = Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Manicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("450.00"))
                .isActive(true)
                .build();
        em.persist(serviceDefinition);

        masterService = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(masterService);

        em.flush();
    }

    private Booking buildBooking(BookingStatus status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return Booking.builder()
                .client(clientUser)
                .master(master)
                .masterService(masterService)
                .status(status)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
    }

    @Test
    @DisplayName("should_findOverlappingBookings_when_bookingSpansQueryWindow")
    void should_findOverlappingBookings_when_bookingSpansQueryWindow() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.PENDING, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime windowStart = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime windowEnd = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        List<Booking> result = bookingRepository.findOverlappingByMaster(
                master.getId(),
                windowStart,
                windowEnd
        );

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("should_notReturnBooking_when_statusIsDeclined")
    void should_notReturnBooking_when_statusIsDeclined() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.DECLINED, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime windowStart = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime windowEnd = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        List<Booking> result = bookingRepository.findOverlappingByMaster(
                master.getId(),
                windowStart,
                windowEnd
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_existsOverlapReturnTrue_when_pendingBookingConflicts")
    void should_existsOverlapReturnTrue_when_pendingBookingConflicts() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.PENDING, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime requestedStartsAt = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime requestedEndsAt = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        boolean result = bookingRepository.existsOverlap(master.getId(), requestedStartsAt, requestedEndsAt);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("should_existsOverlapReturnFalse_when_noConflict")
    void should_existsOverlapReturnFalse_when_noConflict() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.PENDING, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime requestedStartsAt = OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime requestedEndsAt = OffsetDateTime.of(2026, 6, 1, 12, 30, 0, 0, ZoneOffset.UTC);

        boolean result = bookingRepository.existsOverlap(master.getId(), requestedStartsAt, requestedEndsAt);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should_findByClientIdAndIdempotencyKey_when_exists")
    void should_findByClientIdAndIdempotencyKey_when_exists() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = Booking.builder()
                .client(clientUser)
                .master(master)
                .masterService(masterService)
                .status(BookingStatus.PENDING)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .idempotencyKey("idem-key-001")
                .build();
        em.persist(booking);
        em.flush();

        Optional<Booking> result = bookingRepository.findActiveByClientIdAndIdempotencyKey(
                clientUser.getId(), "idem-key-001");

        assertThat(result).isPresent();
        assertThat(result.get().getIdempotencyKey()).isEqualTo("idem-key-001");
    }

    @Test
    @DisplayName("should_existsOverlapReturnFalse_when_newBookingStartsExactlyAtExistingEnd")
    void should_existsOverlapReturnFalse_when_newBookingStartsExactlyAtExistingEnd() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.PENDING, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime requestedStartsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime requestedEndsAt = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        boolean result = bookingRepository.existsOverlap(master.getId(), requestedStartsAt, requestedEndsAt);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should_notReturnBooking_when_statusIsCancelled")
    void should_notReturnBooking_when_statusIsCancelled() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.CANCELLED, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime windowStart = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime windowEnd = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        List<Booking> result = bookingRepository.findOverlappingByMaster(
                master.getId(),
                windowStart,
                windowEnd
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_returnEmpty_when_idempotencyKeyNotFound")
    void should_returnEmpty_when_idempotencyKeyNotFound() {
        Optional<Booking> result = bookingRepository.findActiveByClientIdAndIdempotencyKey(
                clientUser.getId(), "idem-key-not-found");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_returnEmpty_when_ownerDoesNotOwnTheSalon")
    void should_returnEmpty_when_ownerDoesNotOwnTheSalon() {
        User salonOwner = new User(
                "owner1-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_OWNER,
                "Owner1",
                "User",
                "+380501111112"
        );
        em.persist(salonOwner);

        User otherOwner = new User(
                "owner2-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_OWNER,
                "Owner2",
                "User",
                "+380503333333"
        );
        em.persist(otherOwner);

        Salon salon = Salon.builder()
                .owner(salonOwner)
                .name("Test Salon")
                .isActive(true)
                .build();
        em.persist(salon);

        Booking booking = Booking.builder()
                .client(clientUser)
                .master(master)
                .masterService(masterService)
                .salon(salon)
                .status(BookingStatus.PENDING)
                .startsAt(OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(booking);
        em.flush();
        em.clear();

        Page<Booking> result = bookingRepository.findBySalonIdAndOwnerIdWithGraph(
                salon.getId(), otherOwner.getId(), PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_returnOnlyMatchingStatus_when_salonOwnerFiltersBookings")
    void should_returnOnlyMatchingStatus_when_salonOwnerFiltersBookings() {
        User salonOwner = new User(
                "owner-filter-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_OWNER,
                "Owner",
                "Filter",
                "+380501111115"
        );
        em.persist(salonOwner);

        Salon salon = Salon.builder()
                .owner(salonOwner)
                .name("Filter Salon")
                .isActive(true)
                .build();
        em.persist(salon);

        User salonMasterUser = new User(
                "smaster-filter-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_MASTER,
                "Salon",
                "MasterFilter",
                "+380501111116"
        );
        em.persist(salonMasterUser);

        Master salonMaster = Master.builder()
                .user(salonMasterUser)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(salonMaster);

        ServiceDefinition salonServiceDef = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salon.getId())
                .name("Pedicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("350.00"))
                .isActive(true)
                .build();
        em.persist(salonServiceDef);

        MasterServiceAssignment salonMsa = MasterServiceAssignment.builder()
                .master(salonMaster)
                .serviceDefinition(salonServiceDef)
                .isActive(true)
                .build();
        em.persist(salonMsa);

        Booking pendingBooking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.PENDING)
                .startsAt(OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 7, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("350.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(pendingBooking);

        Booking confirmedBooking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.CONFIRMED)
                .startsAt(OffsetDateTime.of(2026, 7, 2, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 7, 2, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("350.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(confirmedBooking);

        em.flush();
        em.clear();

        Page<Booking> result = bookingRepository.findBySalonIdAndOwnerIdAndStatusWithGraph(
                salon.getId(), salonOwner.getId(), BookingStatus.PENDING, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    @DisplayName("should_returnEmpty_when_noBookingsMatchStatus")
    void should_returnEmpty_when_noBookingsMatchStatus() {
        User salonOwner = new User(
                "owner-empty-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_OWNER,
                "Owner",
                "Empty",
                "+380501111117"
        );
        em.persist(salonOwner);

        Salon salon = Salon.builder()
                .owner(salonOwner)
                .name("Empty Salon")
                .isActive(true)
                .build();
        em.persist(salon);

        User salonMasterUser = new User(
                "smaster-empty-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_MASTER,
                "Salon",
                "MasterEmpty",
                "+380501111118"
        );
        em.persist(salonMasterUser);

        Master salonMaster = Master.builder()
                .user(salonMasterUser)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(salonMaster);

        ServiceDefinition salonServiceDef = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salon.getId())
                .name("Eyebrows")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(45)
                .basePrice(new BigDecimal("300.00"))
                .isActive(true)
                .build();
        em.persist(salonServiceDef);

        MasterServiceAssignment salonMsa = MasterServiceAssignment.builder()
                .master(salonMaster)
                .serviceDefinition(salonServiceDef)
                .isActive(true)
                .build();
        em.persist(salonMsa);

        Booking pendingBooking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.PENDING)
                .startsAt(OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 8, 1, 10, 45, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("300.00"))
                .durationMinutesAtBooking(45)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(pendingBooking);

        em.flush();
        em.clear();

        Page<Booking> result = bookingRepository.findBySalonIdAndOwnerIdAndStatusWithGraph(
                salon.getId(), salonOwner.getId(), BookingStatus.CONFIRMED, PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    // ── findBySalonIdAndOwnerIdWithGraph — happy-path ─────────────────────────

    @Test
    @DisplayName("should_returnOwnerBookings_when_salonOwnerQueriesAll")
    void should_returnOwnerBookings_when_salonOwnerQueriesAll() {
        User salonOwner = new User(
                "owner-happy-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_OWNER,
                "Happy",
                "Owner",
                "+380509000001"
        );
        em.persist(salonOwner);

        Salon salon = Salon.builder()
                .owner(salonOwner)
                .name("Happy Salon")
                .isActive(true)
                .build();
        em.persist(salon);

        User salonMasterUser = new User(
                "smaster-happy-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_MASTER,
                "Happy",
                "SalonMaster",
                "+380509000002"
        );
        em.persist(salonMasterUser);

        Master salonMaster = Master.builder()
                .user(salonMasterUser)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(salonMaster);

        ServiceDefinition salonServiceDef = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salon.getId())
                .name("Happy Manicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("500.00"))
                .isActive(true)
                .build();
        em.persist(salonServiceDef);

        MasterServiceAssignment salonMsa = MasterServiceAssignment.builder()
                .master(salonMaster)
                .serviceDefinition(salonServiceDef)
                .isActive(true)
                .build();
        em.persist(salonMsa);

        Booking booking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.CONFIRMED)
                .startsAt(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 9, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("500.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(booking);

        em.flush();
        em.clear();

        Page<Booking> result = bookingRepository.findBySalonIdAndOwnerIdWithGraph(
                salon.getId(), salonOwner.getId(), PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(booking.getId());
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    // ── findBySalonIdAndOwnerIdAndStatusWithGraph — happy-path ───────────────

    @Test
    @DisplayName("should_filterByStatus_when_salonOwnerPassesStatusParam")
    void should_filterByStatus_when_salonOwnerPassesStatusParam() {
        User salonOwner = new User(
                "owner-status-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_OWNER,
                "Status",
                "Owner",
                "+380509000003"
        );
        em.persist(salonOwner);

        Salon salon = Salon.builder()
                .owner(salonOwner)
                .name("Status Salon")
                .isActive(true)
                .build();
        em.persist(salon);

        User salonMasterUser = new User(
                "smaster-status-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.SALON_MASTER,
                "Status",
                "SalonMaster",
                "+380509000004"
        );
        em.persist(salonMasterUser);

        Master salonMaster = Master.builder()
                .user(salonMasterUser)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(salonMaster);

        ServiceDefinition salonServiceDef = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salon.getId())
                .name("Status Pedicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(45)
                .basePrice(new BigDecimal("400.00"))
                .isActive(true)
                .build();
        em.persist(salonServiceDef);

        MasterServiceAssignment salonMsa = MasterServiceAssignment.builder()
                .master(salonMaster)
                .serviceDefinition(salonServiceDef)
                .isActive(true)
                .build();
        em.persist(salonMsa);

        // Save one CONFIRMED booking and one COMPLETED booking for the same salon/owner.
        Booking confirmedBooking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.CONFIRMED)
                .startsAt(OffsetDateTime.of(2026, 10, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 10, 1, 10, 45, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("400.00"))
                .durationMinutesAtBooking(45)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(confirmedBooking);

        Booking completedBooking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 10, 2, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 10, 2, 10, 45, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("400.00"))
                .durationMinutesAtBooking(45)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(completedBooking);

        em.flush();
        em.clear();

        Page<Booking> result = bookingRepository.findBySalonIdAndOwnerIdAndStatusWithGraph(
                salon.getId(), salonOwner.getId(), BookingStatus.CONFIRMED, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(confirmedBooking.getId());
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("should_returnFullGraph_when_findActiveByMasterIdAndStartsAtBetweenWithGraph")
    void should_returnFullGraph_when_findActiveByMasterIdAndStartsAtBetweenWithGraph() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 11, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.CONFIRMED, startsAt, endsAt);
        em.persist(booking);
        em.flush();
        em.clear();

        OffsetDateTime from = OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        Page<Booking> result = bookingRepository.findActiveByMasterIdAndStartsAtBetweenWithGraph(
                master.getId(), from, to, org.springframework.data.domain.Pageable.ofSize(10));

        assertThat(result.getContent()).isNotEmpty();
        assertThat(result.getContent().get(0).getClient()).isNotNull();
    }

    @Test
    @DisplayName("should_findActiveByMasterIdAndStartsAtBetween_excludesCancelledBookings")
    void should_findActiveByMasterIdAndStartsAtBetween_excludesCancelledBookings() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking pendingBooking = buildBooking(BookingStatus.PENDING, startsAt, endsAt);
        em.persist(pendingBooking);

        Booking cancelledBooking = buildBooking(BookingStatus.CANCELLED, startsAt, endsAt);
        em.persist(cancelledBooking);

        em.flush();

        OffsetDateTime from = OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        Page<Booking> result = bookingRepository.findActiveByMasterIdAndStartsAtBetween(
                master.getId(), from, to, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(BookingStatus.PENDING);
    }
}
