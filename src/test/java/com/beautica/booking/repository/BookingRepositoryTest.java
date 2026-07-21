package com.beautica.booking.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BookingRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private TestEntityManager em;

    private User clientUser;
    private Master master;
    private MasterServiceAssignment masterService;
    private ServiceType defaultServiceType;

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

        defaultServiceType = persistServiceType();

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(defaultServiceType)
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

    private static final java.util.concurrent.atomic.AtomicInteger SORT_ORDER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(90_000);

    /**
     * Persists a CatalogCategory + ServiceType so fixture ServiceDefinitions satisfy the
     * NOT NULL service_type_id FK. sortOrder is unique per call (uq_service_categories_sort_order).
     */
    private ServiceType persistServiceType() {
        CatalogCategory category = CatalogCategory.builder()
                .nameUk("Нігті")
                .nameEn("Nails")
                .sortOrder(SORT_ORDER_SEQ.getAndIncrement())
                .build();
        em.persist(category);

        ServiceType serviceType = ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build();
        em.persist(serviceType);
        return serviceType;
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

        Booking booking = buildBooking(BookingStatus.CONFIRMED, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime windowStart = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime windowEnd = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        List<Booking> result = bookingRepository.findOverlappingByMaster(
                master.getId(),
                windowStart,
                windowEnd
        );

        assertThat(result)
                .hasSize(1)
                .extracting(Booking::getId)
                .containsExactly(booking.getId());
    }

    @Test
    @DisplayName("should_findOverlap_when_confirmedBookingOverlaps")
    void should_findOverlap_when_confirmedBookingOverlaps() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.CONFIRMED, startsAt, endsAt);
        em.persist(booking);
        em.flush();

        OffsetDateTime windowStart = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime windowEnd = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        List<Booking> result = bookingRepository.findOverlappingByMaster(
                master.getId(),
                windowStart,
                windowEnd
        );

        assertThat(result)
                .hasSize(1)
                .extracting(Booking::getId)
                .containsExactly(booking.getId());
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
    @DisplayName("should_existsOverlapReturnTrue_when_confirmedBookingConflicts")
    void should_existsOverlapReturnTrue_when_confirmedBookingConflicts() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.CONFIRMED, startsAt, endsAt);
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

        Booking booking = buildBooking(BookingStatus.CONFIRMED, startsAt, endsAt);
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
                .status(BookingStatus.CONFIRMED)
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

        Booking booking = buildBooking(BookingStatus.CONFIRMED, startsAt, endsAt);
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
                .status(BookingStatus.CONFIRMED)
                .startsAt(OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(booking);
        em.flush();
        em.clear();

        Page<UUID> result = bookingRepository.findIdsBySalonIdAndOwnerId(
                salon.getId(), otherOwner.getId(), PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_returnOnlyMatchingStatusId_when_salonOwnerFiltersBookings")
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
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("350.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(salonServiceDef);

        MasterServiceAssignment salonMsa = MasterServiceAssignment.builder()
                .master(salonMaster)
                .serviceDefinition(salonServiceDef)
                .isActive(true)
                .build();
        em.persist(salonMsa);

        Booking declinedBooking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.DECLINED)
                .startsAt(OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 7, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("350.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(declinedBooking);

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

        Page<UUID> idPage = bookingRepository.findIdsBySalonIdAndOwnerIdAndStatus(
                salon.getId(), salonOwner.getId(), BookingStatus.DECLINED, PageRequest.of(0, 10));

        assertThat(idPage.getTotalElements()).isEqualTo(1);
        assertThat(idPage.getContent().get(0)).isEqualTo(declinedBooking.getId());
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
                .category("MANICURE")
                .baseDurationMinutes(45)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(salonServiceDef);

        MasterServiceAssignment salonMsa = MasterServiceAssignment.builder()
                .master(salonMaster)
                .serviceDefinition(salonServiceDef)
                .isActive(true)
                .build();
        em.persist(salonMsa);

        Booking declinedBooking = Booking.builder()
                .client(clientUser)
                .master(salonMaster)
                .masterService(salonMsa)
                .salon(salon)
                .status(BookingStatus.DECLINED)
                .startsAt(OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 8, 1, 10, 45, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("300.00"))
                .durationMinutesAtBooking(45)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(declinedBooking);

        em.flush();
        em.clear();

        Page<UUID> result = bookingRepository.findIdsBySalonIdAndOwnerIdAndStatus(
                salon.getId(), salonOwner.getId(), BookingStatus.CONFIRMED, PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    // ── findIdsBySalonIdAndOwnerId — happy-path ───────────────────────────────

    @Test
    @DisplayName("should_returnOwnerBookingId_when_salonOwnerQueriesAll")
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
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .serviceType(defaultServiceType)
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

        Page<UUID> idPage = bookingRepository.findIdsBySalonIdAndOwnerId(
                salon.getId(), salonOwner.getId(), PageRequest.of(0, 10));

        assertThat(idPage.getTotalElements()).isEqualTo(1);
        assertThat(idPage.getContent().get(0)).isEqualTo(booking.getId());

        // Verify the batch-hydrate query fetches the full graph for the returned IDs
        List<Booking> hydrated = bookingRepository.findAllByIdsWithGraph(idPage.getContent());
        assertThat(hydrated).hasSize(1);
        assertThat(hydrated.get(0).getId()).isEqualTo(booking.getId());
        assertThat(hydrated.get(0).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(hydrated.get(0).getClient()).isNotNull();
        assertThat(hydrated.get(0).getMaster()).isNotNull();
    }

    // ── findIdsBySalonIdAndOwnerIdAndStatus — happy-path ─────────────────────

    @Test
    @DisplayName("should_returnOnlyMatchingStatusIds_when_salonOwnerPassesStatusParam")
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
                .category("MANICURE")
                .baseDurationMinutes(45)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("400.00"))
                .serviceType(defaultServiceType)
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

        Page<UUID> idPage = bookingRepository.findIdsBySalonIdAndOwnerIdAndStatus(
                salon.getId(), salonOwner.getId(), BookingStatus.CONFIRMED, PageRequest.of(0, 10));

        assertThat(idPage.getTotalElements()).isEqualTo(1);
        assertThat(idPage.getContent().get(0)).isEqualTo(confirmedBooking.getId());
    }

    @Test
    @DisplayName("should_returnBookingId_when_findActiveIdsByMasterIdAndStartsAtBetween")
    void should_returnBookingId_when_findActiveIdsByMasterIdAndStartsAtBetween() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 11, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(BookingStatus.CONFIRMED, startsAt, endsAt);
        em.persist(booking);
        em.flush();
        em.clear();

        OffsetDateTime from = OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        Page<UUID> idPage = bookingRepository.findActiveIdsByMasterIdAndStartsAtBetween(
                master.getId(), from, to, org.springframework.data.domain.Pageable.ofSize(10));

        assertThat(idPage.getContent()).isNotEmpty();
        assertThat(idPage.getContent().get(0)).isEqualTo(booking.getId());

        // Verify batch-hydrate loads the full graph for the returned IDs
        List<Booking> hydrated = bookingRepository.findAllByIdsWithGraph(idPage.getContent());
        assertThat(hydrated).hasSize(1);
        assertThat(hydrated.get(0).getClient()).isNotNull();
        assertThat(hydrated.get(0).getMaster()).isNotNull();
    }

    // ── findGuestBookingsForReminder — already-reminded exclusion pin ──────────
    // The BookingReminderJobTest comment claims "already-reminded excluded by query" but the
    // unit test only exercises the empty-result path. This DataJpaTest pins the real contract:
    // the JPQL filters reminderSent = false, so a guest booking already marked reminderSent=true
    // is NOT re-fetched (and therefore never double-SMS'd) on the next hourly sweep.

    @Test
    @DisplayName("should_excludeAlreadyRemindedGuestBooking_when_findGuestBookingsForReminder")
    void should_excludeAlreadyRemindedGuestBooking_when_findGuestBookingsForReminder() {
        // Two non-overlapping slots for the same master (the DB exclusion constraint
        // no_overlapping_bookings forbids same-master time overlap), both inside the sweep window.
        OffsetDateTime dueStart = OffsetDateTime.of(2026, 6, 2, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime dueEnd = OffsetDateTime.of(2026, 6, 2, 11, 0, 0, 0, ZoneOffset.UTC);

        // Due, not yet reminded → MUST be returned.
        Booking due = Booking.guestBooking(
                master, masterService, null, dueStart, dueEnd,
                new BigDecimal("450.00"), null, 60, 0, "Олена", "Коваль", "+380501234567");
        em.persist(due);

        // Later same-day slot, already reminded → MUST be excluded by the query.
        Booking alreadyReminded = Booking.guestBooking(
                master, masterService, null,
                OffsetDateTime.of(2026, 6, 2, 14, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 2, 15, 0, 0, 0, ZoneOffset.UTC),
                new BigDecimal("450.00"), null, 60, 0, "Ірина", "Левко", "+380507654321");
        alreadyReminded.setReminderSent(true);
        em.persist(alreadyReminded);
        em.flush();
        em.clear();

        OffsetDateTime from = OffsetDateTime.of(2026, 6, 2, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(2026, 6, 3, 0, 0, 0, 0, ZoneOffset.UTC);

        List<Booking> result = bookingRepository.findGuestBookingsForReminder(from, to);

        assertThat(result)
                .as("only the not-yet-reminded due booking is returned; the reminderSent=true "
                        + "booking is excluded so it is never re-sent")
                .extracting(Booking::getId)
                .containsExactly(due.getId());
    }

    // ── findFirstConflictingClientBookingId[Excluding] (Phase 19.4, real DB) ──────
    //
    // The service-layer tests (BookingServiceTest) exercise these methods only via
    // Mockito. This section pins the actual native-query contract against real Postgres:
    // status filtering (CONFIRMED conflicts; every terminal status does not),
    // half-open boundary, ORDER BY starts_at ASC LIMIT 1 determinism, cross-master scope
    // (client_id only, no master_id predicate), and the excluding-variant's self-exclusion.

    /** A second master + master-service, independent of {@link #master}/{@link #masterService}. */
    private Master secondMasterWithService() {
        User secondMasterUser = new User(
                "master2-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.INDEPENDENT_MASTER,
                "Second",
                "Master",
                "+380503333334"
        );
        em.persist(secondMasterUser);

        Master secondMaster = Master.builder()
                .user(secondMasterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(secondMaster);
        return secondMaster;
    }

    private MasterServiceAssignment serviceFor(Master m) {
        ServiceDefinition def = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(m.getId())
                .name("Pedicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("400.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(def);

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .master(m)
                .serviceDefinition(def)
                .isActive(true)
                .build();
        em.persist(msa);
        return msa;
    }

    private Booking bookingFor(User client, Master m, MasterServiceAssignment msa,
                                BookingStatus status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return Booking.builder()
                .client(client)
                .master(m)
                .masterService(msa)
                .status(status)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("400.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
    }

    @Test
    @DisplayName("should_findConflict_when_overlappingBookingIsWithADifferentMaster")
    void should_findConflict_when_overlappingBookingIsWithADifferentMaster() {
        Master otherMaster = secondMasterWithService();
        MasterServiceAssignment otherMsa = serviceFor(otherMaster);
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        Booking existing = bookingFor(clientUser, otherMaster, otherMsa, BookingStatus.CONFIRMED, startsAt, endsAt);
        em.persist(existing);
        em.flush();

        // Requested window is for a DIFFERENT master (master, not otherMaster) — the query
        // is scoped by client_id only, so it must still find the cross-master conflict.
        Optional<UUID> result = bookingRepository.findFirstConflictingClientBookingId(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC));

        assertThat(result).contains(existing.getId());
    }

    @Test
    @DisplayName("should_returnEmpty_when_noOverlappingClientBookingExists")
    void should_returnEmpty_when_noOverlappingClientBookingExists() {
        Optional<UUID> result = bookingRepository.findFirstConflictingClientBookingId(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_returnEmpty_when_newBookingStartsExactlyWhenClientsExistingBookingEnds_halfOpenBoundary")
    void should_returnEmpty_when_newBookingStartsExactlyWhenClientsExistingBookingEnds() {
        Booking existing = bookingFor(clientUser, master, masterService, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC));
        em.persist(existing);
        em.flush();

        Optional<UUID> result = bookingRepository.findFirstConflictingClientBookingId(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC));

        assertThat(result)
                .as("back-to-back is allowed — the half-open interval must not treat an "
                        + "exact-boundary touch as an overlap")
                .isEmpty();
    }

    @Test
    @DisplayName("should_findConflict_when_confirmedClientBookingOverlaps")
    void should_findConflict_when_confirmedClientBookingOverlaps() {
        Booking existing = bookingFor(clientUser, master, masterService, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC));
        em.persist(existing);
        em.flush();

        Optional<UUID> result = bookingRepository.findFirstConflictingClientBookingId(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC));

        assertThat(result).contains(existing.getId());
    }

    @Test
    @DisplayName("should_returnEmpty_when_overlappingClientBookingIsCancelled")
    void should_returnEmpty_when_overlappingClientBookingIsCancelled() {
        assertNoConflict_forTerminalStatus(BookingStatus.CANCELLED);
    }

    @Test
    @DisplayName("should_returnEmpty_when_overlappingClientBookingIsDeclined")
    void should_returnEmpty_when_overlappingClientBookingIsDeclined() {
        assertNoConflict_forTerminalStatus(BookingStatus.DECLINED);
    }

    @Test
    @DisplayName("should_returnEmpty_when_overlappingClientBookingIsCompleted")
    void should_returnEmpty_when_overlappingClientBookingIsCompleted() {
        assertNoConflict_forTerminalStatus(BookingStatus.COMPLETED);
    }

    @Test
    @DisplayName("should_returnEmpty_when_overlappingClientBookingIsNotCompleted")
    void should_returnEmpty_when_overlappingClientBookingIsNotCompleted() {
        assertNoConflict_forTerminalStatus(BookingStatus.NOT_COMPLETED);
    }

    private void assertNoConflict_forTerminalStatus(BookingStatus terminalStatus) {
        Booking existing = bookingFor(clientUser, master, masterService, terminalStatus,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC));
        em.persist(existing);
        em.flush();

        Optional<UUID> result = bookingRepository.findFirstConflictingClientBookingId(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC));

        assertThat(result)
                .as("a %s booking must never be reported as a client conflict", terminalStatus)
                .isEmpty();
    }

    @Test
    @DisplayName("should_returnEarliestConflict_when_clientHoldsMultipleOverlappingBookings")
    void should_returnEarliestConflict_when_clientHoldsMultipleOverlappingBookings() {
        Master otherMaster = secondMasterWithService();
        MasterServiceAssignment otherMsa = serviceFor(otherMaster);

        Booking later = bookingFor(clientUser, master, masterService, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC));
        Booking earlier = bookingFor(clientUser, otherMaster, otherMsa, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC));
        em.persist(later);
        em.persist(earlier);
        em.flush();

        // A wide requested window overlaps BOTH — ORDER BY starts_at ASC LIMIT 1 must
        // deterministically surface the earlier one.
        Optional<UUID> result = bookingRepository.findFirstConflictingClientBookingId(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 9, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC));

        assertThat(result).contains(earlier.getId());
    }

    @Test
    @DisplayName("should_excludeOwnRow_when_findFirstConflictingClientBookingIdExcluding")
    void should_excludeOwnRow_when_findFirstConflictingClientBookingIdExcluding() {
        Booking booking = bookingFor(clientUser, master, masterService, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC));
        em.persist(booking);
        em.flush();

        // The requested window overlaps the booking's OWN stored window — with the
        // exclusion, this must NOT be reported as a conflict against itself.
        Optional<UUID> excluded = bookingRepository.findFirstConflictingClientBookingIdExcluding(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC),
                booking.getId());
        assertThat(excluded).isEmpty();

        // Sanity: WITHOUT the exclusion the same window IS reported as a conflict —
        // proves the excluding-variant's empty result above is due to the exclusion,
        // not to some other predicate mismatch.
        Optional<UUID> notExcluded = bookingRepository.findFirstConflictingClientBookingId(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC));
        assertThat(notExcluded).contains(booking.getId());
    }

    @Test
    @DisplayName("should_stillFindOtherConflict_when_findFirstConflictingClientBookingIdExcludingOnlyExcludesOneRow")
    void should_stillFindOtherConflict_when_excludingOnlyOneOfTwoOverlappingBookings() {
        Master otherMaster = secondMasterWithService();
        MasterServiceAssignment otherMsa = serviceFor(otherMaster);

        Booking excludedBooking = bookingFor(clientUser, master, masterService, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC));
        Booking otherConflict = bookingFor(clientUser, otherMaster, otherMsa, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 10, 15, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 15, 0, 0, ZoneOffset.UTC));
        em.persist(excludedBooking);
        em.persist(otherConflict);
        em.flush();

        Optional<UUID> result = bookingRepository.findFirstConflictingClientBookingIdExcluding(
                clientUser.getId(),
                OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC),
                excludedBooking.getId());

        assertThat(result)
                .as("excluding one overlapping row must not suppress a genuine conflict from another")
                .contains(otherConflict.getId());
    }

    // ── findActiveTimeRangesByMasterInRange — the two-column availability projection (Perf MEDIUM-1) ──
    //
    // The availability computation (calendar day projection + free-slot gate) reads bookings ONLY through
    // this JPQL constructor projection, which must reproduce findOverlappingByMaster's predicate exactly:
    // CONFIRMED only, master-scoped, [starts_at < windowEnd AND ends_at > windowStart), ordered by
    // start. These pin the data-correctness contract the whole booking-day fix rides on.

    private static final OffsetDateTime WINDOW_START =
            OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime WINDOW_END =
            OffsetDateTime.of(2026, 6, 2, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("should_projectStartAndEnd_forConfirmedBookingsOrderedByStart")
    void should_projectStartAndEnd_forConfirmedBookingsInRange() {
        // track 24.x: only CONFIRMED is active — two CONFIRMED bookings exercise the ordering.
        Booking later = buildBooking(BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 14, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 15, 0, 0, 0, ZoneOffset.UTC));
        Booking earlier = buildBooking(BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        em.persist(later);
        em.persist(earlier);
        em.flush();

        List<BookingTimeRange> result = bookingRepository.findActiveTimeRangesByMasterInRange(
                master.getId(), WINDOW_START, WINDOW_END);

        // Ordered by startsAt ASC (earlier 09:00 before later 14:00), projecting exactly the two columns.
        assertThat(result)
                .as("both active bookings are projected, ordered by start, as (startsAt, endsAt) tuples")
                .extracting(BookingTimeRange::startsAt, BookingTimeRange::endsAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC)),
                        org.assertj.core.groups.Tuple.tuple(
                                OffsetDateTime.of(2026, 6, 1, 14, 0, 0, 0, ZoneOffset.UTC),
                                OffsetDateTime.of(2026, 6, 1, 15, 0, 0, 0, ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("should_excludeTerminalStatuses_when_projectingActiveTimeRanges")
    void should_excludeTerminalStatuses_when_projectingActiveTimeRanges() {
        // Every non-active status parked in the window — none may leak into the availability projection,
        // or a cancelled/declined slot would wrongly read as occupied and hide a genuinely free day.
        int hour = 9;
        for (BookingStatus terminal : List.of(
                BookingStatus.DECLINED, BookingStatus.CANCELLED,
                BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED)) {
            em.persist(buildBooking(terminal,
                    OffsetDateTime.of(2026, 6, 1, hour, 0, 0, 0, ZoneOffset.UTC),
                    OffsetDateTime.of(2026, 6, 1, hour + 1, 0, 0, 0, ZoneOffset.UTC)));
            hour += 2;
        }
        em.flush();

        List<BookingTimeRange> result = bookingRepository.findActiveTimeRangesByMasterInRange(
                master.getId(), WINDOW_START, WINDOW_END);

        assertThat(result)
                .as("DECLINED / CANCELLED / COMPLETED / NOT_COMPLETED never occupy availability")
                .isEmpty();
    }

    @Test
    @DisplayName("should_excludeBoundaryAbuttingBookings_when_projectingActiveTimeRanges")
    void should_excludeBoundaryAbuttingBookings_when_projectingActiveTimeRanges() {
        // Entirely before the window, ending EXACTLY at windowStart (ends_at > windowStart is FALSE) — out.
        Booking before = buildBooking(BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 5, 31, 23, 0, 0, 0, ZoneOffset.UTC),
                WINDOW_START);
        // Starts EXACTLY at windowEnd (starts_at < windowEnd is FALSE) — out.
        Booking after = buildBooking(BookingStatus.CONFIRMED,
                WINDOW_END,
                OffsetDateTime.of(2026, 6, 2, 1, 0, 0, 0, ZoneOffset.UTC));
        // A plainly in-window booking as the positive control. None of the three overlap each other
        // (before ends 00:00, within is 10:00–11:00, after starts 00:00 next day), so all coexist.
        Booking within = buildBooking(BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC));
        em.persist(before);
        em.persist(after);
        em.persist(within);
        em.flush();

        List<BookingTimeRange> result = bookingRepository.findActiveTimeRangesByMasterInRange(
                master.getId(), WINDOW_START, WINDOW_END);

        assertThat(result)
                .as("half-open [windowStart, windowEnd): a booking ending exactly at windowStart or "
                        + "starting exactly at windowEnd is out; only the in-window booking survives")
                .extracting(BookingTimeRange::startsAt)
                .containsExactly(OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_includeTailSpillingAcrossWindowStart_when_projectingActiveTimeRanges")
    void should_includeTailSpillingAcrossWindowStart_when_projectingActiveTimeRanges() {
        // Starts on the previous day, tail spills past windowStart (ends_at > windowStart) — included,
        // exactly as the day-bucketing relies on to occupy the first in-window day.
        Booking spillIn = buildBooking(BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 5, 31, 23, 30, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 6, 1, 0, 30, 0, 0, ZoneOffset.UTC));
        em.persist(spillIn);
        em.flush();

        List<BookingTimeRange> result = bookingRepository.findActiveTimeRangesByMasterInRange(
                master.getId(), WINDOW_START, WINDOW_END);

        assertThat(result)
                .as("a booking whose tail spills past windowStart is returned by the overlap predicate")
                .extracting(BookingTimeRange::startsAt, BookingTimeRange::endsAt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        OffsetDateTime.of(2026, 5, 31, 23, 30, 0, 0, ZoneOffset.UTC),
                        OffsetDateTime.of(2026, 6, 1, 0, 30, 0, 0, ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("should_scopeToMaster_when_projectingActiveTimeRanges")
    void should_excludeOtherMastersBookings_when_projectingActiveTimeRanges() {
        User otherMasterUser = new User(
                "other-master-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash", Role.INDEPENDENT_MASTER, "Other", "Master", "+380509999999");
        em.persist(otherMasterUser);
        Master otherMaster = Master.builder()
                .user(otherMasterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(otherMaster);
        ServiceDefinition otherDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(otherMaster.getId())
                .name("Other")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(otherDef);
        MasterServiceAssignment otherAssignment = MasterServiceAssignment.builder()
                .master(otherMaster)
                .serviceDefinition(otherDef)
                .isActive(true)
                .build();
        em.persist(otherAssignment);

        Booking otherMasterBooking = Booking.builder()
                .client(clientUser)
                .master(otherMaster)
                .masterService(otherAssignment)
                .status(BookingStatus.CONFIRMED)
                .startsAt(OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(otherMasterBooking);
        em.flush();

        List<BookingTimeRange> result = bookingRepository.findActiveTimeRangesByMasterInRange(
                master.getId(), WINDOW_START, WINDOW_END);

        assertThat(result)
                .as("the projection is master-scoped — another master's booking never occupies this master")
                .isEmpty();
    }

}
