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

        Page<Booking> result = bookingRepository.findBySalonIdAndOwnerId(
                salon.getId(), otherOwner.getId(), PageRequest.of(0, 10));

        assertThat(result).isEmpty();
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
